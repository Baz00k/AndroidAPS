package app.aaps.pump.ypsopump.history

import app.aaps.pump.ypsopump.comm.YpsoCrc
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Strictly decoded 17-byte YpsoPump history value.
 *
 * The first field is deliberately named [factorySeconds], not timestamp. The pinned research
 * reference says history uses factory time, but its separate source parser's year-2000 conversion
 * is not target evidence. Conversion to an instant therefore requires a qualified calibration and
 * is intentionally outside this wire decoder.
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
