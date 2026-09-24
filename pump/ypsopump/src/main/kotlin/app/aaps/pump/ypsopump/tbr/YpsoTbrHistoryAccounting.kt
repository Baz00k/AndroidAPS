package app.aaps.pump.ypsopump.tbr

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.ypsopump.history.YpsoHistoryEvent
import app.aaps.pump.ypsopump.history.YpsoHistoryKind
import app.aaps.pump.ypsopump.history.YpsoPumpLocalTime
import app.aaps.pump.ypsopump.history.YpsoPumpModeChange
import java.time.ZoneId

/** Read-back of temporary basal records. */
interface YpsoTbrRecordLookup {
    data class Record(val start: Long, val duration: Long, val valid: Boolean)

    /**
     * The record with this pump ID starting at [start], or null when none exists. A record the user
     * removed is returned with [Record.valid] false and is never written again.
     */
    fun byPumpId(pumpId: Long, pumpSerial: String, start: Long): Record?

    /** Whether any valid YpsoPump PUMP_SUSPEND record is still active at [at]. */
    fun suspendActiveAt(pumpSerial: String, at: Long): Boolean

    /** A PUMP_SUSPEND record, from a Stop row ([pumpId]) or from status alone ([temporaryId]). */
    data class Suspend(val pumpId: Long?, val temporaryId: Long?, val start: Long, val duration: Long, val valid: Boolean)

    /** The newest YpsoPump PUMP_SUSPEND record starting within a day before [at]. */
    fun latestSuspendBefore(pumpSerial: String, at: Long): Suspend?

    /** Whether any YpsoPump TBR record other than [exceptPumpId] starts after [from] and before [to]. */
    fun anyStartedBetween(pumpSerial: String, from: Long, to: Long, exceptPumpId: Long): Boolean

    /** A PUMP_SUSPEND record that status wrote under a temporary ID when it saw the pump stopped. */
    data class StatusSuspend(val temporaryId: Long, val start: Long, val duration: Long)

    /** Valid status-written stop records starting at or after [from]. */
    fun statusSuspendsFrom(pumpSerial: String, from: Long): List<StatusSuspend>

}

/**
 * Basal accounting from pump history (see docs/history.md). A TBR row keeps its sequence while the
 * pump rewrites it from running (type 9) to ended (type 10), so the row identity is the PumpSync pump
 * ID for the whole life of the TBR. An AAPS-started TBR is already recorded under a temporary ID; its
 * row binds to that record instead of importing a duplicate. Every write is verified by read-back and
 * a failure blocks the history cursor, so the row is applied again on the next scan.
 */
class YpsoTbrHistoryAccounting(
    private val pumpSync: PumpSync,
    private val journal: YpsoTbrJournal,
    private val lookup: YpsoTbrRecordLookup,
    /** AAPS accepts pump records only from this pump's registration on; older rows are not its to import. */
    private val registeredAt: (pumpSerial: String) -> Long = { 0L },
) {
    /**
     * Applies one reconciled history event; returns a blocking reason or null when applied.
     * [pumpClockOffsetMs] is the pump clock minus the phone clock, measured on the read that returned
     * this event, or null when it could not be measured or does not hold for this event because the
     * pump clock was set after it. [waitForClock] is true while a later read may still measure it.
     */
    fun apply(
        event: YpsoHistoryEvent,
        pumpSerial: String,
        zone: ZoneId,
        pumpClockOffsetMs: Long? = null,
        waitForClock: Boolean = false,
    ): String? {
        val kind = event.semantics.kind
        val tbr = kind in TBR_KINDS
        val mode = kind == YpsoHistoryKind.PUMP_MODE_CHANGED
        if (!tbr && !mode) return null
        val pumpId = event.identity.aapsPumpId
        val percent = event.entry.value1
        // Type 9 carries requested minutes; type 10 carries elapsed whole minutes, equal to the request
        // after natural expiry. Floored minutes keep a cancelled TBR within the minute the pump reports.
        val minutes = event.entry.value2
        val running = kind == YpsoHistoryKind.TEMP_BASAL_STARTED
        // An AAPS start already matched to this row is timed by AAPS and needs no pump time.
        if (tbr) (journal.boundTo(pumpId) ?: journal.identifiedAs(pumpId))?.let { return syncAttempt(it, pumpId, minutes * MINUTE) }
        // The AAPS record of an unmatched start stands in for this row, in every later rewrite too.
        if (tbr && journal.unmatchedAs(pumpId) != null) return null
        val pumpTime = (YpsoPumpLocalTime.resolve(event.entry.factorySeconds, zone) as? YpsoPumpLocalTime.Resolution.Resolved)
            ?.instant?.toEpochMilli()
        // On the phone's clock. With the offset measured on this read, a row's time is exact to a few
        // seconds; without it, only the drift profile reads allow bounds it.
        val start = pumpTime?.let { it - (pumpClockOffsetMs ?: 0L) } ?: return "TBR or pump-mode timestamp is ambiguous"
        val skew = if (pumpClockOffsetMs != null) MEASURED_SKEW else CLOCK_SKEW
        if (tbr) {
            bindCandidate(pumpId, percent, if (running) minutes else null, start, pumpSerial, skew)
                ?.let { return syncAttempt(it, pumpId, minutes * MINUTE) }
            // Without a usable clock a start's own row can fall outside the drift window, and importing
            // it would count the same TBR twice. A later read may still measure the clock; after that,
            // the start keeps its AAPS record in place of this row, and history moves on.
            if (pumpClockOffsetMs == null) {
                journal.all().firstOrNull {
                    it.awaitsBinding && it.rowPumpId == null && it.pumpSerial == pumpSerial && it.percent == percent &&
                        (it.baselinePumpId == null || it.baselinePumpId < pumpId)
                }?.let {
                    if (waitForClock) return "TBR row $pumpId may belong to AAPS start ${it.id}; $CLOCK_WAIT"
                    journal.unmatched(it.id, pumpId)
                    return null
                }
            }
        }
        // PumpSync refuses records from before this pump was registered; the pump's history reaches
        // further back than AAPS' use of it. Such a row was never AAPS's, so it is passed over. AAPS
        // starts, whose rows may read slightly earlier through clock skew, were matched above.
        if (start < registeredAt(pumpSerial)) return null
        if (mode) return pumpMode(event.semantics.modeChange, start, pumpId, pumpSerial, skew)
        // A start whose outcome status has not proven yet may be this very row: importing it now would
        // record the same TBR twice. Hold until status settles it.
        journal.all().firstOrNull {
            it.kind == YpsoTbrAttempt.Kind.START && it.pumpSerial == pumpSerial && it.percent == percent &&
                (it.baselinePumpId == null || it.baselinePumpId < pumpId) && it.awaitsStatus && it.dispatchedAt != null
        }?.let { return "TBR row $pumpId may belong to AAPS start ${it.id}, which status has not resolved yet" }
        val duration = (minutes * MINUTE).coerceAtLeast(1L)
        return sync(pumpId, pumpSerial, start, duration, keepStart = true) { at ->
            pumpSync.syncTemporaryBasalWithPumpId(
                at, percent.toDouble(), duration, false, PumpSync.TemporaryBasalType.NORMAL, pumpId, PumpType.YPSOPUMP, pumpSerial,
            )
        }
    }

    /**
     * The AAPS start whose time window this row's start falls in. The pump was proven idle right
     * before each start was sent, so its row began inside [dispatch, latest effect], widened by
     * [skew]: a few seconds when the pump clock was measured on this read, otherwise the drift that
     * profile reads allow. With a measured clock this is identity: an identical TBR set on the pump
     * instead would have to start within seconds of the AAPS command, which the pump would reject
     * while the AAPS TBR runs. [requestedMinutes] is the duration a running row still carries. Rows older than the attempt's history baseline are never its
     * own. Rows arrive oldest first, so of several windows the earliest-dispatched attempt is this
     * row's. A start that fits no row keeps its own record, which status reconciles against the pump.
     */
    private fun bindCandidate(pumpId: Long, percent: Int, requestedMinutes: Int?, rowStart: Long, serial: String, skew: Long): YpsoTbrAttempt? =
        journal.all()
            .filter {
                it.awaitsBinding && it.rowPumpId == null && it.pumpSerial == serial && it.percent == percent &&
                    (requestedMinutes == null || it.durationMinutes == requestedMinutes) &&
                    (it.baselinePumpId == null || it.baselinePumpId < pumpId) &&
                    rowStart >= checkNotNull(it.dispatchedAt) - skew &&
                    rowStart <= (it.effectiveBy ?: checkNotNull(it.effectiveAt)) + skew
            }
            .minByOrNull { checkNotNull(it.dispatchedAt) }

    private fun syncAttempt(attempt: YpsoTbrAttempt, pumpId: Long, pumpDuration: Long): String? {
        val start = checkNotNull(attempt.effectiveAt)
        // A confirmed AAPS stop already fixed this record's end; history never extends it.
        val requested = attempt.durationMinutes * MINUTE
        val duration = listOfNotNull(pumpDuration.coerceAtLeast(1L), requested, attempt.stoppedAt?.let { it - start })
            .min().coerceAtLeast(1L)
        val type = PumpSync.TemporaryBasalType.valueOf(attempt.type)
        if (attempt.pumpId == null) {
            // Binds the provisional record when it exists; otherwise the row itself is the evidence.
            val bound = pumpSync.syncTemporaryBasalWithTempId(
                start, attempt.percent.toDouble(), duration, false, attempt.temporaryId, type, pumpId, PumpType.YPSOPUMP, attempt.pumpSerial,
            )
            if (!bound) {
                pumpSync.syncTemporaryBasalWithPumpId(start, attempt.percent.toDouble(), duration, false, type, pumpId, PumpType.YPSOPUMP, attempt.pumpSerial)
            }
            val saved = lookup.byPumpId(pumpId, attempt.pumpSerial, start) ?: return "PumpSync did not bind temporary basal $pumpId"
            if (saved.valid) verify(pumpId, attempt.pumpSerial, start, duration)?.let { return it }
            journal.bound(attempt.id, pumpId)
            return null
        }
        return sync(pumpId, attempt.pumpSerial, start, duration) { at ->
            pumpSync.syncTemporaryBasalWithPumpId(at, attempt.percent.toDouble(), duration, false, type, pumpId, PumpType.YPSOPUMP, attempt.pumpSerial)
        }
    }

    /**
     * Writes a record under [pumpId] and reads it back. With [keepStart], a record already written
     * for this row keeps its start: a pump row's time depends on the offset measured on each read,
     * which differs by a second or two, or not at all once the clock was set since.
     */
    private fun sync(pumpId: Long, serial: String, start: Long, duration: Long, keepStart: Boolean = false, write: (start: Long) -> Unit): String? {
        val existing = lookup.byPumpId(pumpId, serial, start)
        if (existing != null && !existing.valid) return null
        val at = existing?.start?.takeIf { keepStart } ?: start
        if (existing != null && existing.start == at && existing.duration == duration) return null
        write(at)
        return verify(pumpId, serial, at, duration)
    }

    private fun verify(pumpId: Long, serial: String, start: Long, duration: Long): String? {
        val saved = lookup.byPumpId(pumpId, serial, start)
        return if (saved != null && (!saved.valid || saved.start == start && saved.duration == duration)) null
        else "PumpSync did not save temporary basal $pumpId"
    }

    /**
     * Stop and resume rows carry the pump's own time, so the zero-delivery window is not inferred.
     * A stop without a resume row stays recorded for one day, then lapses.
     */
    private fun pumpMode(change: YpsoPumpModeChange?, at: Long, pumpId: Long, serial: String, skew: Long): String? = when (change) {
        // Inserted once. A replayed row keeps whatever end its resume row or a status already gave it.
        YpsoPumpModeChange.STOPPED -> if (lookup.byPumpId(pumpId, serial, at) != null) null else {
            // Status records a stop when it first sees one. This row's record covers only the time
            // before that, so the two never overlap: counting the same zero basal twice would
            // understate IOB. If status saw a later stop instead, this stop's Resume row cuts it.
            // A status stop that ended before this Stop row is an earlier stop and says nothing here.
            val seenAt = lookup.statusSuspendsFrom(serial, at - skew).filter { it.start + it.duration > at }.minOfOrNull { it.start }
            val duration = seenAt?.let { it - at } ?: SUSPEND_WINDOW
            // A status stop already covering this row's time is this stop, recorded from status.
            if (duration <= 0L) null else {
                pumpSync.syncTemporaryBasalWithPumpId(
                    at, 0.0, duration, true, PumpSync.TemporaryBasalType.PUMP_SUSPEND, pumpId, PumpType.YPSOPUMP, serial,
                )
                if (lookup.byPumpId(pumpId, serial, at) != null) null else "PumpSync did not save pump stop $pumpId"
            }
        }
        // The resume row gives the exact end of the latest recorded stop, even one already cut short
        // by a status observation. A duration update keeps that record correctable.
        YpsoPumpModeChange.RESUMED -> {
            // Only the stop directly before this resume: one followed by any other record already ended.
            // It may be a stop only status saw, whose Stop row history has not bound.
            lookup.latestSuspendBefore(serial, at)?.takeIf { !lookup.anyStartedBetween(serial, it.start, at, it.pumpId ?: NO_ID) }?.let { stop ->
                val duration = (at - stop.start).coerceAtLeast(1L)
                if (stop.valid && stop.duration != duration) {
                    val type = PumpSync.TemporaryBasalType.PUMP_SUSPEND
                    if (stop.pumpId != null) {
                        pumpSync.syncTemporaryBasalWithPumpId(stop.start, 0.0, duration, true, type, stop.pumpId, PumpType.YPSOPUMP, serial)
                    } else if (stop.temporaryId != null) {
                        pumpSync.syncTemporaryBasalWithTempId(stop.start, 0.0, duration, true, stop.temporaryId, type, null, PumpType.YPSOPUMP, serial)
                    }
                }
            }
            if (lookup.suspendActiveAt(serial, at)) "pump resume $pumpId did not end the recorded stop" else null
        }
        null -> null
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val SUSPEND_WINDOW = 24 * 60 * MINUTE
        /** Profile reads reject pump clock drift above 30 s; this also covers the pump's whole-second rows. */
        private const val CLOCK_SKEW = 90_000L
        /** A row time corrected by the offset measured on its read: whole seconds plus read latency. */
        private const val MEASURED_SKEW = 15_000L
        private const val CLOCK_WAIT = "waiting for a pump clock reading"

        /** Whether [apply] held a row only because the pump clock could not be used for it yet. */
        fun isClockWait(reason: String): Boolean = reason.endsWith(CLOCK_WAIT)
        private const val NO_ID = Long.MIN_VALUE
        private val TBR_KINDS = setOf(
            YpsoHistoryKind.TEMP_BASAL_STARTED,
            YpsoHistoryKind.TEMP_BASAL_COMPLETED,
            YpsoHistoryKind.TEMP_BASAL_CANCELLED,
            YpsoHistoryKind.TEMP_BASAL_TERMINAL_UNRESOLVED,
        )
    }
}
