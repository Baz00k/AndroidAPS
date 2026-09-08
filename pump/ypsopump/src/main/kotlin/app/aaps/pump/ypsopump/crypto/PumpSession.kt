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
    data class Reservation(val id: String, val counter: Long, val phase: Phase)
    data class Record(
        val pump: String,
        val keyId: String,
        val generation: String,
        val reboot: Int,
        val read: Long,
        val write: Long?,
        val reservation: Reservation? = null
    )
    data class State(val records: List<Record> = emptyList())
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
        record = saved
        key = sharedKey.copyOf()
        return Token(saved.generation, UUID.randomUUID().toString()).also { token = it }
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
            check(previous.reboot == reboot && read >= previous.read) { "Cannot roll back or reset an imported key" }
            persist(current.copy(records = current.records.map { if (it == previous) it.copy(read = read) else it }))
        } else {
            persist(current.copy(records = current.records + Record(pump, id, UUID.randomUUID().toString(), reboot, read, null)))
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
    internal fun accept(origin: Token, id: String, message: SessionCrypto.Message): ByteArray {
        val old = owned(origin)
        check(transaction == id) { "Stale transaction" }
        require(message.reboot >= 0 && message.counter >= 0) { "Counter outside supported signed range" }
        // Authenticated metadata is not evidence of reboot/reset semantics. Neither direction can
        // automatically reseed either counter until a target transition is independently validated.
        if (message.reboot != old.reboot) throw SecurityException("Unvalidated reboot transition")
        if (message.counter <= old.read) throw SecurityException("Replayed or rolled-back read")
        update(old.copy(read = message.counter))
        return message.body // Later CRC/schema rejection must not undo this durable replay floor.
    }

    @Synchronized
    fun decrypt(origin: Token, id: String, payload: ByteArray, crypto: SessionCrypto): ByteArray {
        owned(origin)
        check(transaction == id) { "Stale transaction" }
        return accept(origin, id, crypto.decrypt(payload, checkNotNull(key)))
    }

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
                require(r.reboot >= 0 && r.read >= 0 && (r.write == null || r.write >= 0))
                r.reservation?.let {
                    require(it.id.isNotBlank() && it.counter > 0 && it.counter == r.write)
                }
            }
        }
    }
}
