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
    enum class AvailabilityCause {
        UNCONFIGURED,
        BOND_OR_PERMISSION,
        TRANSPORT,
        AUTHENTICATION,
        ENCRYPTED_STATUS_UNAVAILABLE,
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
    data class Reservation(val id: String, val counter: Long, val phase: Phase)
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
        val verifiedSerial: String? = null
    )
    data class State(
        val records: List<Record> = emptyList(),
        val activeGeneration: String? = null,
        val availability: Availability = Availability()
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

    private var state: State? = runCatching { store.load().also(::validate) }.getOrNull()
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
        val saved = state?.records?.singleOrNull { it.pump == pump && it.keyId == id }
            ?: throw SecurityException("Session recovery required: no durable replay baseline")
        check(saved.keyHex == null || saved.keyHex.equals(sharedKey.toHex(), ignoreCase = true)) { "Protected key does not match session record" }
        record = saved
        key = sharedKey.copyOf()
        return Token(saved.generation, UUID.randomUUID().toString()).also { token = it }
    }

    /** Atomically replaces the active identity/key bundle while retaining every prior generation. */
    @Synchronized
    fun install(provisioning: Provisioning): Installation {
        val plan = planInstallation(provisioning)
        val current = state ?: throw SecurityException("Session storage unavailable")
        val records = if (plan.previous == null) current.records + plan.installed else
            current.records.map { if (it.generation == plan.previous.generation) plan.installed else it }
        val retainedCauses = current.availability.causes - AvailabilityCause.UNCONFIGURED
        val nextCauses = retainedCauses + AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE + AvailabilityCause.COUNTER_UNCERTAIN
        persist(
            current.copy(
                records = records,
                activeGeneration = plan.installed.generation,
                availability = current.availability.copy(
                    causes = nextCauses,
                    since = current.availability.since.takeIf { retainedCauses.isNotEmpty() } ?: provisioning.importedAt,
                    failures = current.availability.failures.takeIf { retainedCauses.isNotEmpty() } ?: 0,
                    retryAt = current.availability.retryAt.takeUnless { AvailabilityCause.SUSPECTED_REKEY_REQUIRED in nextCauses }
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
        val previous = current.records.singleOrNull { it.keyId == id }
        if (previous != null) check(previous.pump == provisioning.pump) { "Key belongs to another pump" }
        val active = current.records.singleOrNull { it.generation == current.activeGeneration }
        val unresolved = listOfNotNull(active, previous).distinctBy(Record::generation)
            .firstOrNull { it.reservation != null && it.reservation.phase != Phase.VERIFIED }
        if (unresolved != null)
            throw SecurityException("Cannot replace a session with unresolved pump accounting")
        val installation = when {
            previous != null -> Installation.SAME_KEY
            active == null -> Installation.FIRST_PUMP
            active.pump == provisioning.pump -> Installation.ROTATED_KEY
            else -> Installation.SWITCHED_PUMP
        }
        val installed = previous?.copy(
            serial = provisioning.serial,
            keyHex = provisioning.sharedKey.toHex(),
            createdAt = provisioning.createdAt ?: previous.createdAt,
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
    fun activeRecord(): Record? = state?.records?.singleOrNull { it.generation == state?.activeGeneration }

    @Synchronized
    fun availability(): Availability = state?.availability ?: Availability(setOf(AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE))

    @Synchronized
    fun setAvailability(availability: Availability) {
        val current = state ?: throw SecurityException("Session storage unavailable")
        persist(current.copy(availability = availability))
    }

    @Synchronized
    fun markVerified(serial: String, at: Long) {
        val current = state ?: throw SecurityException("Session storage unavailable")
        val active = current.records.singleOrNull { it.generation == current.activeGeneration }
            ?: throw SecurityException("No active session")
        check(active.serial == serial) { "Verified pump serial does not match configured identity" }
        val next = active.copy(verifiedAt = at, verifiedSerial = serial)
        persist(
            current.copy(
                records = current.records.map { if (it.generation == next.generation) next else it },
                availability = Availability(
                    causes = if (next.write == null) setOf(AvailabilityCause.COUNTER_UNCERTAIN) else emptySet(),
                    since = at
                )
            )
        )
        record = next
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
    fun reserve(origin: Token, id: String): Reservation {
        val old = owned(origin)
        check(transaction == id) { "Stale transaction" }
        check(old.reservation == null || old.reservation.phase == Phase.VERIFIED) { "Unresolved write" }
        val last = old.write ?: throw SecurityException("Write counter uncertain; bench validation required")
        check(last < Long.MAX_VALUE) { "Write counter exhausted" }
        val reserved = Reservation(id, last + 1, Phase.RESERVED)
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
            require(state.records.map { it.keyId }.distinct().size == state.records.size)
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
                }
            }
            require(state.activeGeneration == null || state.records.count { it.generation == state.activeGeneration } == 1)
            require(state.availability.failures >= 0)
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
        private fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
