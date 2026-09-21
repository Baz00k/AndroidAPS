package app.aaps.pump.ypsopump.comm

import app.aaps.pump.ypsopump.bolus.YpsoBolusBlock

/**
 * Decoded CONTROL_NOTIFY (…fcbee58b…) bolus notification.
 *
 * The pump pushes this whenever a bolus block changes state, so it reports a terminal transition
 * within about a second instead of requiring a status or history read. [CHAR_BOLUS_STATUS] clears its
 * sequence and amount at terminal, which is why the notification is the reliable terminal signal.
 *
 * Observed 13-byte body on firmware V05.00.52:
 *   fastStatus(u8) || fastSequence(u32 LE) || slowStatus(u8) || slowSequence(u32 LE) || trailer(3)
 *
 * The trailing three bytes are not yet qualified and are ignored rather than guessed at.
 */
data class YpsoBolusNotification(
    val fastStatusCode: Int,
    val fastSequence: Long,
    val slowStatusCode: Int,
    val slowSequence: Long,
) {

    fun statusCode(block: YpsoBolusBlock): Int =
        when (block) {
            YpsoBolusBlock.FAST -> fastStatusCode
            YpsoBolusBlock.SLOW -> slowStatusCode
        }

    fun sequence(block: YpsoBolusBlock): Long =
        when (block) {
            YpsoBolusBlock.FAST -> fastSequence
            YpsoBolusBlock.SLOW -> slowSequence
        }

    /** Whether the pump announced that [sequence] on [block] stopped delivering for any reason. */
    fun isTerminalFor(block: YpsoBolusBlock, sequence: Long): Boolean =
        sequence(block) == sequence && statusCode(block) in TERMINAL_CODES

    companion object {
        const val STATUS_IDLE = 0
        const val STATUS_DELIVERING = 1
        const val STATUS_CANCELLED = 3
        const val STATUS_COMPLETED = 4

        /**
         * Both codes mean the block stopped delivering. Only [STATUS_COMPLETED] has been observed on
         * target, for normal completion and for cancellation alike, so the code says that delivery
         * ended and never how much was delivered. The amount remains history's to establish.
         */
        val TERMINAL_CODES = setOf(STATUS_CANCELLED, STATUS_COMPLETED)

        private const val BODY_LENGTH = 13

        fun decode(value: ByteArray): YpsoBolusNotification? {
            if (value.size < BODY_LENGTH) return null
            fun u32(offset: Int): Long =
                (value[offset].toLong() and 0xff) or
                    ((value[offset + 1].toLong() and 0xff) shl 8) or
                    ((value[offset + 2].toLong() and 0xff) shl 16) or
                    ((value[offset + 3].toLong() and 0xff) shl 24)
            val fastStatus = value[0].toInt() and 0xff
            val slowStatus = value[5].toInt() and 0xff
            val known = setOf(STATUS_IDLE, STATUS_DELIVERING, STATUS_CANCELLED, STATUS_COMPLETED)
            if (fastStatus !in known || slowStatus !in known) return null
            return YpsoBolusNotification(fastStatus, u32(1), slowStatus, u32(6))
        }
    }
}
