package app.aaps.pump.ypsopump.comm.commands

import app.aaps.pump.ypsopump.comm.YpsoCommand
import app.aaps.pump.ypsopump.comm.YpsoCommandCodes
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry

/**
 * Strict history read commands: exact COUNT and CRC-validated event VALUE decoding.
 *
 * Index selection is deliberately not offered here. On this target it is a serialized selector
 * write that must go through the reviewed write path with durable counter ownership, target
 * evidence and explicit reconciliation; this read-only command set must not become an unguarded
 * writer or apply event-family CRC framing to alarm/system values it has not evidenced.
 */
class CountCommand(commandCode: YpsoCommandCodes) : YpsoCommand(commandCode) {
    var entryCount: Int = 0; private set

    override fun encode(): ByteArray = byteArrayOf(0x00)

    override fun decode(data: ByteArray) {
        val decoded = YpsoGlb.decodeExact(data)
        success = decoded != null && decoded >= 0
        if (success) entryCount = checkNotNull(decoded)
    }
}

/** Event-history value decoder with exact field widths and mandatory CRC integrity. */
class EventValueCommand : YpsoCommand(YpsoCommandCodes.EVENT_ENTRY_VALUE) {
    var entry: YpsoHistoryEntry? = null; private set

    override fun encode(): ByteArray = byteArrayOf(0x00)

    override fun decode(data: ByteArray) {
        entry = YpsoHistoryEntry.decodeWire(data)
        success = entry != null
    }
}
