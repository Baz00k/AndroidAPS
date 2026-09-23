package app.aaps.pump.ypsopump.history

import app.aaps.pump.ypsopump.comm.YpsoCrc
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packed 120-bit history-row fingerprint: factorySeconds(32) | type(8) | value1(16) in [high],
 * value2(16) | value3(16) | sequence(32) in [low]. Fixed-width fields make equality exact; no
 * string or byte-array allocation is produced in scan loops.
 */
data class YpsoHistoryFingerprint internal constructor(val high: Long, val low: Long)

/**
 * Strictly decoded 17-byte YpsoPump history value.
 *
 * The first field is deliberately named [factorySeconds], not timestamp. Paired target observations
 * establish pump-local wall-clock seconds since 2000-01-01; conversion to an instant still requires
 * an explicit time-zone resolution and is intentionally outside this wire decoder.
 */
data class YpsoHistoryEntry(
    val factorySeconds: Long,
    val eventType: Int,
    val value1: Int,
    val value2: Int,
    val value3: Int,
    val sequence: Long,
    val index: Int,
) {
    init {
        require(factorySeconds in 0..0xffffffffL)
        require(eventType in 0..0xff)
        require(value1 in 0..0xffff)
        require(value2 in 0..0xffff)
        require(value3 in 0..0xffff)
        require(sequence in 0..0xffffffffL)
        require(index in 0..0xffff)
    }

    /**
     * Stable identity fingerprint. The moving ring index and mutable event state are excluded.
     *
     * Target pairing shows that an active TBR row keeps its sequence and factory time while type 9
     * is rewritten in place to terminal type 10. Values may also change from the requested duration
     * to elapsed minutes on cancellation. Those fields are semantics, not event identity.
     */
    fun fingerprint(): YpsoHistoryFingerprint {
        val mutableTbr = eventType == 9 || eventType == 10
        return pack(
            if (mutableTbr) 9 else eventType,
            value1,
            if (mutableTbr) 0 else value2,
            if (mutableTbr) 0 else value3,
        )
    }

    /** Exact semantic state fingerprint, excluding only the moving ring index. */
    fun stateFingerprint(): YpsoHistoryFingerprint = pack(eventType, value1, value2, value3)

    /** Match legacy persisted fingerprints as well as exact rows. Bolus state is mutable, but only
     * within its family and the same start time, sequence and reboot epoch. Never cross-match shapes.
     */
    fun matchesCursor(cursor: YpsoHistoryCursor, reboot: Long): Boolean {
        if (sequence != cursor.identity.sequence) return false
        if (fingerprint() == cursor.fingerprint) return true
        if (reboot != cursor.pumpReboot || factorySeconds != cursor.fingerprint.high ushr 24) return false
        val oldType = ((cursor.fingerprint.high ushr 16) and 0xff).toInt()
        return when (oldType) {
            1 -> eventType == 1 || eventType == 3
            19 -> eventType == 19 || eventType == 2
            17 -> eventType == 17 || eventType == 18
            // Delivered totals can be updated while the row already has its terminal type.
            2, 3, 18 -> eventType == oldType
            else -> false
        }
    }

    private fun pack(type: Int, v1: Int, v2: Int, v3: Int): YpsoHistoryFingerprint =
        YpsoHistoryFingerprint(
            (factorySeconds shl 24) or (type.toLong() shl 16) or v1.toLong(),
            (v2.toLong() shl 48) or (v3.toLong() shl 32) or sequence,
        )

    companion object {
        const val PAYLOAD_SIZE = 17
        const val WIRE_SIZE = PAYLOAD_SIZE + 2

        /** Decode one exact CRC-protected history value. Raw or trailing-byte fallbacks are rejected. */
        fun decodeWire(body: ByteArray): YpsoHistoryEntry? {
            if (body.size != WIRE_SIZE) return null
            val payload = YpsoCrc.validatedPayload(body) ?: return null
            return decodePayload(payload)
        }

        /** Decode an already integrity-validated exact payload. */
        fun decodePayload(payload: ByteArray): YpsoHistoryEntry? {
            if (payload.size != PAYLOAD_SIZE) return null
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            return YpsoHistoryEntry(
                factorySeconds = buffer.int.toLong() and 0xffffffffL,
                eventType = buffer.get().toInt() and 0xff,
                value1 = buffer.short.toInt() and 0xffff,
                value2 = buffer.short.toInt() and 0xffff,
                value3 = buffer.short.toInt() and 0xffff,
                sequence = buffer.int.toLong() and 0xffffffffL,
                index = buffer.short.toInt() and 0xffff,
            )
        }
    }
}
