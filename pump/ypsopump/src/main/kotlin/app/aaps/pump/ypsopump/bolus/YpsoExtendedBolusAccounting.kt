package app.aaps.pump.ypsopump.bolus

import app.aaps.core.data.model.EB
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.pump.ypsopump.comm.commands.BolusCommand

/** Verifies the persisted record for one physical extended bolus by stable pump identity. */
object YpsoExtendedBolusAccounting {

    data class TerminalWindow(val start: Long, val duration: Long) {
        val end: Long get() = start + duration
    }

    data class CancelledStatusObservation(
        val deliveredCentiUnits: Int,
        val observedAt: Long,
    )

    /**
     * A cancel request or ACK is not terminal evidence. A post-cancel status is terminal evidence only
     * when the pump reports the already-proven slow block idle while retaining its exact sequence,
     * programmed shape, and delivered counter.
     */
    fun cancelledStatusObservation(
        attempt: YpsoBolusAttempt,
        status: BolusCommand,
        observedAt: Long,
    ): CancelledStatusObservation? {
        val dispatchedAt = attempt.dispatchedAt ?: return null
        if (attempt.shape == YpsoBolusShape.IMMEDIATE ||
            attempt.cancelRequestId == null || attempt.cancelBlock != YpsoBolusBlock.SLOW ||
            status.extendedStatusCode != BolusCommand.STATUS_IDLE ||
            status.extendedSequence != attempt.pumpSlowSequence ||
            Math.round(status.extendedTotalUnits * 100.0).toInt() != attempt.requestedCentiUnits ||
            status.extendedMinutesTotal != attempt.durationMinutes ||
            Math.round(status.comboImmediateTotalUnits * 100.0).toInt() != attempt.immediateCentiUnits
        ) return null
        val delivered = Math.round(status.extendedDeliveredUnits * 100.0).toInt()
        if (delivered !in 0..attempt.requestedCentiUnits ||
            delivered < (attempt.cancelObservedCentiUnits ?: 0) ||
            observedAt < dispatchedAt
        ) return null
        return CancelledStatusObservation(delivered, observedAt)
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

    /**
     * Closing window for a cancellation the pump acknowledged but never proved. The pump has stopped,
     * so the record must not keep claiming delivery to its programmed end; it is closed at the moment
     * delivery stopped, clamped to the programmed plan so recovery cannot invent extra delivery time.
     */
    fun unprovenCancelWindow(attempt: YpsoBolusAttempt, stoppedAt: Long): TerminalWindow {
        require(attempt.shape != YpsoBolusShape.IMMEDIATE)
        require(attempt.cancelRequestId != null) { "cancellation was never dispatched" }
        val start = requireNotNull(attempt.dispatchedAt)
        val plannedDuration = attempt.durationMinutes * 60_000L
        return TerminalWindow(start, (stoppedAt - start).coerceIn(1L, plannedDuration))
    }

    /**
     * Insulin the pump can have delivered across [window] at the programmed rate. Proven pump evidence
     * outranks the schedule, so an observed larger amount is never reduced to the elapsed estimate.
     */
    fun elapsedCentiUnits(attempt: YpsoBolusAttempt, window: TerminalWindow): Int {
        val plannedDuration = attempt.durationMinutes * 60_000L
        val scheduled = Math.round(attempt.requestedCentiUnits.toDouble() * window.duration / plannedDuration).toInt()
        return scheduled.coerceIn(0, attempt.requestedCentiUnits)
            .coerceAtLeast(attempt.cancelObservedCentiUnits ?: 0)
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
