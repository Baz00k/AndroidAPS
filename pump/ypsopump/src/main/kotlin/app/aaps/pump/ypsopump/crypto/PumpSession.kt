package app.aaps.pump.ypsopump.crypto

import java.security.MessageDigest
import java.util.UUID

/** Serialized durable counter ownership. Persistence always precedes publication or dispatch. */
class PumpSession(private val store: Store) {

    interface Store {
        /** Missing, corrupt, restored or incompletely committed storage throws. */
        fun load(): State
        fun commit(state: State)
    }

    enum class Phase { RESERVED, POSSIBLY_SENT, ACKED, VERIFIED }
    enum class WriteResolution { ACCEPTED, REJECTED_COUNTER_CONSUMED, REJECTED_COUNTER_NOT_CONSUMED }
    enum class WriteCandidate {
        STANDARD,
        BENCH_STRICT_NEXT_SELECTOR,
        BENCH_FORWARD_GAP_SELECTOR,
        BENCH_NEW_EPOCH_BOOTSTRAP_SELECTOR,
        BENCH_AMBIGUITY_CONVERGENCE_SELECTOR,
        BENCH_DUPLICATE_COUNTER_SELECTOR,
        /**
         * Read-only compatibility for the one completed alarm-cursor recovery experiment recorded
         * before Step 08. No reservation API exposes this candidate, so a current artifact can
         * preserve and account for that authenticated historical write but can never dispatch it.
         */
        LEGACY_BENCH_ALARM_CURSOR_RECOVERY_SELECTOR,
    }
    enum class WriteBootstrapState { UNKNOWN_MID_EPOCH, OBSERVED_NEW_EPOCH, ESTABLISHED }
    enum class HistoryFamily { ALARM, SYSTEM }
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
        /** Exact durable write floor before this candidate; absent only in legacy journals. */
        val priorWrite: Long? = null,
        val candidate: WriteCandidate = WriteCandidate.STANDARD,
        val historyBinding: HistoryWriteBinding? = null,
        val acceptedPredecessor: AcceptedWriteBinding? = null,
        val unresolvedPredecessor: UnresolvedWriteBinding? = null,
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
        val historyBinding: HistoryWriteBinding? = null,
        val acceptedPredecessor: AcceptedWriteBinding? = null,
        val unresolvedPredecessor: UnresolvedWriteBinding? = null,
    )
    data class WriteIntent(val operationId: String, val characteristic: String, val purpose: String, val payloadHash: String)
    data class BootstrapReference(
        val reboot: Int,
        val read: Long,
        val characteristic: String,
        val payloadHash: String,
    )
    data class HistoryCountEvidence(
        val family: HistoryFamily,
        val reboot: Int,
        val read: Long,
        val count: Int,
        val characteristic: String,
        val payloadHash: String,
    )
    data class HistorySelectorState(
        val family: HistoryFamily,
        val reboot: Int,
        val read: Long,
        val index: Int,
        val characteristic: String,
        val payloadHash: String,
    )
    data class HistoryWriteBinding(
        val count: HistoryCountEvidence,
        val selectedBefore: HistorySelectorState,
        val writeIndex: Int,
    )
    data class AcceptedWriteBinding(
        val reboot: Int,
        val operationId: String,
        val reservationId: String,
        val counter: Long,
        val characteristic: String,
        val purpose: String,
        val payloadHash: String,
        val priorWrite: Long,
        val candidate: WriteCandidate,
        val evidenceHash: String,
        val unresolvedPredecessor: UnresolvedWriteBinding? = null,
    ) {
        fun matches(evidence: WriteEvidence): Boolean =
            operationId == evidence.operationId &&
                reservationId == evidence.reservationId &&
                counter == evidence.counter &&
                characteristic == evidence.characteristic &&
                purpose == evidence.purpose &&
                payloadHash == evidence.payloadHash &&
                priorWrite == evidence.priorWrite &&
                candidate == evidence.candidate &&
                evidenceHash == evidence.evidenceHash &&
                unresolvedPredecessor == evidence.unresolvedPredecessor

        companion object {
            fun from(reboot: Int, evidence: WriteEvidence): AcceptedWriteBinding =
                AcceptedWriteBinding(
                    reboot = reboot,
                    operationId = evidence.operationId,
                    reservationId = evidence.reservationId,
                    counter = evidence.counter,
                    characteristic = evidence.characteristic,
                    purpose = evidence.purpose,
                    payloadHash = evidence.payloadHash,
                    priorWrite = evidence.priorWrite,
                    candidate = evidence.candidate,
                    evidenceHash = evidence.evidenceHash,
                    unresolvedPredecessor = evidence.unresolvedPredecessor,
                )
        }
    }
    data class UnresolvedWriteBinding(
        val reboot: Int,
        val reservationId: String,
        val phase: Phase,
        val operationId: String,
        val counter: Long,
        val characteristic: String,
        val purpose: String,
        val payloadHash: String,
        val priorWrite: Long,
        val candidate: WriteCandidate,
        val evidenceHash: String,
    ) {
        fun matches(reservation: Reservation, evidence: WriteEvidence): Boolean =
            reservationId == reservation.id &&
                phase == reservation.phase &&
                operationId == reservation.operationId &&
                counter == reservation.counter &&
                characteristic == reservation.characteristic &&
                purpose == reservation.purpose &&
                payloadHash == reservation.payloadHash &&
                priorWrite == reservation.priorWrite &&
                candidate == reservation.candidate &&
                evidence.resolution == null &&
                evidence.operationId == operationId &&
                evidence.reservationId == reservationId &&
                evidence.counter == counter &&
                evidence.characteristic == characteristic &&
                evidence.purpose == purpose &&
                evidence.payloadHash == payloadHash &&
                evidence.priorWrite == priorWrite &&
                evidence.candidate == candidate &&
                evidence.evidenceHash == evidenceHash &&
                evidence.historyBinding == null &&
                evidence.acceptedPredecessor == null &&
                evidence.unresolvedPredecessor == null

        fun reservation(): Reservation =
            Reservation(
                id = reservationId,
                counter = counter,
                phase = phase,
                operationId = operationId,
                characteristic = characteristic,
                purpose = purpose,
                payloadHash = payloadHash,
                priorWrite = priorWrite,
                candidate = candidate,
            )

        companion object {
            fun from(reboot: Int, reservation: Reservation, evidence: WriteEvidence): UnresolvedWriteBinding =
                UnresolvedWriteBinding(
                    reboot = reboot,
                    reservationId = reservation.id,
                    phase = reservation.phase,
                    operationId = checkNotNull(reservation.operationId),
                    counter = reservation.counter,
                    characteristic = checkNotNull(reservation.characteristic),
                    purpose = checkNotNull(reservation.purpose),
                    payloadHash = checkNotNull(reservation.payloadHash),
                    priorWrite = checkNotNull(reservation.priorWrite),
                    candidate = reservation.candidate,
                    evidenceHash = evidence.evidenceHash,
                )
        }
    }
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
        /** Exact completed pre-Step-08 record after a current reservation supersedes its live slot. */
        val retiredLegacyBenchAlarmCursorRecovery: Reservation? = null,
        val serial: String = "",
        val keyHex: String? = null,
        val createdAt: Long? = null,
        val importedAt: Long? = null,
        val source: Map<String, String> = emptyMap(),
        val verifiedAt: Long? = null,
        val verifiedSerial: String? = null,
        val writeEvidence: List<WriteEvidence> = emptyList(),
        /** Authenticated pre-reboot selected value; the counter-1 bootstrap must choose a different payload. */
        val benchNewEpochBootstrapReference: BootstrapReference? = null,
        /** Authenticated, family-specific count evidence used to bind alarm/system selector indices. */
        val benchHistoryCounts: List<HistoryCountEvidence> = emptyList(),
        /** Authenticated selected values used to prove alarm/system selector rows change state. */
        val benchHistorySelectorStates: List<HistorySelectorState> = emptyList(),
        /** Write ownership is explicit: ordinary reads cannot turn an unknown mid-epoch floor into a usable floor. */
        val writeBootstrapState: WriteBootstrapState =
            if (write == null) WriteBootstrapState.UNKNOWN_MID_EPOCH else WriteBootstrapState.ESTABLISHED,
        /** Set before the new epoch's sole counter-1 bootstrap candidate can reach platform dispatch. */
        val benchNewEpochBootstrapAttempted: Boolean = false,
        /** Bench-only epoch evidence; never authorizes production writes. */
        val benchStrictNextAccepted: Boolean = false,
        /** Set before the epoch's sole +2 candidate can reach platform dispatch. */
        val benchForwardGapAttempted: Boolean = false,
        /** Set before the epoch's sole same-counter duplicate probe can reach platform dispatch. */
        val benchDuplicateCounterAttempted: Boolean = false,
        /** Set before the epoch's sole bounded ambiguity-convergence candidate can reach dispatch. */
        val benchAmbiguityConvergenceAttempted: Boolean = false,
        /** Immutable accepted write that authorized the epoch's duplicate-counter probe. */
        val benchDuplicateCounterPredecessor: AcceptedWriteBinding? = null,
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
    private var state: State? = runCatching { store.load().also(::validate) }.getOrNull()?.copy(lastAttempt = null)
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
                    causes = if (next.write == null) setOf(AvailabilityCause.COUNTER_UNCERTAIN) else emptySet(),
                    since = at
                )
            )
        } ?: current.copy(
            records = current.records.map { if (it.generation == next.generation) next else it },
            availability = Availability(if (next.write == null) setOf(AvailabilityCause.COUNTER_UNCERTAIN) else emptySet(), at)
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
                    benchNewEpochBootstrapReference = candidate.benchNewEpochBootstrapReference,
                    benchHistoryCounts = candidate.benchHistoryCounts,
                    benchHistorySelectorStates = candidate.benchHistorySelectorStates,
                    writeBootstrapState = candidate.writeBootstrapState,
                    benchNewEpochBootstrapAttempted = candidate.benchNewEpochBootstrapAttempted,
                    benchStrictNextAccepted = candidate.benchStrictNextAccepted,
                    benchForwardGapAttempted = candidate.benchForwardGapAttempted,
                    benchDuplicateCounterAttempted = candidate.benchDuplicateCounterAttempted,
                    benchAmbiguityConvergenceAttempted = candidate.benchAmbiguityConvergenceAttempted,
                    benchDuplicateCounterPredecessor = candidate.benchDuplicateCounterPredecessor,
                )
            } else {
                replaced.copy(
                    reboot = candidate.reboot ?: replaced.reboot,
                    read = listOfNotNull(replaced.read, candidate.read).maxOrNull(),
                    write = candidate.write ?: replaced.write,
                    reservation = candidate.reservation ?: replaced.reservation,
                    writeEvidence = mergeWriteEvidence(replaced.writeEvidence, candidate.writeEvidence),
                    benchNewEpochBootstrapReference =
                        candidate.benchNewEpochBootstrapReference ?: replaced.benchNewEpochBootstrapReference,
                    benchHistoryCounts = candidate.benchHistoryCounts,
                    benchHistorySelectorStates = candidate.benchHistorySelectorStates,
                    writeBootstrapState = candidate.writeBootstrapState,
                    benchNewEpochBootstrapAttempted =
                        replaced.benchNewEpochBootstrapAttempted || candidate.benchNewEpochBootstrapAttempted,
                    benchStrictNextAccepted = replaced.benchStrictNextAccepted || candidate.benchStrictNextAccepted,
                    benchForwardGapAttempted = replaced.benchForwardGapAttempted || candidate.benchForwardGapAttempted,
                    benchDuplicateCounterAttempted =
                        replaced.benchDuplicateCounterAttempted || candidate.benchDuplicateCounterAttempted,
                    benchAmbiguityConvergenceAttempted =
                        replaced.benchAmbiguityConvergenceAttempted || candidate.benchAmbiguityConvergenceAttempted,
                    benchDuplicateCounterPredecessor =
                        candidate.benchDuplicateCounterPredecessor ?: replaced.benchDuplicateCounterPredecessor,
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

    /**
     * Provisioning seam, not an automatic import path. Caller must independently validate the floor.
     * Existing keys cannot be reset/reassigned. Write recovery awaits measured target semantics.
     */
    @Synchronized
    fun provisionReadBaseline(pump: String, sharedKey: ByteArray, reboot: Int, read: Long) {
        require(pump.isNotBlank() && sharedKey.size == SessionCrypto.KEY_SIZE && reboot >= 0 && read >= 0)
        val current = state ?: throw SecurityException("Session storage unavailable")
        val id = fingerprint(sharedKey)
        val previous = current.records.singleOrNull { it.keyId == id }
        if (previous != null) {
            check(previous.pump == pump) { "Key belongs to another pump" }
            check(previous.reboot == reboot && read >= checkNotNull(previous.read)) { "Cannot roll back or reset an imported key" }
            persist(
                current.copy(
                    records =
                        current.records.map {
                            if (it == previous) {
                                it.copy(
                                    read = read,
                                    benchHistoryCounts = if (read == previous.read) it.benchHistoryCounts else emptyList(),
                                    benchHistorySelectorStates = if (read == previous.read) it.benchHistorySelectorStates else emptyList(),
                                )
                            } else {
                                it
                            }
                        },
                    activeGeneration = previous.generation,
                ),
            )
        } else {
            val installed = Record(pump, id, UUID.randomUUID().toString(), reboot, read, null)
            persist(current.copy(records = current.records + installed, activeGeneration = installed.generation))
        }
        quiesce()
    }

    /**
     * Dedicated bench import of an independently measured write floor. This cannot alter an existing
     * write floor or bypass unresolved accounting; normal provisioning never calls it.
     */
    @Synchronized
    internal fun provisionBenchWriteBaseline(pump: String, sharedKey: ByteArray, reboot: Int, write: Long) {
        require(pump.isNotBlank() && sharedKey.size == SessionCrypto.KEY_SIZE && reboot >= 0 && write >= 0)
        val current = state ?: throw SecurityException("Session storage unavailable")
        val id = fingerprint(sharedKey)
        val previous = current.records.singleOrNull { it.keyId == id }
            ?: throw SecurityException("Read baseline must be established before write baseline")
        check(previous.pump == pump && previous.reboot == reboot) { "Write baseline belongs to another pump epoch" }
        check(previous.reservation == null) { "Cannot seed over unresolved pump accounting" }
        check(previous.write == null || previous.write == write) { "Cannot replace an established write floor" }
        persist(
            current.copy(
                records =
                    current.records.map {
                        if (it == previous) {
                            it.copy(
                                write = write,
                                writeBootstrapState = WriteBootstrapState.ESTABLISHED,
                            )
                        } else {
                            it
                        }
                    },
            ),
        )
        quiesce()
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
                            it.historyBinding == unresolved.historyBinding &&
                            it.acceptedPredecessor == unresolved.acceptedPredecessor &&
                            it.unresolvedPredecessor == unresolved.unresolvedPredecessor &&
                            it.resolution == null
                    },
                ) { "Cannot transition with an unreviewed outstanding write record" }
            }
            val next =
                retireLegacyReservation(old).copy(
                    reboot = message.reboot,
                    read = message.counter,
                    write = null,
                    reservation = null,
                    benchNewEpochBootstrapReference =
                        old.benchNewEpochBootstrapReference?.takeIf { it.reboot == old.reboot },
                    benchHistoryCounts = emptyList(),
                    benchHistorySelectorStates = emptyList(),
                    writeBootstrapState = WriteBootstrapState.OBSERVED_NEW_EPOCH,
                    benchNewEpochBootstrapAttempted = false,
                    benchStrictNextAccepted = false,
                    benchForwardGapAttempted = false,
                    benchDuplicateCounterAttempted = false,
                    benchAmbiguityConvergenceAttempted = false,
                    benchDuplicateCounterPredecessor = null,
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

    /** No production caller can establish write certainty in the status-only contract. */
    @Synchronized
    fun reserve(origin: Token, id: String, intent: WriteIntent? = null): Reservation {
        return reserveCandidate(origin, id, intent, forwardGap = 0, candidate = WriteCandidate.STANDARD)
    }

    /** Dedicated Step 07 seam: zero means strict-next; one means the single bounded +2 candidate. */
    @Synchronized
    internal fun reserveBenchCandidate(
        origin: Token,
        id: String,
        intent: WriteIntent,
        forwardGap: Int,
        historyFamily: HistoryFamily? = null,
        historyIndex: Int? = null,
        historyCountCharacteristic: String? = null,
    ): Reservation {
        require(forwardGap in 0..1) { "forward gap must be exactly 0 or 1" }
        val old = owned(origin)
        val requiredFamily =
            when (intent.characteristic.lowercase()) {
                expectedHistoryIndexCharacteristic(HistoryFamily.ALARM) -> HistoryFamily.ALARM
                expectedHistoryIndexCharacteristic(HistoryFamily.SYSTEM) -> HistoryFamily.SYSTEM
                else -> null
            }
        if (requiredFamily != null) check(historyFamily == requiredFamily) { "Missing history selector binding" }
        else check(historyFamily == null) { "Unexpected history selector binding" }
        val historyBinding = historyFamily?.let { family ->
            val index = checkNotNull(historyIndex) { "History selector index is missing" }
            val countCharacteristic = checkNotNull(historyCountCharacteristic) { "History count characteristic is missing" }.lowercase()
            require(index >= 0) { "history selector index must be non-negative" }
            require(intent.characteristic.lowercase() == expectedHistoryIndexCharacteristic(family)) {
                "History selector family does not match write destination"
            }
            require(intent.purpose == "HISTORY_SELECTOR") { "History selector purpose does not match" }
            val reboot = checkNotNull(old.reboot) { "Authenticated epoch is unavailable" }
            val evidence = old.benchHistoryCounts.singleOrNull { it.family == family && it.reboot == reboot }
                ?: throw SecurityException("Authenticated ${family.name.lowercase()} count is unavailable for this epoch")
            check(evidence.characteristic == countCharacteristic) {
                "Authenticated ${family.name.lowercase()} count characteristic does not match"
            }
            check(evidence.count > 0 && index == evidence.count - 1) {
                "${family.name.lowercase()} selector must equal authenticated count - 1"
            }
            val selectedBefore = old.benchHistorySelectorStates.singleOrNull { it.family == family && it.reboot == reboot }
                ?: throw SecurityException("Authenticated ${family.name.lowercase()} selector state is unavailable for this epoch")
            check(selectedBefore.characteristic == expectedHistoryValueCharacteristic(family)) {
                "Authenticated ${family.name.lowercase()} selector state characteristic does not match"
            }
            check(selectedBefore.index != index) { "${family.name.lowercase()} selector already equals requested index" }
            HistoryWriteBinding(evidence, selectedBefore, index)
        }
        if (forwardGap == 1) {
            check(old.benchStrictNextAccepted) { "A reconciled accepted strict-next selector is required before the gap candidate" }
            check(!old.benchForwardGapAttempted) { "The epoch's single forward-gap candidate was already attempted" }
        }
        val candidate =
            if (forwardGap == 0) WriteCandidate.BENCH_STRICT_NEXT_SELECTOR else WriteCandidate.BENCH_FORWARD_GAP_SELECTOR
        return reserveCandidate(
            origin,
            id,
            intent,
            forwardGap,
            candidate,
            markForwardGapAttempted = forwardGap == 1,
            historyBinding = historyBinding,
        )
    }

    /**
     * Dedicated fail-closed bootstrap seam. Only an authenticated `old + 1` reboot observation can
     * expose this epoch's single counter-1 candidate; an ordinary key import remains unknown.
     */
    @Synchronized
    internal fun reserveBenchNewEpochBootstrapCandidate(origin: Token, id: String, intent: WriteIntent): Reservation {
        val old = owned(origin)
        check(intent.characteristic.lowercase() == EVENT_INDEX_CHARACTERISTIC && intent.purpose == "HISTORY_SELECTOR") {
            "New-epoch bootstrap supports only the event selector"
        }
        check(old.writeBootstrapState == WriteBootstrapState.OBSERVED_NEW_EPOCH) {
            "New-epoch bootstrap requires an authenticated next reboot"
        }
        check(!old.benchNewEpochBootstrapAttempted) { "The epoch's bootstrap candidate was already attempted" }
        check(old.write == null && old.reservation == null) { "New-epoch bootstrap state is not clean" }
        val reference = checkNotNull(old.benchNewEpochBootstrapReference) { "Pre-reboot selector reference is missing" }
        check(reference.reboot < Int.MAX_VALUE && reference.reboot + 1 == old.reboot) {
            "Pre-reboot selector reference belongs to another epoch"
        }
        check(reference.characteristic == intent.characteristic) { "Bootstrap selector differs from the reference family" }
        check(reference.payloadHash != intent.payloadHash) { "Bootstrap selector must differ from the pre-reboot value" }
        return reserveCandidate(
            origin,
            id,
            intent,
            forwardGap = 0,
            candidate = WriteCandidate.BENCH_NEW_EPOCH_BOOTSTRAP_SELECTOR,
            bootstrapPriorWrite = 0,
            markNewEpochBootstrapAttempted = true,
        )
    }

    /**
     * Dedicated one-shot bench probe for duplicate-counter behavior. It reuses exactly the counter of
     * a fully verified accepted strict-next event selector and requires a different event payload.
     */
    @Synchronized
    internal fun reserveBenchDuplicateCounterCandidate(origin: Token, id: String, intent: WriteIntent): Reservation {
        val old = owned(origin)
        check(transaction == id) { "Stale transaction" }
        check(old.writeBootstrapState == WriteBootstrapState.ESTABLISHED) { "Write bootstrap is not established" }
        check(!old.benchDuplicateCounterAttempted) { "The epoch's duplicate-counter probe was already attempted" }
        check(intent.characteristic.lowercase() == EVENT_INDEX_CHARACTERISTIC && intent.purpose == "HISTORY_SELECTOR") {
            "Duplicate-counter probe supports only the event selector"
        }
        val predecessor = checkNotNull(old.reservation) { "A verified predecessor reservation is required" }
        check(predecessor.phase == Phase.VERIFIED) { "The predecessor write is not verified" }
        check(
            predecessor.candidate in
                setOf(
                    WriteCandidate.BENCH_STRICT_NEXT_SELECTOR,
                    WriteCandidate.BENCH_AMBIGUITY_CONVERGENCE_SELECTOR,
                ),
        ) {
            "Duplicate-counter probe requires an accepted event predecessor"
        }
        check(predecessor.characteristic?.lowercase() == EVENT_INDEX_CHARACTERISTIC && predecessor.purpose == "HISTORY_SELECTOR") {
            "Duplicate-counter predecessor must be an event selector"
        }
        check(predecessor.operationId != intent.operationId) { "Duplicate-counter probe requires a new operation ID" }
        check(predecessor.payloadHash != intent.payloadHash) { "Duplicate-counter probe must use a different payload" }
        val accepted =
            old.writeEvidence.singleOrNull {
                it.reservationId == predecessor.id &&
                    it.operationId == predecessor.operationId &&
                    it.counter == predecessor.counter &&
                    it.characteristic == predecessor.characteristic &&
                    it.purpose == predecessor.purpose &&
                    it.payloadHash == predecessor.payloadHash &&
                    it.priorWrite == predecessor.priorWrite &&
                    it.candidate == predecessor.candidate &&
                    it.resolution == WriteResolution.ACCEPTED
            } ?: throw SecurityException("Accepted predecessor evidence is missing or ambiguous")
        val binding = AcceptedWriteBinding.from(checkNotNull(old.reboot), accepted)
        val reserved =
            Reservation(
                id = id,
                counter = predecessor.counter,
                phase = Phase.RESERVED,
                operationId = intent.operationId,
                characteristic = intent.characteristic,
                purpose = intent.purpose,
                payloadHash = intent.payloadHash,
                priorWrite = predecessor.counter,
                candidate = WriteCandidate.BENCH_DUPLICATE_COUNTER_SELECTOR,
                acceptedPredecessor = binding,
            )
        update(
            old.copy(
                reservation = reserved,
                benchDuplicateCounterAttempted = true,
                benchDuplicateCounterPredecessor = binding,
            ),
        )
        return reserved
    }

    /**
     * From one hash-bound unresolved strict-next selector at N, reserve exactly N+1. The pump's
     * actual floor is then either N-1 or N, making this candidate respectively the measured +2 or
     * strict-next. Event convergence changes the selected row for independent semantic evidence;
     * settings convergence repeats the same read-only setting ID so its value can be read on-link.
     */
    @Synchronized
    internal fun reserveBenchAmbiguityConvergenceCandidate(origin: Token, id: String, intent: WriteIntent): Reservation {
        val old = owned(origin)
        check(transaction == id) { "Stale transaction" }
        check(old.writeBootstrapState == WriteBootstrapState.ESTABLISHED) { "Write bootstrap is not established" }
        check(!old.benchAmbiguityConvergenceAttempted) { "The epoch's ambiguity-convergence candidate was already attempted" }
        check(isAmbiguityConvergenceSelector(intent.characteristic, intent.purpose)) {
            "Ambiguity convergence supports only event and settings selectors"
        }
        val unresolved = checkNotNull(old.reservation) { "An unresolved predecessor reservation is required" }
        check(unresolved.phase in setOf(Phase.POSSIBLY_SENT, Phase.ACKED)) { "The predecessor is not unresolved" }
        check(unresolved.candidate == WriteCandidate.BENCH_STRICT_NEXT_SELECTOR) {
            "Ambiguity convergence requires an unresolved strict-next predecessor"
        }
        check(
            unresolved.characteristic.equals(intent.characteristic, ignoreCase = true) &&
                unresolved.purpose == intent.purpose &&
                isAmbiguityConvergenceSelector(checkNotNull(unresolved.characteristic), checkNotNull(unresolved.purpose)),
        ) {
            "Ambiguity-convergence predecessor must be the same selector family"
        }
        check(unresolved.operationId != intent.operationId) { "Ambiguity convergence requires a new operation ID" }
        if (intent.purpose == "HISTORY_SELECTOR") {
            check(unresolved.payloadHash != intent.payloadHash) { "Event ambiguity convergence requires a different payload" }
        } else {
            check(unresolved.payloadHash == intent.payloadHash) { "Settings ambiguity convergence must repeat the same setting ID" }
        }
        val evidence = unresolvedEvidence(old, unresolved)
            ?: throw SecurityException("Unresolved predecessor evidence is missing or ambiguous")
        val binding = UnresolvedWriteBinding.from(checkNotNull(old.reboot), unresolved, evidence)
        check(unresolved.counter < Long.MAX_VALUE) { "Write counter exhausted" }
        val reserved =
            Reservation(
                id = id,
                counter = unresolved.counter + 1,
                phase = Phase.RESERVED,
                operationId = intent.operationId,
                characteristic = intent.characteristic,
                purpose = intent.purpose,
                payloadHash = intent.payloadHash,
                priorWrite = unresolved.counter,
                candidate = WriteCandidate.BENCH_AMBIGUITY_CONVERGENCE_SELECTOR,
                unresolvedPredecessor = binding,
            )
        update(old.copy(write = reserved.counter, reservation = reserved, benchAmbiguityConvergenceAttempted = true))
        return reserved
    }

    @Synchronized
    internal fun benchAmbiguityConvergenceReady(): Boolean {
        val old = record ?: return false
        if (old.writeBootstrapState != WriteBootstrapState.ESTABLISHED || old.benchAmbiguityConvergenceAttempted) return false
        val unresolved = old.reservation ?: return false
        if (unresolved.phase !in setOf(Phase.POSSIBLY_SENT, Phase.ACKED)) return false
        if (unresolved.candidate != WriteCandidate.BENCH_STRICT_NEXT_SELECTOR) return false
        if (!isAmbiguityConvergenceSelector(unresolved.characteristic, unresolved.purpose)) return false
        return unresolvedEvidence(old, unresolved) != null
    }

    private fun unresolvedEvidence(record: Record, reservation: Reservation): WriteEvidence? =
        record.writeEvidence.singleOrNull {
            it.reservationId == reservation.id &&
                it.operationId == reservation.operationId &&
                it.counter == reservation.counter &&
                it.characteristic == reservation.characteristic &&
                it.purpose == reservation.purpose &&
                it.payloadHash == reservation.payloadHash &&
                it.priorWrite == reservation.priorWrite &&
                it.candidate == reservation.candidate &&
                it.resolution == null &&
                it.historyBinding == reservation.historyBinding &&
                it.acceptedPredecessor == reservation.acceptedPredecessor &&
                it.unresolvedPredecessor == reservation.unresolvedPredecessor
        }

    /** Record one authenticated, CRC-validated pre-reboot selector value without changing write state. */
    @Synchronized
    internal fun recordBenchNewEpochBootstrapReference(
        origin: Token,
        characteristic: String,
        payloadHash: String,
    ) {
        val old = owned(origin)
        check(transaction == null) { "Another session transaction is active" }
        val reboot = checkNotNull(old.reboot) { "Authenticated epoch is unavailable" }
        val read = checkNotNull(old.read) { "Authenticated read floor is unavailable" }
        require(characteristic.isNotBlank() && payloadHash.matches(Regex("[0-9a-f]{64}")))
        val reference = BootstrapReference(reboot, read, characteristic, payloadHash)
        old.benchNewEpochBootstrapReference?.let {
            check(it.reboot <= reboot) { "Bootstrap reference belongs to a future epoch" }
        }
        update(old.copy(benchNewEpochBootstrapReference = reference))
    }

    /** Persist authenticated family counts only for the current epoch; zero removes and blocks that family. */
    @Synchronized
    internal fun recordBenchHistoryCounts(
        origin: Token,
        counts: List<HistoryCountEvidence>,
    ) {
        val old = owned(origin)
        check(transaction == null) { "Another session transaction is active" }
        check(old.reservation == null || old.reservation.phase == Phase.VERIFIED) { "An unresolved write blocks count replacement" }
        val reboot = checkNotNull(old.reboot) { "Authenticated epoch is unavailable" }
        val read = checkNotNull(old.read) { "Authenticated read floor is unavailable" }
        require(counts.map { it.family }.distinct().size == counts.size) { "Duplicate history count family" }
        counts.forEach {
            require(it.reboot == reboot && it.read == read && it.read > 0 && it.count >= 0)
            require(it.characteristic == expectedHistoryCountCharacteristic(it.family)) {
                "History count family does not match characteristic"
            }
            require(it.payloadHash.matches(Regex("[0-9a-f]{64}")))
        }
        val replacedFamilies = counts.mapTo(mutableSetOf()) { it.family }
        update(
            old.copy(
                benchHistoryCounts =
                    (old.benchHistoryCounts.filterNot { it.reboot != reboot || it.family in replacedFamilies } + counts.filter { it.count > 0 })
                        .sortedBy { it.family.name },
                // A fresh count observation invalidates the family's previous pre-row value; the
                // operator must re-read the current selection before any later alarm/system row.
                benchHistorySelectorStates =
                    old.benchHistorySelectorStates.filterNot { it.reboot != reboot || it.family in replacedFamilies },
            ),
        )
    }

    /** Persist one authenticated current selector value for changed-value history evidence. */
    @Synchronized
    internal fun recordBenchHistorySelectorState(
        origin: Token,
        selectorState: HistorySelectorState,
    ) {
        val old = owned(origin)
        check(transaction == null) { "Another session transaction is active" }
        check(old.reservation == null || old.reservation.phase == Phase.VERIFIED) { "An unresolved write blocks selector-state replacement" }
        val reboot = checkNotNull(old.reboot) { "Authenticated epoch is unavailable" }
        val read = checkNotNull(old.read) { "Authenticated read floor is unavailable" }
        require(selectorState.reboot == reboot && selectorState.read == read && selectorState.read > 0 && selectorState.index >= 0)
        require(selectorState.characteristic == expectedHistoryValueCharacteristic(selectorState.family)) {
            "History selector state family does not match characteristic"
        }
        require(selectorState.payloadHash.matches(Regex("[0-9a-f]{64}")))
        update(
            old.copy(
                benchHistorySelectorStates =
                    (old.benchHistorySelectorStates.filterNot { it.reboot != reboot || it.family == selectorState.family } + selectorState)
                        .sortedBy { it.family.name },
            ),
        )
    }

    private fun reserveCandidate(
        origin: Token,
        id: String,
        intent: WriteIntent?,
        forwardGap: Int,
        candidate: WriteCandidate,
        markForwardGapAttempted: Boolean = false,
        bootstrapPriorWrite: Long? = null,
        markNewEpochBootstrapAttempted: Boolean = false,
        historyBinding: HistoryWriteBinding? = null,
    ): Reservation {
        val old = retireLegacyReservation(owned(origin))
        check(transaction == id) { "Stale transaction" }
        check(old.reservation == null || old.reservation.phase == Phase.VERIFIED) { "Unresolved write" }
        val last = bootstrapPriorWrite ?: old.write ?: throw SecurityException("Write counter uncertain; bench validation required")
        val increment = 1L + forwardGap
        check(last <= Long.MAX_VALUE - increment) { "Write counter exhausted" }
        intent?.let {
            require(it.operationId.isNotBlank() && it.characteristic.isNotBlank() && it.purpose.isNotBlank())
            require(it.payloadHash.matches(Regex("[0-9a-f]{64}")))
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
            historyBinding = historyBinding,
        )
        update(
            old.copy(
                write = reserved.counter,
                reservation = reserved,
                // A history row consumes its authenticated pre-row observation; every later alarm/system
                // row must establish a fresh current value before it can prove a changed selection.
                benchHistorySelectorStates =
                    historyBinding?.let { binding ->
                        old.benchHistorySelectorStates.filterNot {
                            it.family == binding.count.family && it.reboot == binding.count.reboot
                        }
                    } ?: old.benchHistorySelectorStates,
                benchNewEpochBootstrapAttempted =
                    old.benchNewEpochBootstrapAttempted || markNewEpochBootstrapAttempted,
                benchForwardGapAttempted = old.benchForwardGapAttempted || markForwardGapAttempted
            )
        )
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
        val priorWrite = priorWrite(reserved)
        check(old.write == reserved.counter && priorWrite >= 0 && validCounterDistance(reserved, priorWrite)) {
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
        val priorWrite = priorWrite(reserved)
        check(old.write == reserved.counter && priorWrite >= 0 && validCounterDistance(reserved, priorWrite)) {
            "Invalid write reservation"
        }
        require(evidenceHash.matches(Regex("[0-9a-f]{64}")) && detail.isNotBlank() && detail.length <= 4096)
        val evidence = writeEvidence(reserved, WriteResolution.REJECTED_COUNTER_NOT_CONSUMED, evidenceHash, detail)
        update(restoreAfterNotConsumed(old, reserved, evidence))
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
        require(evidenceHash.matches(Regex("[0-9a-f]{64}")) && detail.isNotBlank() && detail.length <= 4096)
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
        require(evidenceHash.matches(Regex("[0-9a-f]{64}")) && detail.isNotBlank() && detail.length <= 4096)
        val evidence = writeEvidence(reserved, resolution, evidenceHash, detail)
        val next = when (resolution) {
            WriteResolution.ACCEPTED ->
                old.copy(
                    reservation = reserved.copy(phase = Phase.VERIFIED),
                    writeEvidence = old.writeEvidence + evidence,
                    writeBootstrapState =
                        if (reserved.candidate == WriteCandidate.BENCH_NEW_EPOCH_BOOTSTRAP_SELECTOR) {
                            WriteBootstrapState.ESTABLISHED
                        } else {
                            old.writeBootstrapState
                        },
                    benchStrictNextAccepted =
                        old.benchStrictNextAccepted || reserved.candidate == WriteCandidate.BENCH_STRICT_NEXT_SELECTOR
                )
            WriteResolution.REJECTED_COUNTER_CONSUMED ->
                old.copy(
                    reservation = reserved.copy(phase = Phase.VERIFIED),
                    writeEvidence = old.writeEvidence + evidence,
                    writeBootstrapState =
                        if (reserved.candidate == WriteCandidate.BENCH_NEW_EPOCH_BOOTSTRAP_SELECTOR) {
                            WriteBootstrapState.ESTABLISHED
                        } else {
                            old.writeBootstrapState
                        },
                )
            WriteResolution.REJECTED_COUNTER_NOT_CONSUMED ->
                restoreAfterNotConsumed(old, reserved, evidence)
        }
        update(next)
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
            priorWrite = priorWrite(reservation),
            candidate = reservation.candidate,
            resolution = resolution,
            evidenceHash = evidenceHash,
            detail = detail,
            historyBinding = reservation.historyBinding,
            acceptedPredecessor = reservation.acceptedPredecessor,
            unresolvedPredecessor = reservation.unresolvedPredecessor,
        )

    private fun restoreAfterNotConsumed(old: Record, reservation: Reservation, evidence: WriteEvidence?): Record {
        val evidenceList = evidence?.let { old.writeEvidence + it } ?: old.writeEvidence
        return when (reservation.candidate) {
            WriteCandidate.BENCH_NEW_EPOCH_BOOTSTRAP_SELECTOR ->
                old.copy(write = null, reservation = null, writeEvidence = evidenceList)
            WriteCandidate.BENCH_AMBIGUITY_CONVERGENCE_SELECTOR -> {
                val predecessor = checkNotNull(reservation.unresolvedPredecessor).reservation()
                old.copy(write = predecessor.counter, reservation = predecessor, writeEvidence = evidenceList)
            }
            else -> old.copy(write = priorWrite(reservation), reservation = null, writeEvidence = evidenceList)
        }
    }

    private fun priorWrite(reservation: Reservation): Long = reservation.priorWrite ?: reservation.counter - 1

    /**
     * The historical alarm-cursor record occupied the single live reservation slot. Once current
     * code needs that slot, retain the exact completed tuple as audit state; never recreate it as a
     * reservable or dispatchable candidate.
     */
    private fun retireLegacyReservation(record: Record): Record {
        val legacy = record.reservation?.takeIf {
            it.candidate == WriteCandidate.LEGACY_BENCH_ALARM_CURSOR_RECOVERY_SELECTOR
        } ?: return record
        check(record.retiredLegacyBenchAlarmCursorRecovery == null) { "Legacy alarm recovery is already retired" }
        return record.copy(reservation = null, retiredLegacyBenchAlarmCursorRecovery = legacy)
    }

    private fun validCounterDistance(reservation: Reservation, priorWrite: Long): Boolean =
        when (reservation.candidate) {
            WriteCandidate.BENCH_DUPLICATE_COUNTER_SELECTOR -> priorWrite == reservation.counter
            else -> priorWrite < reservation.counter
        }

    private fun owned(origin: Token): Record {
        check(token == origin && state != null) { "Stale or unavailable session" }
        return checkNotNull(record)
    }

    private fun update(next: Record) {
        val current = checkNotNull(state)
        persist(current.copy(records = current.records.map { if (it.generation == next.generation) next else it }))
        record = next
    }

    private fun mergeWriteEvidence(
        retained: List<WriteEvidence>,
        candidate: List<WriteEvidence>,
    ): List<WriteEvidence> =
        (retained + candidate).distinctBy { it.reservationId to it.evidenceHash }

    private fun persist(next: State) {
        try {
            validate(next)
            store.commit(next)
            state = next
        } catch (e: Exception) {
            state = null
            quiesce()
            throw SecurityException("Session journal commit failed", e)
        }
    }

    companion object {
        private fun expectedHistoryIndexCharacteristic(family: HistoryFamily): String =
            when (family) {
                HistoryFamily.ALARM -> "669a0c20-0008-969e-e211-fcbec93b7bc5"
                HistoryFamily.SYSTEM -> "381ddce9-e934-b4ae-e345-eb87283db426"
            }

        private fun historyFamilyFor(characteristic: String?): HistoryFamily? =
            characteristic?.lowercase()?.let {
                when (it) {
                    expectedHistoryIndexCharacteristic(HistoryFamily.ALARM) -> HistoryFamily.ALARM
                    expectedHistoryIndexCharacteristic(HistoryFamily.SYSTEM) -> HistoryFamily.SYSTEM
                    else -> null
                }
            }

        private const val EVENT_INDEX_CHARACTERISTIC = "669a0c20-0008-969e-e211-fcbecc3b7bc5"
        private const val SETTING_ID_CHARACTERISTIC = "669a0c20-0008-969e-e211-fcbeb3147bc5"

        private fun isAmbiguityConvergenceSelector(characteristic: String?, purpose: String?): Boolean =
            when (purpose) {
                "HISTORY_SELECTOR" -> characteristic?.lowercase() == EVENT_INDEX_CHARACTERISTIC
                "SETTINGS_SELECTOR" -> characteristic?.lowercase() == SETTING_ID_CHARACTERISTIC
                else -> false
            }

        private fun expectedHistoryValueCharacteristic(family: HistoryFamily): String =
            when (family) {
                HistoryFamily.ALARM -> "669a0c20-0008-969e-e211-fcbeca3b7bc5"
                HistoryFamily.SYSTEM -> "ae3022af-2ec8-bf88-e64c-da68c9a3891a"
            }

        private fun expectedHistoryCountCharacteristic(family: HistoryFamily): String =
            when (family) {
                HistoryFamily.ALARM -> "669a0c20-0008-969e-e211-fcbec83b7bc5"
                HistoryFamily.SYSTEM -> "86a5a431-d442-2c8d-304b-19ee355571fc"
            }

        fun fingerprint(key: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }

        fun validate(state: State) {
            val duplicateKeys = state.records.groupBy { it.keyId }.filterValues { it.size > 1 }
            require(duplicateKeys.all { (_, records) ->
                records.size == 2 && state.candidateGeneration in records.map(Record::generation) &&
                    state.candidateReplacesGeneration in records.map(Record::generation)
            })
            require(state.records.map { it.generation }.distinct().size == state.records.size)
            state.records.forEach { r ->
                require(r.pump.isNotBlank() && r.generation.isNotBlank() && r.keyId.matches(Regex("[0-9a-f]{64}")))
                require((r.reboot == null) == (r.read == null))
                require((r.reboot == null || r.reboot >= 0) && (r.read == null || r.read >= 0) && (r.write == null || r.write >= 0))
                require(r.keyHex == null || r.keyHex.matches(Regex("[0-9a-f]{64}")) && fingerprint(r.keyHex.unhex()) == r.keyId)
                require(r.serial.isNotBlank() || r.keyHex == null)
                require(r.verifiedSerial == null || r.verifiedSerial == r.serial && r.verifiedAt != null)
                r.benchNewEpochBootstrapReference?.let {
                    require(it.reboot >= 0 && it.read > 0 && it.characteristic.isNotBlank())
                    require(it.payloadHash.matches(Regex("[0-9a-f]{64}")))
                    require(r.reboot != null && (it.reboot == r.reboot || it.reboot < Int.MAX_VALUE && it.reboot + 1 == r.reboot))
                }
                require(r.benchHistoryCounts.map { it.family }.distinct().size == r.benchHistoryCounts.size)
                r.benchHistoryCounts.forEach {
                    require(it.reboot >= 0 && it.read > 0 && it.count > 0)
                    require(it.characteristic == expectedHistoryCountCharacteristic(it.family))
                    require(it.payloadHash.matches(Regex("[0-9a-f]{64}")))
                    require(r.reboot != null && it.reboot == r.reboot)
                    require(r.read != null && it.read > 0 && it.read <= r.read)
                }
                require(r.benchHistorySelectorStates.map { it.family }.distinct().size == r.benchHistorySelectorStates.size)
                r.benchHistorySelectorStates.forEach {
                    require(it.reboot >= 0 && it.read > 0 && it.index >= 0)
                    require(it.characteristic == expectedHistoryValueCharacteristic(it.family))
                    require(it.payloadHash.matches(Regex("[0-9a-f]{64}")))
                    require(r.reboot != null && it.reboot == r.reboot)
                    require(r.read != null && it.read > 0 && it.read <= r.read)
                }
                when (r.writeBootstrapState) {
                    WriteBootstrapState.UNKNOWN_MID_EPOCH -> require(r.write == null && r.reservation == null)
                    WriteBootstrapState.OBSERVED_NEW_EPOCH ->
                        require(
                            r.write == null && r.reservation == null ||
                                r.write == 1L &&
                                r.reservation?.candidate == WriteCandidate.BENCH_NEW_EPOCH_BOOTSTRAP_SELECTOR,
                        )
                    WriteBootstrapState.ESTABLISHED -> require(r.write != null)
                }
                require(!r.benchNewEpochBootstrapAttempted || r.writeBootstrapState != WriteBootstrapState.UNKNOWN_MID_EPOCH)
                require(!r.benchNewEpochBootstrapAttempted || r.benchNewEpochBootstrapReference != null)
                require(!r.benchForwardGapAttempted || r.benchStrictNextAccepted)
                require(
                    !r.benchDuplicateCounterAttempted ||
                        r.benchStrictNextAccepted ||
                        r.benchDuplicateCounterPredecessor != null,
                )
                require(!r.benchDuplicateCounterAttempted || r.writeBootstrapState == WriteBootstrapState.ESTABLISHED)
                require(!r.benchAmbiguityConvergenceAttempted || r.writeBootstrapState == WriteBootstrapState.ESTABLISHED)
                require(r.benchDuplicateCounterPredecessor == null || r.benchDuplicateCounterAttempted)
                require(
                    r.benchAmbiguityConvergenceAttempted ||
                        r.writeEvidence.none {
                            it.candidate == WriteCandidate.BENCH_AMBIGUITY_CONVERGENCE_SELECTOR &&
                                it.unresolvedPredecessor?.reboot == r.reboot
                        },
                )
                require(
                    r.benchDuplicateCounterAttempted ||
                        r.writeEvidence.none {
                            it.candidate == WriteCandidate.BENCH_DUPLICATE_COUNTER_SELECTOR &&
                                it.acceptedPredecessor?.reboot == r.reboot
                        },
                )
                r.benchDuplicateCounterPredecessor?.let {
                    requireAcceptedPredecessor(r, it, AcceptedPredecessorEpoch.CURRENT)
                }
                require(
                    r.reboot != null ||
                        !r.benchStrictNextAccepted &&
                        !r.benchForwardGapAttempted &&
                        !r.benchDuplicateCounterAttempted &&
                        !r.benchAmbiguityConvergenceAttempted &&
                        r.benchDuplicateCounterPredecessor == null,
                )
                r.reservation?.let {
                    require(it.id.isNotBlank() && it.counter > 0 && it.counter == r.write)
                    val priorWrite = checkNotNull(it.priorWrite)
                    require(priorWrite >= 0)
                    when (it.candidate) {
                        WriteCandidate.STANDARD -> require(it.counter - priorWrite == 1L)
                        WriteCandidate.BENCH_STRICT_NEXT_SELECTOR -> {
                            require(it.counter - priorWrite == 1L)
                            require(it.operationId != null)
                        }
                        WriteCandidate.BENCH_FORWARD_GAP_SELECTOR -> {
                            require(it.counter - priorWrite == 2L)
                            require(r.benchStrictNextAccepted && r.benchForwardGapAttempted)
                            require(it.operationId != null)
                        }
                        WriteCandidate.BENCH_NEW_EPOCH_BOOTSTRAP_SELECTOR -> {
                            require(it.counter == 1L && priorWrite == 0L)
                            require(r.benchNewEpochBootstrapAttempted)
                            require(
                                if (it.phase == Phase.VERIFIED) {
                                    r.writeBootstrapState == WriteBootstrapState.ESTABLISHED
                                } else {
                                    r.writeBootstrapState == WriteBootstrapState.OBSERVED_NEW_EPOCH
                                },
                            )
                            require(it.operationId != null)
                        }
                        WriteCandidate.BENCH_AMBIGUITY_CONVERGENCE_SELECTOR -> {
                            require(it.counter - priorWrite == 1L)
                            require(r.benchAmbiguityConvergenceAttempted)
                            require(it.operationId != null)
                            val predecessor = checkNotNull(it.unresolvedPredecessor)
                            require(it.counter == predecessor.counter + 1 && priorWrite == predecessor.counter)
                            requireUnresolvedPredecessor(r, predecessor, UnresolvedPredecessorEpoch.CURRENT)
                        }
                        WriteCandidate.BENCH_DUPLICATE_COUNTER_SELECTOR -> {
                            require(it.counter == priorWrite)
                            require(r.benchDuplicateCounterAttempted)
                            require(it.operationId != null)
                            val predecessor = checkNotNull(it.acceptedPredecessor)
                            require(r.benchDuplicateCounterPredecessor == null || r.benchDuplicateCounterPredecessor == predecessor)
                            requireAcceptedPredecessor(r, predecessor, AcceptedPredecessorEpoch.CURRENT)
                        }
                        WriteCandidate.LEGACY_BENCH_ALARM_CURSOR_RECOVERY_SELECTOR -> {
                            // This candidate was emitted by a short-lived bench experiment before
                            // Step 08. Accept only its completed audit record; no current code can
                            // construct or dispatch it.
                            require(it.phase == Phase.VERIFIED && it.counter == 33L && priorWrite == 32L)
                            require(it.operationId != null)
                        }
                    }
                    require((it.operationId == null) == (it.characteristic == null) && (it.characteristic == null) == (it.purpose == null) && (it.purpose == null) == (it.payloadHash == null))
                    require(it.operationId == null || it.operationId.isNotBlank() && it.characteristic!!.isNotBlank() && it.purpose!!.isNotBlank() && it.payloadHash!!.matches(Regex("[0-9a-f]{64}")))
                    val family = historyFamilyFor(it.characteristic)
                    if (it.candidate == WriteCandidate.LEGACY_BENCH_ALARM_CURSOR_RECOVERY_SELECTOR) {
                        require(family == HistoryFamily.ALARM && it.historyBinding == null)
                    } else if (family != null) {
                        val binding = checkNotNull(it.historyBinding) { "Alarm/system selector reservation requires a history binding" }
                        require(binding.count.family == family)
                        require(r.reboot != null && binding.count.reboot == r.reboot)
                        require(r.read != null && binding.count.read <= r.read && binding.selectedBefore.read <= r.read)
                        if (it.phase != Phase.VERIFIED) {
                            require(r.benchHistorySelectorStates.none { state -> state.family == family }) {
                                "An unresolved history reservation must consume its pre-row selector state"
                            }
                        }
                        requireHistoryBinding(binding, it.counter)
                    } else {
                        require(it.historyBinding == null)
                    }
                    require((it.candidate == WriteCandidate.BENCH_DUPLICATE_COUNTER_SELECTOR) == (it.acceptedPredecessor != null))
                    require((it.candidate == WriteCandidate.BENCH_AMBIGUITY_CONVERGENCE_SELECTOR) == (it.unresolvedPredecessor != null))
                }
                r.retiredLegacyBenchAlarmCursorRecovery?.let {
                    require(
                        it.candidate == WriteCandidate.LEGACY_BENCH_ALARM_CURSOR_RECOVERY_SELECTOR &&
                            it.phase == Phase.VERIFIED &&
                            it.counter == 33L &&
                            it.priorWrite == 32L &&
                            it.operationId != null &&
                            historyFamilyFor(it.characteristic) == HistoryFamily.ALARM &&
                            it.historyBinding == null &&
                            it.acceptedPredecessor == null &&
                            it.unresolvedPredecessor == null,
                    )
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
                            it.payloadHash.matches(Regex("[0-9a-f]{64}")) &&
                            it.priorWrite >= 0,
                    )
                    when (it.candidate) {
                        WriteCandidate.STANDARD, WriteCandidate.BENCH_STRICT_NEXT_SELECTOR ->
                            require(it.counter - it.priorWrite == 1L)
                        WriteCandidate.BENCH_FORWARD_GAP_SELECTOR ->
                            require(it.counter - it.priorWrite == 2L)
                        WriteCandidate.BENCH_NEW_EPOCH_BOOTSTRAP_SELECTOR ->
                            require(it.counter == 1L && it.priorWrite == 0L)
                        WriteCandidate.BENCH_AMBIGUITY_CONVERGENCE_SELECTOR -> {
                            require(it.counter - it.priorWrite == 1L)
                            val predecessor = checkNotNull(it.unresolvedPredecessor)
                            require(it.counter == predecessor.counter + 1 && it.priorWrite == predecessor.counter)
                            requireUnresolvedPredecessor(r, predecessor, UnresolvedPredecessorEpoch.CURRENT_OR_PAST)
                        }
                        WriteCandidate.BENCH_DUPLICATE_COUNTER_SELECTOR -> {
                            require(it.counter == it.priorWrite)
                            requireAcceptedPredecessor(r, checkNotNull(it.acceptedPredecessor), AcceptedPredecessorEpoch.CURRENT_OR_PAST)
                        }
                        WriteCandidate.LEGACY_BENCH_ALARM_CURSOR_RECOVERY_SELECTOR ->
                            require(it.counter == 33L && it.priorWrite == 32L && it.resolution == WriteResolution.ACCEPTED)
                    }
                    r.reservation?.takeIf { reservation -> reservation.id == it.reservationId }?.let { reservation ->
                        require(
                            reservation.operationId == it.operationId &&
                                reservation.counter == it.counter &&
                                reservation.characteristic == it.characteristic &&
                                reservation.purpose == it.purpose &&
                                reservation.payloadHash == it.payloadHash &&
                                reservation.priorWrite == it.priorWrite &&
                                reservation.candidate == it.candidate &&
                                reservation.historyBinding == it.historyBinding &&
                                reservation.acceptedPredecessor == it.acceptedPredecessor &&
                                reservation.unresolvedPredecessor == it.unresolvedPredecessor,
                        )
                    }
                    require(it.evidenceHash.matches(Regex("[0-9a-f]{64}")) && it.detail.isNotBlank() && it.detail.length <= 4096)
                    val family = historyFamilyFor(it.characteristic)
                    if (it.candidate == WriteCandidate.LEGACY_BENCH_ALARM_CURSOR_RECOVERY_SELECTOR) {
                        require(family == HistoryFamily.ALARM && it.historyBinding == null)
                    } else if (family != null) {
                        val binding = checkNotNull(it.historyBinding) { "Alarm/system evidence requires a history binding" }
                        require(binding.count.family == family)
                        // History bindings are immutable audit evidence and survive exact-next reboot
                        // adoption. Live count/selector authority is cleared above and remains strictly
                        // current-epoch; retained evidence may belong to the current or an older epoch,
                        // but never to a future one.
                        require(r.reboot != null && binding.count.reboot <= r.reboot)
                        if (binding.count.reboot == r.reboot) {
                            require(r.read != null && binding.count.read <= r.read && binding.selectedBefore.read <= r.read)
                        }
                        requireHistoryBinding(binding, it.counter)
                    } else {
                        require(it.historyBinding == null)
                    }
                    require((it.candidate == WriteCandidate.BENCH_DUPLICATE_COUNTER_SELECTOR) == (it.acceptedPredecessor != null))
                    require((it.candidate == WriteCandidate.BENCH_AMBIGUITY_CONVERGENCE_SELECTOR) == (it.unresolvedPredecessor != null))
                }
                val legacyReservation = r.reservation?.takeIf {
                    it.candidate == WriteCandidate.LEGACY_BENCH_ALARM_CURSOR_RECOVERY_SELECTOR
                }
                val retiredLegacyReservation = r.retiredLegacyBenchAlarmCursorRecovery
                val legacyEvidence = r.writeEvidence.filter {
                    it.candidate == WriteCandidate.LEGACY_BENCH_ALARM_CURSOR_RECOVERY_SELECTOR
                }
                // The retired candidate is valid only as the exact completed reservation/evidence
                // pair left by the old bench artifact. It may occupy the legacy live slot or its
                // dedicated audit slot, but never both; neither form can be recovered into work.
                require(legacyReservation == null || retiredLegacyReservation == null)
                val preservedLegacyReservation = legacyReservation ?: retiredLegacyReservation
                require((preservedLegacyReservation == null) == legacyEvidence.isEmpty())
                preservedLegacyReservation?.let { reservation ->
                    require(legacyEvidence.size == 1)
                    require(
                        legacyEvidence.singleOrNull { evidence ->
                            evidence.reservationId == reservation.id &&
                                evidence.operationId == reservation.operationId &&
                                evidence.counter == reservation.counter &&
                                evidence.characteristic == reservation.characteristic &&
                                evidence.purpose == reservation.purpose &&
                                evidence.payloadHash == reservation.payloadHash &&
                                evidence.priorWrite == reservation.priorWrite &&
                                evidence.resolution == WriteResolution.ACCEPTED
                        } != null,
                    )
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

        private fun requireHistoryBinding(binding: HistoryWriteBinding, counter: Long) {
            val family = binding.count.family
            require(binding.count.reboot == binding.selectedBefore.reboot)
            require(binding.count.reboot >= 0 && binding.count.read > 0 && binding.count.count > 0)
            require(binding.count.characteristic == expectedHistoryCountCharacteristic(family))
            require(binding.count.payloadHash.matches(Regex("[0-9a-f]{64}")))
            require(binding.selectedBefore.family == family)
            require(binding.selectedBefore.reboot >= 0 && binding.selectedBefore.read > 0 && binding.selectedBefore.index >= 0)
            require(binding.selectedBefore.characteristic == expectedHistoryValueCharacteristic(family))
            require(binding.selectedBefore.payloadHash.matches(Regex("[0-9a-f]{64}")))
            require(binding.writeIndex >= 0 && binding.writeIndex == binding.count.count - 1)
            require(binding.selectedBefore.index != binding.writeIndex)
            require(counter > 0)
        }

        private enum class AcceptedPredecessorEpoch { CURRENT, CURRENT_OR_PAST }
        private enum class UnresolvedPredecessorEpoch { CURRENT, CURRENT_OR_PAST }

        private fun requireAcceptedPredecessor(
            record: Record,
            binding: AcceptedWriteBinding,
            epoch: AcceptedPredecessorEpoch,
        ) {
            require(
                record.reboot != null &&
                    when (epoch) {
                        AcceptedPredecessorEpoch.CURRENT -> binding.reboot == record.reboot
                        AcceptedPredecessorEpoch.CURRENT_OR_PAST -> binding.reboot <= record.reboot
                    },
            )
            require(binding.operationId.isNotBlank() && binding.reservationId.isNotBlank())
            require(binding.counter > 0 && binding.priorWrite >= 0 && binding.priorWrite < binding.counter)
            require(binding.characteristic.lowercase() == EVENT_INDEX_CHARACTERISTIC)
            require(binding.purpose == "HISTORY_SELECTOR")
            require(binding.payloadHash.matches(Regex("[0-9a-f]{64}")))
            require(binding.evidenceHash.matches(Regex("[0-9a-f]{64}")))
            require(
                binding.candidate in
                    setOf(
                        WriteCandidate.BENCH_STRICT_NEXT_SELECTOR,
                        WriteCandidate.BENCH_AMBIGUITY_CONVERGENCE_SELECTOR,
                    ),
            )
            require(
                (binding.candidate == WriteCandidate.BENCH_AMBIGUITY_CONVERGENCE_SELECTOR) ==
                    (binding.unresolvedPredecessor != null),
            )
            binding.unresolvedPredecessor?.let {
                requireUnresolvedPredecessor(record, it, UnresolvedPredecessorEpoch.CURRENT_OR_PAST)
            }
            require(
                record.writeEvidence.any { binding.matches(it) && it.resolution == WriteResolution.ACCEPTED },
            )
        }

        private fun requireUnresolvedPredecessor(
            record: Record,
            binding: UnresolvedWriteBinding,
            epoch: UnresolvedPredecessorEpoch,
        ) {
            require(
                record.reboot != null &&
                    when (epoch) {
                        UnresolvedPredecessorEpoch.CURRENT -> binding.reboot == record.reboot
                        UnresolvedPredecessorEpoch.CURRENT_OR_PAST -> binding.reboot <= record.reboot
                    },
            )
            require(binding.reservationId.isNotBlank() && binding.operationId.isNotBlank())
            require(binding.phase in setOf(Phase.POSSIBLY_SENT, Phase.ACKED))
            require(binding.counter > 0 && binding.priorWrite >= 0 && binding.counter - binding.priorWrite == 1L)
            require(isAmbiguityConvergenceSelector(binding.characteristic, binding.purpose))
            require(binding.payloadHash.matches(Regex("[0-9a-f]{64}")))
            require(binding.evidenceHash.matches(Regex("[0-9a-f]{64}")))
            require(binding.candidate == WriteCandidate.BENCH_STRICT_NEXT_SELECTOR)
            require(
                record.writeEvidence.any { evidence -> binding.matches(binding.reservation(), evidence) },
            )
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
        private fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
