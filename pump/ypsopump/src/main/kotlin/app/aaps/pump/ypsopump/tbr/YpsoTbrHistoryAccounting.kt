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

    /**
     * Pump IDs of pump-row TBR records (not stops) with [percent] and a pump ID in ([after], [before])
     * that were still running at [runningAt].
     */
    fun tbrRowsBetween(pumpSerial: String, percent: Int, after: Long, before: Long, runningAt: Long): List<Long>
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
    /** Fresh pump status, or null when none is current. Proves which TBR the pump runs now. */
    private val observation: () -> YpsoTbrObservation? = { null },
    /** AAPS accepts pump records only from this pump's registration on; older rows are not its to import. */
    private val registeredAt: (pumpSerial: String) -> Long = { 0L },
) {
    /**
     * Applies one reconciled history event; returns a blocking reason or null when applied. [readAt]
     * is when the pump read of this event's snapshot began.
     */
    fun apply(event: YpsoHistoryEvent, pumpSerial: String, zone: ZoneId, readAt: Long = Long.MAX_VALUE): String? {
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
        // An AAPS start matched by row identity is timed by AAPS, so it needs no pump wall-clock time.
        var ambiguousRunning = false
        if (tbr) {
            val match = if (running) runningStart(pumpId, percent, minutes, pumpSerial, readAt) else null
            match?.hold?.let { return it }
            ambiguousRunning = match?.ambiguous == true
            val known = journal.boundTo(pumpId) ?: journal.identifiedAs(pumpId) ?: match?.attempt
            if (known != null) return syncAttempt(known, pumpId, minutes * MINUTE)
        }
        val start = (YpsoPumpLocalTime.resolve(event.entry.factorySeconds, zone) as? YpsoPumpLocalTime.Resolution.Resolved)
            ?.instant?.toEpochMilli() ?: return "TBR or pump-mode timestamp is ambiguous"
        // PumpSync refuses records from before this pump was registered; the pump's history reaches
        // further back than AAPS' use of it. Such a row was never AAPS's, so it is passed over. An
        // AAPS start's row may still read slightly earlier through clock skew, so starts bind first.
        val preRegistration = start < registeredAt(pumpSerial)
        if (mode) return if (preRegistration) null else pumpMode(event.semantics.modeChange, start, pumpId, pumpSerial)
        // A running row that status could not match falls back to the time window only when another
        // same-percent pump row lies between it and a start; otherwise status alone decides.
        val attempt = if (running) bindCandidate(pumpId, percent, minutes, start, pumpSerial).takeIf { ambiguousRunning }
        else bindCandidate(pumpId, percent, null, start, pumpSerial)
        if (attempt != null) return syncAttempt(attempt, pumpId, minutes * MINUTE)
        if (preRegistration) return null
        // A start whose outcome status has not proven yet may be this very row: importing it now would
        // record the same TBR twice. Hold until status settles it.
        journal.all().firstOrNull {
            it.kind == YpsoTbrAttempt.Kind.START && it.pumpSerial == pumpSerial && it.percent == percent &&
                (it.baselinePumpId == null || it.baselinePumpId < pumpId) && it.awaitsStatus && it.dispatchedAt != null
        }?.let { return "TBR row $pumpId may belong to AAPS start ${it.id}, which status has not resolved yet" }
        val duration = (minutes * MINUTE).coerceAtLeast(1L)
        return sync(pumpId, pumpSerial, start, duration) {
            pumpSync.syncTemporaryBasalWithPumpId(
                start, percent.toDouble(), duration, false, PumpSync.TemporaryBasalType.NORMAL, pumpId, PumpType.YPSOPUMP, pumpSerial,
            )
        }
    }

    private class RunningMatch(val attempt: YpsoTbrAttempt? = null, val hold: String? = null, val ambiguous: Boolean = false)

    /**
     * The AAPS start this running row is, proven by status rather than by the pump's clock. The pump
     * runs one TBR at a time and keeps only that TBR's row running (type 9). A start that took effect
     * before this history read began, and that status read at most [STATUS_BEFORE_READ] before the
     * read still shows running, was the TBR running while the read saw this row.
     * When the evidence is not there yet (status is stale or predates a start, or an AAPS stop came
     * after the read began) the row waits: importing it could record the same TBR twice.
     */
    private fun runningStart(pumpId: Long, percent: Int, minutes: Int, serial: String, readAt: Long): RunningMatch {
        val candidates = journal.all().filter {
            it.awaitsBinding && it.rowPumpId == null && it.pumpSerial == serial &&
                it.percent == percent && it.durationMinutes == minutes && (it.baselinePumpId == null || it.baselinePumpId < pumpId)
        }
        if (journal.boundTo(pumpId) != null || journal.identifiedAs(pumpId) != null) return RunningMatch()
        if (candidates.isEmpty()) return RunningMatch()
        // Status shows only what runs now, not which row began it. If another same-percent pump row,
        // still running once a start took effect, lies between that start and this row, the start may
        // have been cancelled and set again on the pump. Status cannot tell them apart; only the time
        // window may bind then.
        if (candidates.any {
                lookup.tbrRowsBetween(serial, percent, it.baselinePumpId ?: Long.MIN_VALUE, pumpId, checkNotNull(it.effectiveAt) - CLOCK_SKEW)
                    .any { id -> journal.boundTo(id) == null }
            }) {
            return RunningMatch(ambiguous = true)
        }
        val hold = RunningMatch(hold = "TBR row $pumpId awaits status matching it to an AAPS start")
        val status = observation()?.takeIf { it.observedAt >= readAt - STATUS_BEFORE_READ } ?: return hold
        // A start stopped by AAPS after the read began may be the row the read saw running.
        if (candidates.any { it.stoppedAt != null && it.stoppedAt >= readAt - STATUS_BEFORE_READ }) return hold
        val live = candidates.filter { it.stoppedAt == null }
        // The start must have run before the read began, so the row the read saw can be its row, and
        // before the status, which otherwise says nothing about it.
        if (live.any { checkNotNull(it.effectiveBy ?: it.effectiveAt) > minOf(readAt, status.observedAt) }) return hold
        val match = live.filter { it.runningPer(status) }.maxByOrNull { checkNotNull(it.effectiveAt) }
        // Status taken during the read may postdate this row: the start may have ended since.
        if (match == null && status.observedAt > readAt) return hold
        return RunningMatch(attempt = match)
    }

    /**
     * Fallback for a start whose row history saw only after it ended. The pump was proven
     * idle right before each start was sent, so its row began inside [dispatch, latest effect],
     * widened by pump/phone clock skew. Rows older than the attempt's history baseline are never its
     * own. Rows arrive oldest first, so of several windows the earliest-dispatched attempt is this
     * row's. A start that fits no row keeps its own record, which status reconciles against the pump.
     */
    private fun bindCandidate(pumpId: Long, percent: Int, requestedMinutes: Int?, pumpStart: Long, serial: String): YpsoTbrAttempt? =
        journal.all()
            .filter {
                it.awaitsBinding && it.rowPumpId == null && it.pumpSerial == serial && it.percent == percent &&
                    (requestedMinutes == null || it.durationMinutes == requestedMinutes) &&
                    (it.baselinePumpId == null || it.baselinePumpId < pumpId) &&
                    pumpStart >= checkNotNull(it.dispatchedAt) - CLOCK_SKEW &&
                    pumpStart <= (it.effectiveBy ?: checkNotNull(it.effectiveAt)) + CLOCK_SKEW
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
        return sync(pumpId, attempt.pumpSerial, start, duration) {
            pumpSync.syncTemporaryBasalWithPumpId(start, attempt.percent.toDouble(), duration, false, type, pumpId, PumpType.YPSOPUMP, attempt.pumpSerial)
        }
    }

    private fun sync(pumpId: Long, serial: String, start: Long, duration: Long, write: () -> Unit): String? {
        val existing = lookup.byPumpId(pumpId, serial, start)
        if (existing != null && (!existing.valid || existing.start == start && existing.duration == duration)) return null
        write()
        return verify(pumpId, serial, start, duration)
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
    private fun pumpMode(change: YpsoPumpModeChange?, at: Long, pumpId: Long, serial: String): String? = when (change) {
        // Inserted once. A replayed row keeps whatever end its resume row or a status already gave it.
        YpsoPumpModeChange.STOPPED -> if (lookup.byPumpId(pumpId, serial, at) != null) null else {
            // Status records a stop when it first sees one. This row's record covers only the time
            // before that, so the two never overlap: counting the same zero basal twice would
            // understate IOB. If status saw a later stop instead, this stop's Resume row cuts it.
            // A status stop that ended before this Stop row is an earlier stop and says nothing here.
            val seenAt = lookup.statusSuspendsFrom(serial, at - CLOCK_SKEW).filter { it.start + it.duration > at }.minOfOrNull { it.start }
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
        /** Status this much older than the history read still describes the TBR the read saw. */
        private const val STATUS_BEFORE_READ = 2 * 60_000L
        private const val NO_ID = Long.MIN_VALUE
        private val TBR_KINDS = setOf(
            YpsoHistoryKind.TEMP_BASAL_STARTED,
            YpsoHistoryKind.TEMP_BASAL_COMPLETED,
            YpsoHistoryKind.TEMP_BASAL_CANCELLED,
            YpsoHistoryKind.TEMP_BASAL_TERMINAL_UNRESOLVED,
        )
    }
}
