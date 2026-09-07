package app.aaps.libre3

/**
 * Transfer framing for the Libre 3 security characteristics.
 *
 * Payloads larger than one BLE write are split into fixed frames, and the two directions do
 * NOT use the same scheme — a detail worth stating loudly because getting it backwards
 * produces a handshake that fails only after the crypto, where it looks like a key problem:
 *
 *   sensor → app   sequence(1) ‖ payload(n)          length announced in advance
 *   app → sensor   offsetLE16(2) ‖ payload(18)       always exactly 20 bytes, zero-padded
 *
 * Deliberately free of Android types so it can be unit-tested on the JVM.
 * Derived from Juggluco's `Libre3GattCallback` (`getsecdata` / `writedata` / `preparedata`).
 */
object Libre3Framing {

    const val WRITE_FRAME_SIZE = 20
    const val WRITE_HEADER_SIZE = 2
    const val WRITE_PAYLOAD_SIZE = WRITE_FRAME_SIZE - WRITE_HEADER_SIZE   // 18

    /**
     * Announcement received on COMMAND_RESPONSE telling us what is about to be streamed.
     * `value[0]` is the signal, `value[1]` the byte count.
     */
    object Signal {
        const val CERT_ACCEPTED = 4     // single-byte message; reply with SecurityCommand.UNKNOWN_9
        const val CHALLENGE_READY = 8
        const val CERTIFICATE_READY = 10
        const val EPHEMERAL_READY = 15
    }

    data class Announcement(val signal: Int, val length: Int)

    /**
     * Parse a COMMAND_RESPONSE notification. A single-byte value is a bare signal with no
     * transfer following it; anything else announces [Announcement.length] bytes to come.
     */
    fun parseAnnouncement(value: ByteArray): Announcement? {
        if (value.isEmpty()) return null
        val signal = value[0].toInt() and 0xFF
        if (value.size == 1) return Announcement(signal, 0)
        return Announcement(signal, value[1].toInt() and 0xFF)
    }

    /**
     * Reassembles sensor → app transfers.
     *
     * Each frame is `sequence(1) ‖ payload`. Sequence starts at 0 and must increment by
     * exactly one; a gap means frames were dropped or reordered, and the caller must
     * disconnect rather than carry on with a hole in the buffer.
     */
    class Reassembler(val expectedLength: Int) {
        private val buffer = ByteArray(expectedLength)
        private var received = 0
        private var lastSequence = -1

        val isComplete: Boolean get() = received >= expectedLength

        sealed interface Result {
            /** More frames expected; [remaining] bytes still outstanding. */
            data class NeedMore(val remaining: Int) : Result
            data class Complete(val data: ByteArray) : Result
            data class Error(val reason: String) : Result
        }

        fun offer(frame: ByteArray): Result {
            if (frame.isEmpty()) return Result.Error("empty frame")
            val sequence = frame[0].toInt() and 0xFF
            if (sequence != lastSequence + 1)
                return Result.Error("sequence gap: got $sequence, expected ${lastSequence + 1}")

            val payloadLength = frame.size - 1
            if (received + payloadLength > expectedLength)
                return Result.Error("overflow: $received + $payloadLength > $expectedLength")

            frame.copyInto(buffer, received, 1, frame.size)
            received += payloadLength
            lastSequence = sequence

            return if (isComplete) Result.Complete(buffer.copyOf(expectedLength))
            else Result.NeedMore(expectedLength - received)
        }
    }

    /**
     * Splits an app → sensor payload into 20-byte frames.
     *
     * Each frame is `offset(uint16 LE) ‖ 18 bytes`, zero-padded on the last one — the frame is
     * always full width. The offset is the running byte position, not a frame index.
     */
    fun splitForWrite(payload: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>((payload.size + WRITE_PAYLOAD_SIZE - 1) / WRITE_PAYLOAD_SIZE)
        var offset = 0
        while (offset < payload.size) {
            val chunk = minOf(WRITE_PAYLOAD_SIZE, payload.size - offset)
            val frame = ByteArray(WRITE_FRAME_SIZE)          // zero-padded by construction
            frame[0] = (offset and 0xFF).toByte()
            frame[1] = ((offset shr 8) and 0xFF).toByte()
            payload.copyInto(frame, WRITE_HEADER_SIZE, offset, offset + chunk)
            frames.add(frame)
            offset += chunk
        }
        return frames
    }
}
