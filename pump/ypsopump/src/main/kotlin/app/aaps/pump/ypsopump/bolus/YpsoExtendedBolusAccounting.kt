package app.aaps.pump.ypsopump.bolus

import app.aaps.core.data.model.EB
import app.aaps.core.data.pump.defs.PumpType

/** Verifies the persisted record for one physical extended bolus by stable pump identity. */
object YpsoExtendedBolusAccounting {

    fun matches(record: EB?, pumpId: Long, timestamp: Long, amount: Double, duration: Long, serial: String): Boolean =
        record?.let {
            it.isValid &&
                it.ids.pumpId == pumpId &&
                it.ids.pumpType == PumpType.YPSOPUMP &&
                it.ids.pumpSerial == serial &&
                it.timestamp == timestamp &&
                it.amount == amount &&
                it.duration == duration
        } == true
}
