package app.aaps.pump.ypsopump.bolus

import app.aaps.core.data.model.EB
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.pump.ypsopump.comm.commands.BolusCommand

/** Verifies the persisted record for one physical extended bolus by stable pump identity. */
object YpsoExtendedBolusAccounting {

    /** Type-3 terminal history reports elapsed minutes, not the original programmed duration.
     * Zero with a nonzero amount is an initial pulse. Keep a known sub-minute window; otherwise
     * represent that pulse at the start using the minimum positive EB duration (not a second bolus).
     * Positive minute values remain quantized pump evidence, not exact pulse timestamps.
     */
    fun squareHistoryDuration(elapsedMinutes: Int, recordedDuration: Long): Long {
        require(elapsedMinutes >= 0 && recordedDuration > 0)
        if (elapsedMinutes == 0) return if (recordedDuration < 60_000L) recordedDuration else 1L
        val historyDuration = elapsedMinutes * 60_000L
        // Keep the finer observed stop window when it agrees within the pump's minute resolution.
        return if (kotlin.math.abs(recordedDuration - historyDuration) < 60_000L) recordedDuration else historyDuration
    }

    data class SquarePart(val start: Long, val amount: Double, val duration: Long)

    /**
     * The part of a pump-started square bolus that AAPS accounts: all of it, or, when it was already
     * running at [registeredAt], only what it delivered from then on. AAPS accepts no record from
     * before a pump's registration, like any other earlier delivery. A square bolus delivers evenly,
     * so that part is the amount's share of the window after registration. The pump reports elapsed
     * whole minutes, so the real end is up to a minute later; that latest end gives the largest
     * share, rounded up to the pump's 0.01 U, so the error stays on the side of more insulin.
     * The caller skips a bolus that ended before registration even at its latest end.
     */
    fun squareFrom(registeredAt: Long, start: Long, elapsedMinutes: Int, amount: Double): SquarePart {
        val duration = (elapsedMinutes * MINUTE).coerceAtLeast(1L)
        if (start >= registeredAt) return SquarePart(start, amount, duration)
        val latestEnd = start + (elapsedMinutes + 1L) * MINUTE
        require(latestEnd > registeredAt) { "square bolus ended before registration" }
        val centiUnits = Math.round(amount * 100)
        val after = (centiUnits * (latestEnd - registeredAt) + (latestEnd - start) - 1) / (latestEnd - start)
        return SquarePart(registeredAt, after / 100.0, (start + duration - registeredAt).coerceAtLeast(1L))
    }

    private const val MINUTE = 60_000L

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
        val stoppedAt = attempt.cancelStoppedAt ?: attempt.blockTerminalAt ?: observedAt
        val duration = if (completed) plannedDuration else (stoppedAt - start).coerceIn(1L, plannedDuration)
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
