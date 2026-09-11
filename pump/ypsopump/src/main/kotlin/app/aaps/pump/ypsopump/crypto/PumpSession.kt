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
        val payloadHash: String? = null
    )
    data class WriteEvidence(
        val operationId: String,
        val reservationId: String,
        val counter: Long,
        val resolution: WriteResolution?,
        val evidenceHash: String,
        val detail: String
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
        val writeEvidence: List<WriteEvidence> = emptyList()
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
                    reservation = candidate.reservation
                )
            } else {
                replaced.copy(
                    reboot = candidate.reboot ?: replaced.reboot,
                    read = listOfNotNull(replaced.read, candidate.read).maxOrNull(),
                    write = candidate.write ?: replaced.write,
                    reservation = candidate.reservation ?: replaced.reservation
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
            persist(current.copy(records = current.records.map { if (it == previous) it.copy(read = read) else it }, activeGeneration = previous.generation))
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
        persist(current.copy(records = current.records.map { if (it == previous) it.copy(write = write) else it }))
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
            check(old.reservation == null) { "Cannot transition with an outstanding write record" }
            val next = old.copy(reboot = message.reboot, read = message.counter, write = null)
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
        val old = owned(origin)
        check(transaction == id) { "Stale transaction" }
        check(old.reservation == null || old.reservation.phase == Phase.VERIFIED) { "Unresolved write" }
        val last = old.write ?: throw SecurityException("Write counter uncertain; bench validation required")
        check(last < Long.MAX_VALUE) { "Write counter exhausted" }
        intent?.let {
            require(it.operationId.isNotBlank() && it.characteristic.isNotBlank() && it.purpose.isNotBlank())
            require(it.payloadHash.matches(Regex("[0-9a-f]{64}")))
        }
        val reserved = Reservation(
            id,
            last + 1,
            Phase.RESERVED,
            intent?.operationId,
            intent?.characteristic,
            intent?.purpose,
            intent?.payloadHash
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
        check(old.write == reserved.counter && reserved.counter > 0) { "Invalid write reservation" }
        update(old.copy(write = reserved.counter - 1, reservation = null))
    }

    /** A persisted RESERVED phase proves the dispatch boundary was never committed and is safe to roll back after restart. */
    @Synchronized
    fun recoverReservedNotSent(origin: Token, operationId: String, evidenceHash: String, detail: String) {
        val old = owned(origin)
        check(transaction == null) { "Another session transaction is active" }
        val reserved = checkNotNull(old.reservation)
        check(reserved.operationId == operationId && reserved.phase == Phase.RESERVED) { "Write is not proven undispatched" }
        check(old.write == reserved.counter && reserved.counter > 0) { "Invalid write reservation" }
        require(evidenceHash.matches(Regex("[0-9a-f]{64}")) && detail.isNotBlank() && detail.length <= 4096)
        val evidence = WriteEvidence(
            operationId,
            reserved.id,
            reserved.counter,
            WriteResolution.REJECTED_COUNTER_NOT_CONSUMED,
            evidenceHash,
            detail
        )
        update(old.copy(write = reserved.counter - 1, reservation = null, writeEvidence = old.writeEvidence + evidence))
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
        val evidence = WriteEvidence(
            checkNotNull(reserved.operationId),
            reserved.id,
            reserved.counter,
            null,
            evidenceHash,
            detail
        )
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
        val evidence = WriteEvidence(
            checkNotNull(reserved.operationId),
            reserved.id,
            reserved.counter,
            resolution,
            evidenceHash,
            detail
        )
        val next = when (resolution) {
            WriteResolution.ACCEPTED,
            WriteResolution.REJECTED_COUNTER_CONSUMED -> old.copy(
                reservation = reserved.copy(phase = Phase.VERIFIED),
                writeEvidence = old.writeEvidence + evidence
            )
            WriteResolution.REJECTED_COUNTER_NOT_CONSUMED -> old.copy(
                write = reserved.counter - 1,
                reservation = null,
                writeEvidence = old.writeEvidence + evidence
            )
        }
        update(next)
    }

    @Synchronized
    fun snapshot(): Record? = record

    private fun owned(origin: Token): Record {
        check(token == origin && state != null) { "Stale or unavailable session" }
        return checkNotNull(record)
    }

    private fun update(next: Record) {
        val current = checkNotNull(state)
        persist(current.copy(records = current.records.map { if (it.generation == next.generation) next else it }))
        record = next
    }

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
                r.reservation?.let {
                    require(it.id.isNotBlank() && it.counter > 0 && it.counter == r.write)
                    require((it.operationId == null) == (it.characteristic == null) && (it.characteristic == null) == (it.purpose == null) && (it.purpose == null) == (it.payloadHash == null))
                    require(it.operationId == null || it.operationId.isNotBlank() && it.characteristic!!.isNotBlank() && it.purpose!!.isNotBlank() && it.payloadHash!!.matches(Regex("[0-9a-f]{64}")))
                }
                require(
                    r.writeEvidence.map { it.reservationId to it.evidenceHash }.distinct().size == r.writeEvidence.size
                )
                r.writeEvidence.forEach {
                    require(it.operationId.isNotBlank() && it.reservationId.isNotBlank() && it.counter > 0)
                    require(it.evidenceHash.matches(Regex("[0-9a-f]{64}")) && it.detail.isNotBlank() && it.detail.length <= 4096)
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
