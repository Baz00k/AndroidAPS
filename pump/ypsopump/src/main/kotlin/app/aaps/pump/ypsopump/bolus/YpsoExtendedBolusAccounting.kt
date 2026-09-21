package app.aaps.pump.ypsopump.bolus

import app.aaps.core.data.model.EB
import app.aaps.core.data.pump.defs.PumpType

/** Verifies the persisted record for one physical extended bolus by stable pump identity. */
object YpsoExtendedBolusAccounting {

    data class TerminalWindow(val start: Long, val duration: Long) {
        val end: Long get() = start + duration
    }

    /**
     * Terminal square/combination rows do not provide a trustworthy stop instant. Preserve the
     * dispatch start and close a partial delivery when it was observed, clamped to the programmed
     * end so delayed recovery cannot invent extra delivery time. Full delivery keeps its plan.
     */
    fun terminalWindow(attempt: YpsoBolusAttempt, deliveredCentiUnits: Int, observedAt: Long): TerminalWindow {
        val start = requireNotNull(attempt.dispatchedAt)
        val plannedDuration = attempt.durationMinutes * 60_000L
        val completed = deliveredCentiUnits == attempt.requestedCentiUnits
        val duration = if (completed) plannedDuration else (observedAt - start).coerceIn(1L, plannedDuration)
        return TerminalWindow(start, duration)
    }

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
