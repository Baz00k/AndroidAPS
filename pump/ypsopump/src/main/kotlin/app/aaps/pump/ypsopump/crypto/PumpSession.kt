package app.aaps.pump.ypsopump.crypto

import java.security.MessageDigest
import java.util.UUID

/** Serialized durable counter ownership. Persistence always precedes publication or dispatch. */
class PumpSession(private val store: Store) {

    interface Store {
        /** Missing, corrupt, restored or incompletely committed storage throws. */
        fun load(): State
        fun commit(state: State)
        /** Explicit disaster-recovery replacement; ordinary stores must reject it. */
        fun replaceUnavailable(state: State) { throw UnsupportedOperationException("Store cannot replace unavailable state") }
    }

    enum class Phase { RESERVED, POSSIBLY_SENT, ACKED, VERIFIED }
    enum class WriteResolution { ACCEPTED, REJECTED_COUNTER_CONSUMED, REJECTED_COUNTER_NOT_CONSUMED }
    enum class WriteCandidate {
        STANDARD,
        /** Selector-only recovery from a durable lower bound after journal loss. */
        LOWER_BOUND_HISTORY_RECOVERY_SELECTOR,
    }
    enum class WriteBootstrapState { UNKNOWN_MID_EPOCH, OBSERVED_NEW_EPOCH, RECOVERING_LOWER_BOUND, ESTABLISHED }
    enum class AvailabilityCause {
        UNCONFIGURED,
        BOND_OR_PERMISSION,
        TRANSPORT,
        AUTHENTICATION,
        ENCRYPTED_STATUS_UNAVAILABLE,
        KEY_REJECTED,
        SUSPECTED_REKEY_REQUIRED,
        COUNTER_UNCERTAIN,
        IDENTITY_MISMATCH
    }
    data class Availability(
        val causes: Set<AvailabilityCause> = setOf(AvailabilityCause.UNCONFIGURED),
        val since: Long = 0,
        val code: Int? = null,
        val operation: String? = null,
        val firmware: String? = null,
        val failures: Int = 0,
        val retryAt: Long? = null
    )
    data class Reservation(
        val id: String,
        val counter: Long,
        val phase: Phase,
        val operationId: String? = null,
        val characteristic: String? = null,
        val purpose: String? = null,
        val payloadHash: String? = null,
        /** Exact durable write floor before this candidate. */
        val priorWrite: Long,
        val candidate: WriteCandidate = WriteCandidate.STANDARD,
    )
    data class WriteEvidence(
        val operationId: String,
        val reservationId: String,
        val counter: Long,
        val characteristic: String,
        val purpose: String,
        val payloadHash: String,
        val priorWrite: Long,
        val candidate: WriteCandidate,
        val resolution: WriteResolution?,
        val evidenceHash: String,
        val detail: String,
    )
    data class WriteIntent(val operationId: String, val characteristic: String, val purpose: String, val payloadHash: String)
    enum class AttemptStatus { PENDING, SUCCEEDED, FAILED, CANCELLED }
    data class AttemptResult(val id: String, val status: AttemptStatus)
    data class Record(
        val pump: String,
        val keyId: String,
        val generation: String,
        val reboot: Int?,
        val read: Long?,
        val write: Long?,
        val reservation: Reservation? = null,
        val serial: String = "",
        val keyHex: String? = null,
        val createdAt: Long? = null,
        val importedAt: Long? = null,
        val source: Map<String, String> = emptyMap(),
        val verifiedAt: Long? = null,
        val verifiedSerial: String? = null,
        val writeEvidence: List<WriteEvidence> = emptyList(),
        /** Write ownership is explicit: ordinary reads cannot turn an unknown mid-epoch floor into a usable floor. */
        val writeBootstrapState: WriteBootstrapState =
            if (write == null) WriteBootstrapState.UNKNOWN_MID_EPOCH else WriteBootstrapState.ESTABLISHED,
        /** Number of consecutive pump-confirmed APPERR_COUNTER_ERROR responses. */
        val counterRecoveryExponent: Int = 0,
        /** Exact epoch of independently bound lower-bound evidence; present only during recovery. */
        val lowerBoundRecoveryReboot: Int? = null,
    )
    data class State(
        val records: List<Record> = emptyList(),
        val activeGeneration: String? = null,
        val availability: Availability = Availability(),
        val candidateGeneration: String? = null,
        val candidateReplacesGeneration: String? = null,
        val candidateAvailability: Availability? = null,
        val candidateAttemptId: String? = null,
        val lastAttempt: AttemptResult? = null
    )
    data class Provisioning(
        val pump: String,
        val serial: String,
        val sharedKey: ByteArray,
        val createdAt: Long?,
        val importedAt: Long,
        val source: Map<String, String>
    )
    enum class Installation { SAME_KEY, ROTATED_KEY, SWITCHED_PUMP, FIRST_PUMP }
    class Token internal constructor(val generation: String, val connection: String)

    // A completed attempt is feedback for the interaction that produced it, not durable state: a
    // fresh process renders the journaled availability instead of replaying the previous result.
    private val loadedState = runCatching { compactCompletedEvidence(store.load().also(::validate)) }
    internal val loadFailureLocation: String? = loadedState.exceptionOrNull()?.let { error ->
        if (error is SessionJournal.AnchorMismatch) "anchor_count=${error.count},contains_current=${error.containsCurrent}"
        else error.javaClass.simpleName + ":" + error.stackTrace.firstOrNull { it.className.startsWith("app.aaps.pump.ypsopump") }
    }
    private var state: State? = loadedState.getOrNull()?.copy(lastAttempt = null)
    private var record: Record? = null
    private var token: Token? = null
    private var key: ByteArray? = null
    private var transaction: String? = null

    /** A reconnect never resets counters. An import alone cannot establish a replay baseline. */
    @Synchronized
    fun open(pump: String, sharedKey: ByteArray): Token {
        require(sharedKey.size == SessionCrypto.KEY_SIZE)
        quiesce()
        val id = fingerprint(sharedKey)
        val current = state
        val selected = current?.candidateGeneration ?: current?.activeGeneration
        val saved = current?.records?.singleOrNull { it.pump == pump && it.keyId == id && it.generation == selected }
            ?: throw SecurityException("Session recovery required: no durable replay baseline")
        check(saved.keyHex == null || saved.keyHex.equals(sharedKey.toHex(), ignoreCase = true)) { "Protected key does not match session record" }
        record = saved
        key = sharedKey.copyOf()
        return Token(saved.generation, UUID.randomUUID().toString()).also { token = it }
    }

    /** Durably stages credentials without changing the active identity/key bundle. */
    @Synchronized
    fun install(provisioning: Provisioning): Installation {
        val plan = planInstallation(provisioning)
        val current = state ?: throw SecurityException("Session storage unavailable")
        val withoutOldCandidate = retireCandidate(current).copy(lastAttempt = current.candidateAttemptId?.let {
            AttemptResult(it, AttemptStatus.CANCELLED)
        } ?: current.lastAttempt)
        val staged = plan.installed.copy(
            generation = plan.installed.generation.takeIf {
                current.records.singleOrNull { record -> record.generation == current.candidateGeneration }?.keyId == plan.installed.keyId
            }
                ?: UUID.randomUUID().toString()
        )
        // A staged bundle that reuses the superseded candidate's generation replaces it in place;
        // otherwise the superseded candidate is retained as an inactive tombstone when it already
        // learned an authenticated replay floor.
        val records = withoutOldCandidate.records.filterNot { it.generation == staged.generation } + staged
        // A fresh bundle starts a fresh verification cycle. Only a suspected re-key survives until a
        // verified read clears it; stale transport/auth/identity failures and their backoff must never
        // cross into the new identity. Failures/retry reset so the new bundle can verify immediately.
        val priorAvailability = current.candidateAvailability ?: current.availability
        val retainedRekey = priorAvailability.causes.intersect(setOf(AvailabilityCause.SUSPECTED_REKEY_REQUIRED))
        val nextCauses = retainedRekey + AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE + AvailabilityCause.COUNTER_UNCERTAIN
        persist(
            current.copy(
                records = records,
                activeGeneration = withoutOldCandidate.activeGeneration,
                candidateGeneration = staged.generation,
                candidateReplacesGeneration = plan.previous?.generation,
                candidateAttemptId = UUID.randomUUID().toString(),
                lastAttempt = withoutOldCandidate.lastAttempt,
                candidateAvailability = priorAvailability.copy(
                    causes = nextCauses,
                    since = priorAvailability.since.takeIf { retainedRekey.isNotEmpty() } ?: provisioning.importedAt,
                    failures = 0,
                    retryAt = null
                )
            )
        )
        quiesce()
        return plan.installation
    }

    /**
     * One-way recovery when the protected journal is already unreadable. This records an
     * independently proven local allocation lower bound; the read floor is established by the first
     * authenticated read, and writes reconcile above the bound through the pump-confirmed search.
     */
    @Synchronized
    internal fun recoverLostJournalLowerBound(
        provisioning: Provisioning,
        lowerBound: Long,
        recoveryReboot: Int,
        evidenceHash: String,
    ) {
        check(state == null && loadedState.isFailure) { "Journal-loss recovery requires an unavailable journal" }
        require(provisioning.pump.isNotBlank() && provisioning.serial.isNotBlank())
        require(provisioning.sharedKey.size == SessionCrypto.KEY_SIZE && provisioning.sharedKey.any { it.toInt() != 0 })
        require(lowerBound > 0)
        require(recoveryReboot >= 0)
        require(evidenceHash.matches(SHA256_HEX))
        val record = Record(
            pump = provisioning.pump,
            keyId = fingerprint(provisioning.sharedKey),
            generation = UUID.randomUUID().toString(),
            reboot = null,
            read = null,
            write = lowerBound,
            serial = provisioning.serial,
            keyHex = provisioning.sharedKey.toHex(),
            createdAt = provisioning.createdAt,
            importedAt = provisioning.importedAt,
            source = provisioning.source + mapOf(
                "journal_loss_recovery_evidence_sha256" to evidenceHash,
                "journal_loss_failure" to checkNotNull(loadFailureLocation),
            ),
            writeBootstrapState = WriteBootstrapState.RECOVERING_LOWER_BOUND,
            lowerBoundRecoveryReboot = recoveryReboot,
        )
        val recovered = State(
            records = listOf(record),
            activeGeneration = record.generation,
            availability = Availability(setOf(AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE, AvailabilityCause.COUNTER_UNCERTAIN)),
        )
        try {
            validate(recovered)
            store.replaceUnavailable(recovered)
            state = recovered
        } catch (e: Exception) {
            state = null
            quiesce()
            throw SecurityException("Session journal recovery failed", e)
        }
        quiesce()
    }

    /**
     * Replace an already-unreadable journal with identity/key only. The first authenticated pump read
     * establishes a read replay floor; the write floor stays UNKNOWN_MID_EPOCH until the first
     * accepted write, reconciled from zero against the pump.
     */
    @Synchronized
    internal fun recoverLostJournalIdentityOnly(provisioning: Provisioning, documentHash: String) {
        check(state == null && loadedState.isFailure) { "Identity-only recovery requires an unavailable journal" }
        require(provisioning.pump.isNotBlank() && provisioning.serial.isNotBlank())
        require(provisioning.sharedKey.size == SessionCrypto.KEY_SIZE && provisioning.sharedKey.any { it.toInt() != 0 })
        require(documentHash.matches(SHA256_HEX))
        val record = Record(
            pump = provisioning.pump,
            keyId = fingerprint(provisioning.sharedKey),
            generation = UUID.randomUUID().toString(),
            reboot = null,
            read = null,
            write = null,
            serial = provisioning.serial,
            keyHex = provisioning.sharedKey.toHex(),
            createdAt = provisioning.createdAt,
            importedAt = provisioning.importedAt,
            source = provisioning.source + mapOf(
                "journal_loss_identity_document_sha256" to documentHash,
                "journal_loss_failure" to checkNotNull(loadFailureLocation),
            ),
            writeBootstrapState = WriteBootstrapState.UNKNOWN_MID_EPOCH,
        )
        val recovered = State(
            records = listOf(record),
            activeGeneration = record.generation,
            availability = Availability(setOf(AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE, AvailabilityCause.COUNTER_UNCERTAIN)),
        )
        try {
            validate(recovered)
            store.replaceUnavailable(recovered)
            state = recovered
        } catch (e: Exception) {
            state = null
            quiesce()
            throw SecurityException("Identity-only session journal recovery failed", e)
        }
        quiesce()
    }

    /** Validates a replacement before callers quiesce the current transport. */
    @Synchronized
    fun preflight(provisioning: Provisioning): Installation = planInstallation(provisioning).installation

    private fun planInstallation(provisioning: Provisioning): InstallationPlan {
        require(provisioning.pump.isNotBlank() && provisioning.serial.isNotBlank())
        require(provisioning.sharedKey.size == SessionCrypto.KEY_SIZE && provisioning.sharedKey.any { it.toInt() != 0 })
        require(provisioning.createdAt == null || provisioning.createdAt <= provisioning.importedAt)
        val current = state ?: throw SecurityException("Session storage unavailable")
        val id = fingerprint(provisioning.sharedKey)
        val oldCandidate = current.records.singleOrNull { it.generation == current.candidateGeneration }
        val previous = current.records.singleOrNull { it.keyId == id && it.generation != current.candidateGeneration }
        if (previous != null) check(previous.pump == provisioning.pump) { "Key belongs to another pump" }
        val active = current.records.singleOrNull { it.generation == current.activeGeneration }
        val currentBundle = active ?: oldCandidate
        val unresolved = listOfNotNull(active, previous, oldCandidate).distinctBy(Record::generation)
            .firstOrNull { it.reservation != null && it.reservation.phase != Phase.VERIFIED }
        if (unresolved != null)
            throw SecurityException("Cannot replace a session with unresolved pump accounting")
        val installation = when {
            previous != null -> Installation.SAME_KEY
            oldCandidate?.keyId == id -> Installation.SAME_KEY
            currentBundle == null -> Installation.FIRST_PUMP
            currentBundle.pump == provisioning.pump -> Installation.ROTATED_KEY
            else -> Installation.SWITCHED_PUMP
        }
        // The freshest authenticated state for the submitted key wins as the staging baseline: when the
        // current candidate already carries this key, its learned floor must not be replaced by an older
        // predecessor snapshot.
        val baseline = oldCandidate?.takeIf { it.keyId == id } ?: previous
        val installed = baseline?.copy(
            serial = provisioning.serial,
            keyHex = provisioning.sharedKey.toHex(),
            createdAt = provisioning.createdAt ?: baseline.createdAt,
            importedAt = provisioning.importedAt,
            source = provisioning.source,
            verifiedAt = null,
            verifiedSerial = null
        ) ?: Record(
            pump = provisioning.pump,
            keyId = id,
            generation = UUID.randomUUID().toString(),
            reboot = null,
            read = null,
            write = null,
            serial = provisioning.serial,
            keyHex = provisioning.sharedKey.toHex(),
            createdAt = provisioning.createdAt,
            importedAt = provisioning.importedAt,
            source = provisioning.source
        )
        return InstallationPlan(installation, previous, installed)
    }

    private data class InstallationPlan(val installation: Installation, val previous: Record?, val installed: Record)

    @Synchronized
    fun activeRecord(): Record? = candidateRecord() ?: committedRecord()

    @Synchronized
    fun recordForGeneration(generation: String): Record? = state?.records?.singleOrNull { it.generation == generation }

    @Synchronized
    fun committedRecord(): Record? = state?.records?.singleOrNull { it.generation == state?.activeGeneration }

    @Synchronized
    fun candidateRecord(): Record? = state?.records?.singleOrNull { it.generation == state?.candidateGeneration }

    @Synchronized
    fun verificationAttempt(): AttemptResult? = state?.let { current ->
        current.candidateAttemptId?.let { AttemptResult(it, AttemptStatus.PENDING) } ?: current.lastAttempt
    }

    @Synchronized
    fun availability(): Availability = state?.let { it.candidateAvailability ?: it.availability }
        ?: Availability(setOf(AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE))

    @Synchronized
    fun setAvailability(availability: Availability) {
        val current = state ?: throw SecurityException("Session storage unavailable")
        persist(if (current.candidateGeneration != null) current.copy(candidateAvailability = availability) else current.copy(availability = availability))
    }

    @Synchronized
    fun markVerified(serial: String, at: Long): Boolean {
        val current = state ?: throw SecurityException("Session storage unavailable")
        check(current.candidateGeneration == null) { "Stale verification callback" }
        val generation = current.activeGeneration
        val active = current.records.singleOrNull { it.generation == generation }
            ?: throw SecurityException("No active session")
        check(active.serial == serial) { "Verified pump serial does not match configured identity" }
        return promote(current, active, serial, at)
    }

    /**
     * Promote only the candidate attempt used by this connection. Generation and attempt identity are
     * checked in the same synchronized transaction as the promotion, so an old callback cannot slip
     * through a same-generation restage and promote the newer attempt.
     */
    @Synchronized
    fun markVerified(generation: String, attemptId: String?, serial: String, at: Long): Boolean {
        val current = state ?: throw SecurityException("Session storage unavailable")
        if (current.candidateGeneration != null)
            check(current.candidateGeneration == generation && current.candidateAttemptId == attemptId) { "Stale verification callback" }
        else
            check(attemptId == null && current.activeGeneration == generation) { "Stale verification callback" }
        val active = current.records.singleOrNull { it.generation == generation }
            ?: throw SecurityException("No active session")
        check(active.serial == serial) { "Verified pump serial does not match configured identity" }
        return promote(current, active, serial, at)
    }

    private fun promote(current: State, active: Record, serial: String, at: Long): Boolean {
        val next = active.copy(verifiedAt = at, verifiedSerial = serial)
        val promoted = current.candidateGeneration?.let {
            val final = current.candidateReplacesGeneration?.let { replaced -> next.copy(generation = replaced) } ?: next
            current.copy(
                records = current.records.filterNot { it.generation == next.generation || it.generation == current.candidateReplacesGeneration } + final,
                activeGeneration = final.generation,
                candidateGeneration = null,
                candidateReplacesGeneration = null,
                candidateAvailability = null,
                candidateAttemptId = null,
                lastAttempt = AttemptResult(checkNotNull(current.candidateAttemptId), AttemptStatus.SUCCEEDED),
                availability = Availability(
                    causes = if (next.writeBootstrapState == WriteBootstrapState.ESTABLISHED) emptySet() else setOf(AvailabilityCause.COUNTER_UNCERTAIN),
                    since = at
                )
            )
        } ?: current.copy(
            records = current.records.map { if (it.generation == next.generation) next else it },
            availability = Availability(
                if (next.writeBootstrapState == WriteBootstrapState.ESTABLISHED) emptySet() else setOf(AvailabilityCause.COUNTER_UNCERTAIN),
                at,
            )
        )
        persist(promoted)
        // A candidate promotion changes the installed credential bundle, so any token opened with
        // the staged record must be discarded.  An ordinary verified refresh does not: callers may
        // safely perform another read on the same authenticated GATT connection.
        val promotedCandidate = current.candidateGeneration != null
        if (promotedCandidate) quiesce()
        return promotedCandidate
    }

    /** Cancel a staged bundle while preserving any authenticated floor learned with an existing key. */
    @Synchronized
    fun cancelCandidate(status: AttemptStatus = AttemptStatus.CANCELLED) {
        require(status == AttemptStatus.CANCELLED || status == AttemptStatus.FAILED)
        val current = state ?: throw SecurityException("Session storage unavailable")
        if (current.candidateGeneration == null) return
        val attemptId = checkNotNull(current.candidateAttemptId)
        persist(retireCandidate(current).copy(lastAttempt = AttemptResult(attemptId, status)))
        quiesce()
    }

    /** Cancel only the candidate owned by this verification start; a newer install is untouched. */
    @Synchronized
    fun cancelCandidate(generation: String, attemptId: String?, status: AttemptStatus = AttemptStatus.CANCELLED): Boolean {
        require(status == AttemptStatus.CANCELLED || status == AttemptStatus.FAILED)
        val current = state ?: throw SecurityException("Session storage unavailable")
        if (current.candidateGeneration != generation || current.candidateAttemptId != attemptId) return false
        persist(retireCandidate(current).copy(lastAttempt = AttemptResult(checkNotNull(attemptId), status)))
        quiesce()
        return true
    }

    /**
     * Re-establish a retained bundle as the active, still-unverified session after a replacement
     * failed. The supplied availability (including a failure's retry backoff when present) is
     * preserved; promotion still requires an independent read. Never call while a candidate or
     * committed session remains.
     */
    @Synchronized
    fun activateUnverified(provisioning: Provisioning, availability: Availability) {
        require(provisioning.pump.isNotBlank() && provisioning.serial.isNotBlank())
        require(provisioning.sharedKey.size == SessionCrypto.KEY_SIZE && provisioning.sharedKey.any { it.toInt() != 0 })
        val current = state ?: throw SecurityException("Session storage unavailable")
        check(current.activeGeneration == null && current.candidateGeneration == null) { "A session is already active" }
        val id = fingerprint(provisioning.sharedKey)
        val existing = current.records.singleOrNull { it.keyId == id }
        if (existing != null) check(existing.pump == provisioning.pump) { "Key belongs to another pump" }
        val retained = existing?.copy(
            serial = provisioning.serial,
            keyHex = provisioning.sharedKey.toHex(),
            createdAt = provisioning.createdAt ?: existing.createdAt,
            importedAt = provisioning.importedAt,
            source = provisioning.source,
            verifiedAt = null,
            verifiedSerial = null
        ) ?: Record(
            pump = provisioning.pump,
            keyId = id,
            generation = UUID.randomUUID().toString(),
            reboot = null,
            read = null,
            write = null,
            serial = provisioning.serial,
            keyHex = provisioning.sharedKey.toHex(),
            createdAt = provisioning.createdAt,
            importedAt = provisioning.importedAt,
            source = provisioning.source
        )
        persist(
            current.copy(
                records = current.records.filterNot { it.generation == retained.generation } + retained,
                activeGeneration = retained.generation,
                availability = availability
            )
        )
        quiesce()
    }

    /** Atomically rejects precisely this candidate and retains its actionable failure on the restored bundle. */
    @Synchronized
    fun failCandidate(generation: String, attemptId: String?, availability: Availability): Boolean {
        val current = state ?: throw SecurityException("Session storage unavailable")
        if (current.candidateGeneration != generation || current.candidateAttemptId != attemptId) return false
        persist(retireCandidate(current).copy(availability = availability, lastAttempt = AttemptResult(checkNotNull(attemptId), AttemptStatus.FAILED)))
        quiesce()
        return true
    }

    @Synchronized
    fun openGeneration(generation: String, pump: String, sharedKey: ByteArray): Token {
        require(sharedKey.size == SessionCrypto.KEY_SIZE)
        quiesce()
        val current = state
        check(current?.candidateGeneration == generation || current?.candidateGeneration == null && current?.activeGeneration == generation) {
            "Session generation is no longer selected"
        }
        val saved = current.records.singleOrNull { it.generation == generation && it.pump == pump && it.keyId == fingerprint(sharedKey) }
            ?: throw SecurityException("Session recovery required: no durable replay baseline")
        check(saved.keyHex == null || saved.keyHex.equals(sharedKey.toHex(), ignoreCase = true)) { "Protected key does not match session record" }
        record = saved
        key = sharedKey.copyOf()
        return Token(saved.generation, UUID.randomUUID().toString()).also { token = it }
    }

    private fun retireCandidate(current: State): State {
        val candidate = current.records.singleOrNull { it.generation == current.candidateGeneration } ?: return current.copy(
            candidateGeneration = null, candidateReplacesGeneration = null, candidateAvailability = null, candidateAttemptId = null
        )
        val replaced = current.records.singleOrNull { it.generation == current.candidateReplacesGeneration }
        val records = if (replaced != null && replaced.keyId == candidate.keyId) {
            // Replay state is epoch-coupled: a validated reboot transition replaces the whole tuple.
            // Merging a numeric max across reboot generations would mix floors and resurrect counters.
            val merged = if (candidate.reboot != null && candidate.reboot != replaced.reboot) {
                replaced.copy(
                    reboot = candidate.reboot,
                    read = candidate.read,
                    write = candidate.write,
                    reservation = candidate.reservation,
                    writeEvidence = mergeWriteEvidence(replaced.writeEvidence, candidate.writeEvidence),
                    writeBootstrapState = candidate.writeBootstrapState,
                )
            } else {
                replaced.copy(
                    reboot = candidate.reboot ?: replaced.reboot,
                    read = listOfNotNull(replaced.read, candidate.read).maxOrNull(),
                    write = candidate.write ?: replaced.write,
                    reservation = candidate.reservation ?: replaced.reservation,
                    writeEvidence = mergeWriteEvidence(replaced.writeEvidence, candidate.writeEvidence),
                    writeBootstrapState = candidate.writeBootstrapState,
                )
            }
            current.records.filterNot { it.generation == candidate.generation || it.generation == replaced.generation } + merged
        } else if (replaced == null && current.records.none { it.generation != candidate.generation && it.keyId == candidate.keyId }) {
            // An authenticated read may already have advanced this candidate's replay floor before a later
            // CRC/schema/identity rejection. Retaining the learned floor as an inactive tombstone keeps a
            // re-import of the same key from accepting the same counter again; without a floor it is
            // discarded rather than accumulating dead records.
            if (candidate.reboot != null || candidate.read != null || candidate.write != null || candidate.reservation != null)
                current.records
            else current.records - candidate
        } else current.records // A different key remains an inactive replay tombstone.
        return current.copy(
            records = records,
            candidateGeneration = null,
            candidateReplacesGeneration = null,
            candidateAvailability = null,
            candidateAttemptId = null
        )
    }

    @Synchronized
    fun quiesce() {
        token = null
        record = null
        key?.fill(0)
        key = null
        transaction = null
    }

    @Synchronized
    fun begin(origin: Token): String {
        owned(origin)
        check(transaction == null) { "Another session transaction is active" }
        return UUID.randomUUID().toString().also { transaction = it }
    }

    @Synchronized
    fun finish(origin: Token, id: String) {
        if (token == origin && transaction == id) transaction = null
    }

    @Synchronized
    internal fun accept(origin: Token, id: String, message: SessionCrypto.Message, allowObservedReboot: Boolean = false): ByteArray {
        val old = owned(origin)
        check(transaction == id) { "Stale transaction" }
        require(message.reboot >= 0 && message.counter >= 0) { "Counter outside supported signed range" }
        if (old.reboot == null || old.read == null) {
            require(message.counter > 0) { "Initial current-pump read counter must be positive" }
            if (old.writeBootstrapState == WriteBootstrapState.RECOVERING_LOWER_BOUND) {
                require(message.reboot == checkNotNull(old.lowerBoundRecoveryReboot)) {
                    "Authenticated pump epoch does not match lower-bound evidence"
                }
            }
            update(old.copy(reboot = message.reboot, read = message.counter))
            return message.body
        }
        if (message.reboot != old.reboot) {
            // V05.00.52 storage-mode capture: same key, reboot +1, read reset to 1.
            // A missed first response is allowed; neither old read nor write floor seeds this epoch.
            if (!allowObservedReboot || old.reboot == Int.MAX_VALUE || message.reboot != old.reboot + 1 || message.counter == 0L)
                throw SecurityException("Unvalidated reboot transition")
            old.reservation?.takeIf { it.phase != Phase.VERIFIED }?.let { unresolved ->
                check(
                    old.writeEvidence.any {
                        it.reservationId == unresolved.id &&
                            it.operationId == unresolved.operationId &&
                            it.counter == unresolved.counter &&
                            it.characteristic == unresolved.characteristic &&
                            it.purpose == unresolved.purpose &&
                            it.payloadHash == unresolved.payloadHash &&
                            it.priorWrite == unresolved.priorWrite &&
                            it.candidate == unresolved.candidate &&
                            it.resolution == null
                    },
                ) { "Cannot transition with an unreviewed outstanding write record" }
            }
            val next = old.copy(
                reboot = message.reboot,
                read = message.counter,
                write = null,
                reservation = null,
                writeBootstrapState = WriteBootstrapState.OBSERVED_NEW_EPOCH,
            )
            update(next)
            quiesce()
            throw RebootAdoptedException()
        }
        if (message.counter <= old.read) throw SecurityException("Replayed or rolled-back read")
        update(old.copy(read = message.counter))
        return message.body // Later CRC/schema rejection must not undo this durable replay floor.
    }

    @Synchronized
    fun decrypt(origin: Token, id: String, payload: ByteArray, crypto: SessionCrypto, allowObservedReboot: Boolean = false): ByteArray {
        owned(origin)
        check(transaction == id) { "Stale transaction" }
        return accept(origin, id, crypto.decrypt(payload, checkNotNull(key)), allowObservedReboot)
    }

    /** Persisted transition; discard this response and reconnect with a new connection token. */
    class RebootAdoptedException : SecurityException("Authenticated reboot adopted; reconnect required")

    /**
     * Reserve above the durable local high-water mark; the pump does not require contiguous counters.
     * An unknown floor is a normal state: allocation starts at zero and each pump-confirmed
     * counter rejection advances the exponential search through [counterRecoveryExponent].
     */
    @Synchronized
    fun reserve(origin: Token, id: String, intent: WriteIntent? = null): Reservation {
        return reserveCandidate(origin, id, intent, WriteCandidate.STANDARD)
    }

    /** Reserve only an event-history selector while recovering from an independently proven lower bound. */
    @Synchronized
    internal fun reserveLowerBoundHistoryRecovery(origin: Token, id: String, intent: WriteIntent): Reservation {
        val old = owned(origin)
        check(old.writeBootstrapState == WriteBootstrapState.RECOVERING_LOWER_BOUND && old.write != null) {
            "Session is not recovering a lower write bound"
        }
        require(intent.characteristic.lowercase() == EVENT_INDEX_CHARACTERISTIC && intent.purpose == "HISTORY_SELECTOR") {
            "Lower-bound recovery permits only the event-history selector"
        }
        return reserveCandidate(origin, id, intent, WriteCandidate.LOWER_BOUND_HISTORY_RECOVERY_SELECTOR)
    }

    private fun reserveCandidate(origin: Token, id: String, intent: WriteIntent?, candidate: WriteCandidate): Reservation {
        val old = owned(origin)
        check(transaction == id) { "Stale transaction" }
        check(old.reservation == null || old.reservation.phase == Phase.VERIFIED) { "Unresolved write" }
        // A missing floor is the unknown-mid-epoch starting point, not a blocker. The pump accepts
        // any counter above its last accepted value, so the search starts at zero and only a
        // pump-confirmed rejection advances it.
        val last = old.write ?: 0L
        val increment = counterRecoveryIncrement(old.counterRecoveryExponent)
        check(last <= Long.MAX_VALUE - increment) { "Write counter exhausted" }
        intent?.let {
            require(it.operationId.isNotBlank() && it.characteristic.isNotBlank() && it.purpose.isNotBlank())
            require(it.payloadHash.matches(SHA256_HEX))
        }
        val reserved = Reservation(
            id,
            last + increment,
            Phase.RESERVED,
            intent?.operationId,
            intent?.characteristic,
            intent?.purpose,
            intent?.payloadHash,
            priorWrite = last,
            candidate = candidate,
        )
        update(old.copy(write = reserved.counter, reservation = reserved))
        return reserved
    }

    @Synchronized
    fun advance(origin: Token, id: String, phase: Phase) {
        val old = owned(origin)
        check(transaction == id) { "Stale transaction" }
        val reserved = checkNotNull(old.reservation)
        check(reserved.id == id && phase.ordinal == reserved.phase.ordinal + 1) { "Invalid write transition" }
        update(old.copy(reservation = reserved.copy(phase = phase)))
    }

    /**
     * Release a reservation only when local dispatch was proven not to have started. Persistence of
     * POSSIBLY_SENT must precede the platform dispatch call; a false/throwing dispatch result may then
     * safely restore the prior counter. A crash between those steps remains uncertain by design.
     */
    @Synchronized
    fun markNotSent(origin: Token, id: String) {
        val old = owned(origin)
        check(transaction == id) { "Stale transaction" }
        val reserved = checkNotNull(old.reservation)
        check(reserved.id == id && reserved.phase in setOf(Phase.RESERVED, Phase.POSSIBLY_SENT)) { "Write already acknowledged" }
        check(old.write == reserved.counter && reserved.priorWrite in 0 until reserved.counter) {
            "Invalid write reservation"
        }
        update(restoreAfterNotConsumed(old, reserved, evidence = null))
    }

    /** A persisted RESERVED phase proves the dispatch boundary was never committed and is safe to roll back after restart. */
    @Synchronized
    fun recoverReservedNotSent(origin: Token, operationId: String, evidenceHash: String, detail: String) {
        val old = owned(origin)
        check(transaction == null) { "Another session transaction is active" }
        val reserved = checkNotNull(old.reservation)
        check(reserved.operationId == operationId && reserved.phase == Phase.RESERVED) { "Write is not proven undispatched" }
        check(old.write == reserved.counter && reserved.priorWrite in 0 until reserved.counter) {
            "Invalid write reservation"
        }
        require(evidenceHash.matches(SHA256_HEX) && detail.isNotBlank() && detail.length <= 4096)
        val evidence = writeEvidence(reserved, WriteResolution.REJECTED_COUNTER_NOT_CONSUMED, evidenceHash, detail)
        update(restoreAfterNotConsumed(old, reserved, evidence))
    }

    /**
     * Retire an interrupted transport reservation without claiming that its command succeeded or
     * failed. YpsoPump accepts any counter above its last accepted counter: keeping our allocated
     * high-water mark makes the next reservation safe whether this write was consumed or not.
     * Burn a small forward block on reconnect to recover promptly when the last controller's
     * persisted position lags the pump. This is allocation headroom, not a pump gap restriction.
     * Call only after the old transport owner has been released. Therapy effect/retry decisions
     * remain the responsibility of the durable domain journal, not this counter allocator.
     */
    @Synchronized
    fun recoverInterruptedWrite(origin: Token) {
        val old = owned(origin)
        check(transaction == null) { "Another session transaction is active" }
        val reserved = old.reservation?.takeIf { it.phase != Phase.VERIFIED } ?: return
        // Every reconciliation candidate allocates above its durable floor, so the retained counter
        // is a safe next search start regardless of write-bootstrap state.
        check(old.write != null) { "Write high-water mark is unavailable" }
        check(old.write >= reserved.counter) { "Reservation exceeds write high-water mark" }
        val detail = "Interrupted transport retired at phase=${reserved.phase}; counter retained as high-water mark; command outcome unknown"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("${reserved.id}:$detail".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val evidence = writeEvidence(reserved, null, hash, detail)
        update(old.copy(reservation = null, writeEvidence = old.writeEvidence + evidence))
    }

    /**
     * Retires an ordinary event-history or setting selector write left unresolved by a connection that
     * is gone. A selector move has no therapy effect, so its unknown outcome matters only for the
     * counter, which stays retained as the high-water mark exactly as in [recoverInterruptedWrite].
     * Anything else stays blocking: therapy writes, and lower-bound recovery probes, whose ambiguity
     * must be resolved by pump evidence. [reservationId] must still be the current reservation.
     *
     * @return whether a reservation was retired
     */
    @Synchronized
    fun retireAbandonedSelector(origin: Token, reservationId: String): Boolean {
        val old = owned(origin)
        check(transaction == null) { "Another session transaction is active" }
        val reserved = old.reservation?.takeIf { it.id == reservationId && it.phase != Phase.VERIFIED } ?: return false
        if (!isAbandonableSelector(reserved)) return false
        recoverInterruptedWrite(origin)
        return true
    }

    /**
     * Pump-originated APPERR_COUNTER_ERROR (139): the command was rejected and the next gap doubles
     * until [MAX_COUNTER_RECOVERY_EXPONENT]. The exponent bounds the increment, not the rejection
     * record: a 139 at the cap is still persisted, and later candidates advance by the largest
     * increment while counter arithmetic cannot wrap.
     */
    @Synchronized
    fun rejectCounterTooLow(origin: Token, reservationId: String, evidenceHash: String, detail: String) {
        val old = owned(origin)
        check(transaction == null) { "Another session transaction is active" }
        val reserved = checkNotNull(old.reservation)
        check(reserved.id == reservationId && reserved.phase in setOf(Phase.POSSIBLY_SENT, Phase.ACKED)) {
            "Write is not awaiting counter-error recovery"
        }
        require(evidenceHash.matches(SHA256_HEX) && detail.isNotBlank() && detail.length <= 4096)
        val nextExponent =
            if (old.counterRecoveryExponent < MAX_COUNTER_RECOVERY_EXPONENT) old.counterRecoveryExponent + 1
            else old.counterRecoveryExponent
        val evidence = writeEvidence(reserved, WriteResolution.REJECTED_COUNTER_NOT_CONSUMED, evidenceHash, detail)
        update(old.copy(reservation = null, writeEvidence = old.writeEvidence + evidence, counterRecoveryExponent = nextExponent))
    }

    /** Persist reviewed evidence that does not yet classify counter consumption; the reservation remains blocking. */
    @Synchronized
    fun recordUnresolvedWriteEvidence(
        origin: Token,
        reservationId: String,
        evidenceHash: String,
        detail: String
    ) {
        val old = owned(origin)
        check(transaction == null) { "Another session transaction is active" }
        val reserved = checkNotNull(old.reservation)
        check(reserved.id == reservationId && reserved.phase in setOf(Phase.POSSIBLY_SENT, Phase.ACKED)) {
            "Write is not awaiting reconciliation"
        }
        require(evidenceHash.matches(SHA256_HEX) && detail.isNotBlank() && detail.length <= 4096)
        val evidence = writeEvidence(reserved, null, evidenceHash, detail)
        check(old.writeEvidence.none { it.reservationId == reserved.id && it.evidenceHash == evidenceHash }) {
            "Evidence already recorded"
        }
        update(old.copy(writeEvidence = old.writeEvidence + evidence))
    }

    /** Encrypt exactly the already-persisted reservation; encryption itself cannot choose a counter. */
    @Synchronized
    fun encryptReserved(origin: Token, id: String, command: ByteArray, crypto: SessionCrypto): ByteArray {
        val old = owned(origin)
        check(transaction == id) { "Stale transaction" }
        val reserved = checkNotNull(old.reservation)
        check(reserved.id == id && reserved.phase == Phase.RESERVED) { "Write is not reserved for encryption" }
        reserved.payloadHash?.let { expected ->
            check(MessageDigest.getInstance("SHA-256").digest(command).joinToString("") { "%02x".format(it) } == expected) {
                "Reserved plaintext does not match write intent"
            }
        }
        return crypto.encrypt(command, checkNotNull(key), checkNotNull(old.reboot), reserved.counter)
    }

    /**
     * Apply measured semantic and counter evidence after the transport transaction has released its
     * live lock. Until this succeeds the durable reservation continues to block every later write.
     */
    @Synchronized
    fun resolveWrite(
        origin: Token,
        reservationId: String,
        resolution: WriteResolution,
        evidenceHash: String,
        detail: String
    ) {
        val old = owned(origin)
        check(transaction == null) { "Another session transaction is active" }
        val reserved = checkNotNull(old.reservation)
        check(reserved.id == reservationId && reserved.phase in setOf(Phase.POSSIBLY_SENT, Phase.ACKED)) { "Write is not awaiting reconciliation" }
        require(evidenceHash.matches(SHA256_HEX) && detail.isNotBlank() && detail.length <= 4096)
        val evidence = writeEvidence(reserved, resolution, evidenceHash, detail)
        val next = when (resolution) {
            // A pump-confirmed acceptance proves the durable floor, so an unknown mid-epoch allocation
            // becomes ESTABLISHED on its first accepted counter instead of staying write-blocked.
            WriteResolution.ACCEPTED ->
                old.copy(
                    reservation = reserved.copy(phase = Phase.VERIFIED),
                    writeEvidence = old.writeEvidence + evidence,
                    writeBootstrapState = WriteBootstrapState.ESTABLISHED,
                    counterRecoveryExponent = 0,
                    lowerBoundRecoveryReboot = null,
                )
            WriteResolution.REJECTED_COUNTER_CONSUMED ->
                old.copy(reservation = reserved.copy(phase = Phase.VERIFIED), writeEvidence = old.writeEvidence + evidence)
            WriteResolution.REJECTED_COUNTER_NOT_CONSUMED ->
                restoreAfterNotConsumed(old, reserved, evidence)
        }
        update(next)
        // Establishing the floor clears the informational uncertainty cause.
        if (next.writeBootstrapState == WriteBootstrapState.ESTABLISHED &&
            old.writeBootstrapState != WriteBootstrapState.ESTABLISHED
        ) {
            val current = checkNotNull(state)
            val available = current.availability.copy(
                causes = current.availability.causes - AvailabilityCause.COUNTER_UNCERTAIN,
                retryAt = null,
            )
            persist(current.copy(availability = available))
            record = checkNotNull(state).records.single { it.generation == next.generation }
        }
    }

    @Synchronized
    fun snapshot(): Record? = record

    private fun writeEvidence(
        reservation: Reservation,
        resolution: WriteResolution?,
        evidenceHash: String,
        detail: String,
    ) =
        WriteEvidence(
            operationId = checkNotNull(reservation.operationId),
            reservationId = reservation.id,
            counter = reservation.counter,
            characteristic = checkNotNull(reservation.characteristic),
            purpose = checkNotNull(reservation.purpose),
            payloadHash = checkNotNull(reservation.payloadHash),
            priorWrite = reservation.priorWrite,
            candidate = reservation.candidate,
            resolution = resolution,
            evidenceHash = evidenceHash,
            detail = detail,
        )

    private fun restoreAfterNotConsumed(old: Record, reservation: Reservation, evidence: WriteEvidence?): Record =
        old.copy(
            write = reservation.priorWrite,
            reservation = null,
            writeEvidence = evidence?.let { old.writeEvidence + it } ?: old.writeEvidence,
        )

    internal fun isAbandonableSelector(reservation: Reservation): Boolean =
        reservation.candidate == WriteCandidate.STANDARD &&
            isReadSelector(reservation.characteristic, reservation.purpose)

    private fun owned(origin: Token): Record {
        check(token == origin && state != null) { "Stale or unavailable session" }
        return checkNotNull(record)
    }

    private fun update(next: Record) {
        val current = checkNotNull(state)
        persist(current.copy(records = current.records.map { if (it.generation == next.generation) next else it }))
        record = checkNotNull(state).records.single { it.generation == next.generation }
    }

    private fun mergeWriteEvidence(
        retained: List<WriteEvidence>,
        candidate: List<WriteEvidence>,
    ): List<WriteEvidence> =
        (retained + candidate).distinctBy { it.reservationId to it.evidenceHash }

    private fun persist(next: State) {
        try {
            validate(next)
            val compacted = compactCompletedEvidence(next)
            store.commit(compacted)
            state = compacted
        } catch (e: Exception) {
            state = null
            quiesce()
            throw SecurityException("Session journal commit failed", e)
        }
    }

    companion object {
        const val MAX_COUNTER_RECOVERY_EXPONENT = 20

        fun counterRecoveryIncrement(exponent: Int): Long {
            require(exponent in 0..MAX_COUNTER_RECOVERY_EXPONENT)
            return 1L shl maxOf(0, exponent - 1)
        }
        private fun isCounterRecoveryIncrement(value: Long): Boolean =
            value > 0 && value <= counterRecoveryIncrement(MAX_COUNTER_RECOVERY_EXPONENT) && value and (value - 1) == 0L
        private const val EVENT_INDEX_CHARACTERISTIC = "669a0c20-0008-969e-e211-fcbecc3b7bc5"
        private const val SETTING_ID_CHARACTERISTIC = "669a0c20-0008-969e-e211-fcbeb3147bc5"
        private const val BOLUS_COMMAND_CHARACTERISTIC = "669a0c20-0008-969e-e211-fcbee18b7bc5"
        private const val TBR_COMMAND_CHARACTERISTIC = "669a0c20-0008-969e-e211-fcbee38b7bc5"
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private val THERAPY_COMMAND_CHARACTERISTICS = setOf(BOLUS_COMMAND_CHARACTERISTIC, TBR_COMMAND_CHARACTERISTIC)

        private fun isReadSelector(characteristic: String?, purpose: String?): Boolean =
            when (purpose) {
                "HISTORY_SELECTOR" -> characteristic?.lowercase() == EVENT_INDEX_CHARACTERISTIC
                "SETTINGS_SELECTOR" -> characteristic?.lowercase() == SETTING_ID_CHARACTERISTIC
                else -> false
            }

        fun fingerprint(key: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }

        /**
         * Replay protection uses durable counters and the current reservation; bolus/TBR delivery,
         * retries and treatment accounting use separate journals. The accepted-proof tails are
         * diagnostic only, kept separate so history scans cannot evict recent therapy proofs.
         * Uncertain/rejected writes, unknown commands, lower-bound recovery probes and
         * current-reservation proofs must remain. Validate before trimming so corrupt evidence
         * cannot disappear.
         */
        private fun compactCompletedEvidence(state: State): State = state.copy(records = state.records.map { record ->
            if (record.writeEvidence.size <= COMPLETED_EVIDENCE_LIMIT) return@map record
            var selectors = 0
            var therapy = 0
            val retained = record.writeEvidence.asReversed().filter { evidence ->
                val completed = evidence.candidate == WriteCandidate.STANDARD && evidence.resolution == WriteResolution.ACCEPTED
                val withinTail = when {
                    completed && isReadSelector(evidence.characteristic, evidence.purpose) ->
                        ++selectors <= COMPLETED_EVIDENCE_LIMIT
                    completed && evidence.purpose == "THERAPY_COMMAND" &&
                        evidence.characteristic.lowercase() in THERAPY_COMMAND_CHARACTERISTICS ->
                        ++therapy <= COMPLETED_EVIDENCE_LIMIT
                    else -> true
                }
                withinTail || evidence.reservationId == record.reservation?.id
            }.asReversed()
            if (retained.size == record.writeEvidence.size) record else record.copy(writeEvidence = retained)
        })

        private const val COMPLETED_EVIDENCE_LIMIT = 32

        fun validate(state: State) {
            val duplicateKeys = state.records.groupBy { it.keyId }.filterValues { it.size > 1 }
            require(duplicateKeys.all { (_, records) ->
                records.size == 2 && state.candidateGeneration in records.map(Record::generation) &&
                    state.candidateReplacesGeneration in records.map(Record::generation)
            })
            require(state.records.map { it.generation }.distinct().size == state.records.size)
            state.records.forEach { r ->
                require(r.pump.isNotBlank() && r.generation.isNotBlank() && r.keyId.matches(SHA256_HEX))
                require((r.reboot == null) == (r.read == null))
                require((r.reboot == null || r.reboot >= 0) && (r.read == null || r.read >= 0) && (r.write == null || r.write >= 0))
                require(r.keyHex == null || r.keyHex.matches(SHA256_HEX) && fingerprint(r.keyHex.unhex()) == r.keyId)
                require(r.serial.isNotBlank() || r.keyHex == null)
                require(r.verifiedSerial == null || r.verifiedSerial == r.serial && r.verifiedAt != null)
                when (r.writeBootstrapState) {
                    // Unknown floor is reconciled by ordinary allocation starting at zero; `write`
                    // holds the highest allocated search position until acceptance establishes ownership.
                    WriteBootstrapState.UNKNOWN_MID_EPOCH,
                    WriteBootstrapState.OBSERVED_NEW_EPOCH ->
                        require(r.reservation == null || r.reservation.candidate == WriteCandidate.STANDARD)
                    WriteBootstrapState.RECOVERING_LOWER_BOUND -> require(r.write != null && r.lowerBoundRecoveryReboot != null)
                    WriteBootstrapState.ESTABLISHED -> require(r.write != null)
                }
                if (r.writeBootstrapState != WriteBootstrapState.RECOVERING_LOWER_BOUND) require(r.lowerBoundRecoveryReboot == null)
                require(r.counterRecoveryExponent in 0..MAX_COUNTER_RECOVERY_EXPONENT)
                r.reservation?.let {
                    require(it.id.isNotBlank() && it.counter > 0 && it.counter == r.write)
                    require(it.priorWrite >= 0)
                    when (it.candidate) {
                        WriteCandidate.STANDARD ->
                            require(
                                if (it.phase == Phase.VERIFIED) isCounterRecoveryIncrement(it.counter - it.priorWrite)
                                else it.counter - it.priorWrite == counterRecoveryIncrement(r.counterRecoveryExponent)
                            )
                        WriteCandidate.LOWER_BOUND_HISTORY_RECOVERY_SELECTOR -> {
                            require(it.characteristic?.lowercase() == EVENT_INDEX_CHARACTERISTIC && it.purpose == "HISTORY_SELECTOR")
                            require(
                                if (it.phase == Phase.VERIFIED) {
                                    r.writeBootstrapState == WriteBootstrapState.ESTABLISHED && isCounterRecoveryIncrement(it.counter - it.priorWrite)
                                } else {
                                    r.writeBootstrapState == WriteBootstrapState.RECOVERING_LOWER_BOUND &&
                                        it.counter - it.priorWrite == counterRecoveryIncrement(r.counterRecoveryExponent)
                                },
                            )
                        }
                    }
                    require((it.operationId == null) == (it.characteristic == null) && (it.characteristic == null) == (it.purpose == null) && (it.purpose == null) == (it.payloadHash == null))
                    require(it.operationId == null || it.operationId.isNotBlank() && it.characteristic!!.isNotBlank() && it.purpose!!.isNotBlank() && it.payloadHash!!.matches(SHA256_HEX))
                }
                require(
                    r.writeEvidence.map { it.reservationId to it.evidenceHash }.distinct().size == r.writeEvidence.size
                )
                r.writeEvidence.forEach {
                    require(
                        it.operationId.isNotBlank() &&
                            it.reservationId.isNotBlank() &&
                            it.counter > 0 &&
                            it.characteristic.isNotBlank() &&
                            it.purpose.isNotBlank() &&
                            it.payloadHash.matches(SHA256_HEX) &&
                            it.priorWrite >= 0 &&
                            isCounterRecoveryIncrement(it.counter - it.priorWrite),
                    )
                    if (it.candidate == WriteCandidate.LOWER_BOUND_HISTORY_RECOVERY_SELECTOR) {
                        require(it.characteristic.lowercase() == EVENT_INDEX_CHARACTERISTIC && it.purpose == "HISTORY_SELECTOR")
                    }
                    r.reservation?.takeIf { reservation -> reservation.id == it.reservationId }?.let { reservation ->
                        require(
                            reservation.operationId == it.operationId &&
                                reservation.counter == it.counter &&
                                reservation.characteristic == it.characteristic &&
                                reservation.purpose == it.purpose &&
                                reservation.payloadHash == it.payloadHash &&
                                reservation.priorWrite == it.priorWrite &&
                                reservation.candidate == it.candidate,
                        )
                    }
                    require(it.evidenceHash.matches(SHA256_HEX) && it.detail.isNotBlank() && it.detail.length <= 4096)
                }
            }
            require(state.activeGeneration == null || state.records.count { it.generation == state.activeGeneration } == 1)
            require(state.candidateGeneration == null || state.records.count { it.generation == state.candidateGeneration } == 1)
            require(state.candidateGeneration == null || state.candidateGeneration != state.activeGeneration)
            require(state.candidateGeneration == null || state.candidateGeneration != state.candidateReplacesGeneration)
            require((state.candidateGeneration == null) == (state.candidateAvailability == null))
            require((state.candidateGeneration == null) == (state.candidateAttemptId == null))
            require(state.candidateAttemptId == null || state.candidateAttemptId.isNotBlank())
            require(state.lastAttempt == null || state.lastAttempt.id.isNotBlank() && state.lastAttempt.status != AttemptStatus.PENDING)
            require(state.candidateReplacesGeneration == null || state.records.count { it.generation == state.candidateReplacesGeneration } == 1)
            require(state.candidateReplacesGeneration == null || state.candidateGeneration != null)
            val candidateRecord = state.records.singleOrNull { it.generation == state.candidateGeneration }
            val replacedRecord = state.records.singleOrNull { it.generation == state.candidateReplacesGeneration }
            require(candidateRecord == null || replacedRecord == null || candidateRecord.keyId == replacedRecord.keyId)
            require(state.availability.failures >= 0)
            require((state.candidateAvailability?.failures ?: 0) >= 0)
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
        private fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
