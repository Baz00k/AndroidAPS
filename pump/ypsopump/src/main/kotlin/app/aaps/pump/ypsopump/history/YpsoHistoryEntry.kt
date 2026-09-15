package app.aaps.pump.ypsopump.history

import app.aaps.pump.ypsopump.comm.YpsoCrc
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    /** Stable event fingerprint. The moving ring index is deliberately excluded. */
    fun fingerprint(): String = encodePayload().joinToString("") { "%02x".format(it) }

    private fun encodePayload(): ByteArray =
        ByteBuffer
            .allocate(IMMUTABLE_PAYLOAD_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(factorySeconds.toInt())
            .put(eventType.toByte())
            .putShort(value1.toShort())
            .putShort(value2.toShort())
            .putShort(value3.toShort())
            .putInt(sequence.toInt())
            .array()

    companion object {
        const val PAYLOAD_SIZE = 17
        private const val IMMUTABLE_PAYLOAD_SIZE = PAYLOAD_SIZE - 2
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
