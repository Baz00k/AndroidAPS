package app.aaps.pump.ypsopump.comm.commands

import app.aaps.pump.ypsopump.comm.YpsoCommand
import app.aaps.pump.ypsopump.comm.YpsoCommandCodes
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry

/**
 * History entry read commands following the COUNT/INDEX/VALUE pattern.
 *
 * Usage:
 *   1. CountCommand(ALARM_ENTRY_COUNT) → entryCount
 *   2. For i in 0..<entryCount:
 *      a. IndexCommand(ALARM_ENTRY_INDEX, i)
 *      b. ValueCommand(ALARM_ENTRY_VALUE) → raw bytes
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

class IndexCommand(commandCode: YpsoCommandCodes, private val index: Int) : YpsoCommand(commandCode) {
    override fun encode(): ByteArray = YpsoGlb.encode(index)

    override fun decode(data: ByteArray) {
        // Index write acknowledgment
        success = data.isEmpty() || (data.isNotEmpty() && data[0].toInt() == 0)
    }
}

class ValueCommand(commandCode: YpsoCommandCodes) : YpsoCommand(commandCode) {
    var rawValue: ByteArray = byteArrayOf(); private set

    override fun encode(): ByteArray = byteArrayOf(0x00)

    override fun decode(data: ByteArray) {
        val payload = YpsoCrc.validatedPayload(data)
        success = payload != null
        rawValue = payload ?: byteArrayOf()
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
