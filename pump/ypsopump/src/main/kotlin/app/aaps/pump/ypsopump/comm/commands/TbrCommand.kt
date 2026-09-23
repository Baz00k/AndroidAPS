package app.aaps.pump.ypsopump.comm.commands

import app.aaps.pump.ypsopump.comm.YpsoCommand
import app.aaps.pump.ypsopump.comm.YpsoCommandCodes
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * START_STOP_TBR (index 29) request: `percent || ~percent || minutes || ~minutes`, each u32 LE, no CRC.
 * Measured command semantics, limits and result codes are in docs/protocol.md.
 */
class TbrCommand(
    private val percent: Int,
    private val durationMinutes: Int
) : YpsoCommand(YpsoCommandCodes.START_STOP_TBR) {

    var tbrStatusCode: Int = 0; private set

    override fun encode(): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(percent).putInt(percent.inv())
            .putInt(durationMinutes).putInt(durationMinutes.inv())
            .array()

    override fun decode(data: ByteArray) {
        if (data.isNotEmpty()) {
            tbrStatusCode = data[0].toInt() and 0xFF
            success = true
        } else {
            success = false
        }
    }

    companion object {
        /** Ends a running TBR; accepted without effect when none is running. */
        fun cancelPayload(): ByteArray = TbrCommand(100, 0).encode()
    }
}
