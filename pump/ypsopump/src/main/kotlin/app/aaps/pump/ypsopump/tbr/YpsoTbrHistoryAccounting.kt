package app.aaps.pump.ypsopump.tbr

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.ypsopump.history.YpsoHistoryEvent
import app.aaps.pump.ypsopump.history.YpsoHistoryKind
import app.aaps.pump.ypsopump.history.YpsoPumpLocalTime
import app.aaps.pump.ypsopump.history.YpsoPumpModeChange
import java.time.ZoneId
import kotlin.math.abs

/** Read-back of temporary basal records. */
interface YpsoTbrRecordLookup {
    /** [temporaryId] is set when the record began as an AAPS start. */
    data class Record(val start: Long, val duration: Long, val valid: Boolean, val temporaryId: Long? = null)

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

    /** PUMP_SUSPEND records from Stop rows starting exactly at [at], including removed ones. */
    fun rowSuspendsAt(pumpSerial: String, at: Long): List<Suspend>

    /** A percent TBR AAPS started, recorded under its temporary ID and bound to no history row yet. */
    data class UnboundStart(val temporaryId: Long, val start: Long, val duration: Long, val percent: Double)

    /** Unbound AAPS starts beginning from [from] to [to], including removed ones. */
    fun unboundStarts(pumpSerial: String, from: Long, to: Long): List<UnboundStart>
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
     * [scanRows] are all rows of that read, each with the clock offset that holds for it.
     */
    fun apply(
        event: YpsoHistoryEvent,
        pumpSerial: String,
        zone: ZoneId,
        pumpClockOffsetMs: Long? = null,
        waitForClock: Boolean = false,
        scanRows: List<Pair<YpsoHistoryEvent, Long?>> = emptyList(),
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
        // On the phone's clock. With the offset measured on this read, a row's time is exact to a few
        // seconds; without it, only the drift profile reads allow bounds it.
        val starts = rowStarts(event, zone, pumpClockOffsetMs)
        val skew = skew(pumpClockOffsetMs)
        // A row bound to an AAPS start keeps that start's time, also once the journal has dropped it.
        val carried = if (tbr) starts.minOrNull()?.let { lookup.byPumpId(pumpId, pumpSerial, it) } else null
        carried?.temporaryId?.let { temporaryId ->
            // Bound before the journal recorded it, as when the app stopped in between.
            journal.all().firstOrNull { it.awaitsBinding && it.temporaryId == temporaryId }?.let { return syncAttempt(it, pumpId, minutes * MINUTE) }
            return syncRecord(temporaryId, pumpId, pumpSerial, carried.start, carried.duration, percent, minutes * MINUTE)
        }
        // Starts the journal evicted while history stalled are known only by their records. Such a record
        // is this row's only when no other row of the read could be its own; a row that ended before
        // registration is passed over below and takes none.
        val evicted = if (tbr && carried == null && !endedBeforeRegistration(event, pumpSerial, starts)) {
            evictedStarts(percent, if (running) minutes else null, starts, pumpSerial, skew)
        } else emptyList()
        val rivals = scanRows.filter { (row, _) -> row.identity.aapsPumpId != pumpId }
        val contested = evicted.filter { record -> rivals.any { (row, offset) -> couldBeRowOf(record, row, pumpSerial, zone, offset) } }
        // A wall time in the repeated hour of a DST fall-back names two instants an hour apart. Such a
        // row is an AAPS start's own only when that start's window holds one of them and no other row of
        // this read could be the start's: another start an hour away, evicted from the journal or set on
        // the pump, would carry the same wall time. AAPS sends no TBR while history is read, and a start
        // whose outcome is still open holds the row, so a start's own row is in this read. The bound
        // record is timed by AAPS. A row nothing places could be either, and importing it at a guess
        // would misplace its basal.
        if (tbr && starts.size > 1) {
            awaitingStatus(pumpSerial, percent, pumpId)?.let { return awaitingStatusReason(pumpId, it) }
            val owners = starts.mapNotNull { bindCandidate(pumpId, percent, if (running) minutes else null, it, pumpSerial, skew) }.distinct()
            val owner = owners.singleOrNull()
            if (owner != null && evicted.isEmpty() && rivals.none { (row, offset) -> couldBeRowOf(owner, row, zone, offset) }) {
                return syncAttempt(owner, pumpId, minutes * MINUTE)
            }
            val record = evicted.singleOrNull()
            if (owners.isEmpty() && record != null && contested.isEmpty()) {
                return syncRecord(record.temporaryId, pumpId, pumpSerial, record.start, record.duration, percent, minutes * MINUTE)
            }
        }
        val start = starts.singleOrNull() ?: return "TBR or pump-mode timestamp is ambiguous"
        if (tbr) {
            val owner = bindCandidate(pumpId, percent, if (running) minutes else null, start, pumpSerial, skew)
            // Its own row or an evicted start's: nothing tells which, and binding either at a guess could
            // leave the other start's row to be imported beside its record.
            if (evicted.isNotEmpty() && (owner != null || evicted.size > 1)) {
                return "TBR row $pumpId may belong to any of ${evicted.size + listOfNotNull(owner).size} AAPS starts"
            }
            owner?.let { return syncAttempt(it, pumpId, minutes * MINUTE) }
            evicted.singleOrNull()?.let { record ->
                awaitingStatus(pumpSerial, percent, pumpId)?.let { return awaitingStatusReason(pumpId, it) }
                // Rows arrive oldest first, but a TBR set on the pump just before the AAPS start can be
                // older than the start's own row; binding the first would leave the other imported beside it.
                if (contested.isNotEmpty()) return "TBR row $pumpId and another row of this read may both belong to one AAPS start"
                return syncRecord(record.temporaryId, pumpId, pumpSerial, record.start, record.duration, percent, minutes * MINUTE)
            }
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
        // further back than AAPS' use of it. Basal delivered before then is not AAPS's to count, like
        // any earlier delivery; a TBR or stop still running at registration counts from then on. AAPS
        // starts, whose rows may read slightly earlier through clock skew, were matched above.
        val registered = registeredAt(pumpSerial)
        val from = maxOf(start, registered)
        if (mode) {
            val change = event.semantics.modeChange
            if (start <= registered) when (change) {
                // A stop without its resume lapses a day after the pump stopped.
                YpsoPumpModeChange.STOPPED -> if (start + SUSPEND_WINDOW <= registered) return null
                // The stop before it ended by registration too: nothing of it is AAPS's to count.
                YpsoPumpModeChange.RESUMED -> return dropStopFrom(registered, pumpId, pumpSerial)
                null -> return null
            }
            return pumpMode(change, from, pumpId, pumpSerial, skew, lapseAt = start + SUSPEND_WINDOW)
        }
        val until = start + minutes * MINUTE
        // Ended before registration. A record already written (from its running row) is ended there.
        if (until <= registered && lookup.byPumpId(pumpId, pumpSerial, from) == null) return null
        // A start whose outcome status has not proven yet may be this very row: importing it now would
        // record the same TBR twice. Hold until status settles it.
        awaitingStatus(pumpSerial, percent, pumpId)?.let { return awaitingStatusReason(pumpId, it) }
        val duration = (until - from).coerceAtLeast(1L)
        return sync(pumpId, pumpSerial, from, duration, keepStart = true) { at ->
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
                    (it.baselinePumpId == null || it.baselinePumpId < pumpId) && it.windowHolds(rowStart, skew)
            }
            .minByOrNull { checkNotNull(it.dispatchedAt) }

    private fun YpsoTbrAttempt.windowHolds(rowStart: Long, skew: Long) =
        rowStart >= checkNotNull(dispatchedAt) - skew && rowStart <= (effectiveBy ?: checkNotNull(effectiveAt)) + skew

    /**
     * Whether binding would take [row], a TBR no AAPS start owns yet, for [attempt]'s at any time its
     * wall time names, read with the clock [offset] that holds for it. Its duration is left out, so this
     * errs towards a rival.
     */
    private fun couldBeRowOf(attempt: YpsoTbrAttempt, row: YpsoHistoryEvent, zone: ZoneId, offset: Long?): Boolean {
        val starts = rowStarts(row, zone, offset)
        return row.semantics.kind in TBR_KINDS && row.entry.value1 == attempt.percent &&
            (attempt.baselinePumpId == null || attempt.baselinePumpId < row.identity.aapsPumpId) &&
            starts.any { attempt.windowHolds(it, skew(offset)) } && unowned(row, attempt.pumpSerial, starts)
    }

    /** No AAPS start or record has taken [row] yet: a journalled start, or a record already carrying it. */
    private fun unowned(row: YpsoHistoryEvent, serial: String, starts: List<Long>): Boolean {
        val rowPumpId = row.identity.aapsPumpId
        return journal.boundTo(rowPumpId) == null && journal.identifiedAs(rowPumpId) == null && journal.unmatchedAs(rowPumpId) == null &&
            starts.minOrNull()?.let { lookup.byPumpId(rowPumpId, serial, it) } == null
    }

    /**
     * AAPS starts this row may be, known only by their records: the journal keeps a bounded number of
     * attempts, and while history stalls the oldest starts are evicted before history reaches their rows.
     * A start's record begins when its command took effect: at the pump's acknowledgement, or at dispatch
     * when that was lost. Either way the pump began it within a command's time of that start, so its row
     * lies within that time and [skew] of it. Records of starts still journalled are the journal's to bind.
     */
    private fun evictedStarts(percent: Int, requestedMinutes: Int?, rowStarts: List<Long>, serial: String, skew: Long): List<YpsoTbrRecordLookup.UnboundStart> {
        if (rowStarts.isEmpty()) return emptyList()
        val journalled = journal.all().filter { it.kind == YpsoTbrAttempt.Kind.START }.map { it.temporaryId }.toSet()
        val span = skew + COMMAND_SPAN
        return lookup.unboundStarts(serial, rowStarts.min() - span, rowStarts.max() + span).filter { record ->
            record.temporaryId !in journalled && record.percent == percent.toDouble() &&
                // A record never outlasts its request, which a running row still carries.
                (requestedMinutes == null || record.duration <= requestedMinutes * MINUTE) &&
                rowStarts.any { record.windowHolds(it, skew) }
        }
    }

    private fun YpsoTbrRecordLookup.UnboundStart.windowHolds(rowStart: Long, skew: Long) = abs(rowStart - start) <= skew + COMMAND_SPAN

    /** As [couldBeRowOf], for a start known only by its [record]. */
    private fun couldBeRowOf(record: YpsoTbrRecordLookup.UnboundStart, row: YpsoHistoryEvent, serial: String, zone: ZoneId, offset: Long?): Boolean {
        val starts = rowStarts(row, zone, offset)
        return row.semantics.kind in TBR_KINDS && row.entry.value1.toDouble() == record.percent &&
            starts.any { record.windowHolds(it, skew(offset)) } && unowned(row, serial, starts) && !endedBeforeRegistration(row, serial, starts)
    }

    /**
     * A TBR row that ended before the pump was registered, at any time its wall time names. [apply]
     * passes it over when no record carries it yet, so it can be no AAPS start's own.
     */
    private fun endedBeforeRegistration(row: YpsoHistoryEvent, serial: String, starts: List<Long>): Boolean {
        val latest = starts.maxOrNull() ?: return false
        return latest + row.entry.value2 * MINUTE <= registeredAt(serial) && lookup.byPumpId(row.identity.aapsPumpId, serial, starts.min()) == null
    }

    /**
     * Binds or updates the record AAPS wrote for a start under [temporaryId] as this row's. It keeps its
     * start and type. The pump's minutes can only shorten it: its [recorded] end is the request or a
     * confirmed AAPS stop, and a status that saw the TBR end cut it so that IOB errs high.
     */
    private fun syncRecord(temporaryId: Long, pumpId: Long, serial: String, start: Long, recorded: Long, percent: Int, pumpDuration: Long): String? {
        val duration = minOf(pumpDuration, recorded).coerceAtLeast(1L)
        val existing = lookup.byPumpId(pumpId, serial, start)
        if (existing != null && (!existing.valid || existing.duration == duration)) return null
        pumpSync.syncTemporaryBasalWithTempId(start, percent.toDouble(), duration, false, temporaryId, null, pumpId, PumpType.YPSOPUMP, serial)
        val saved = lookup.byPumpId(pumpId, serial, start) ?: return "PumpSync did not bind temporary basal $pumpId"
        return if (saved.valid) verify(pumpId, serial, start, duration) else null
    }

    /** On the phone's clock: every instant the row's wall time names, corrected by [offset]. */
    private fun rowStarts(row: YpsoHistoryEvent, zone: ZoneId, offset: Long?) =
        YpsoPumpLocalTime.candidates(row.entry.factorySeconds, zone).map { it - (offset ?: 0L) }

    private fun skew(offset: Long?) = if (offset != null) MEASURED_SKEW else CLOCK_SKEW

    /** A start with this row's percent whose outcome status has not proven yet. */
    private fun awaitingStatus(serial: String, percent: Int, pumpId: Long): YpsoTbrAttempt? = journal.all().firstOrNull {
        it.kind == YpsoTbrAttempt.Kind.START && it.pumpSerial == serial && it.percent == percent &&
            (it.baselinePumpId == null || it.baselinePumpId < pumpId) && it.awaitsStatus && it.dispatchedAt != null
    }

    private fun awaitingStatusReason(pumpId: Long, attempt: YpsoTbrAttempt) =
        "TBR row $pumpId may belong to AAPS start ${attempt.id}, which status has not resolved yet"

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
    /**
     * A Stop row before registration was recorded from registration on; a Resume also before it
     * leaves nothing of that stop after registration, so the record is removed. Every such stop
     * starts at registration, so the Resume's own Stop is the one directly before it in pump order.
     */
    private fun dropStopFrom(registered: Long, resumePumpId: Long, serial: String): String? {
        val stop = lookup.rowSuspendsAt(serial, registered)
            .filter { checkNotNull(it.pumpId) < resumePumpId }
            .maxByOrNull { checkNotNull(it.pumpId) }
            ?.takeIf { it.valid } ?: return null
        val pumpId = checkNotNull(stop.pumpId)
        pumpSync.invalidateTemporaryBasalWithPumpId(pumpId, PumpType.YPSOPUMP, serial)
        return if (lookup.byPumpId(pumpId, serial, stop.start)?.valid == false) null else "PumpSync did not remove pump stop $pumpId"
    }

    private fun pumpMode(
        change: YpsoPumpModeChange?,
        at: Long,
        pumpId: Long,
        serial: String,
        skew: Long,
        lapseAt: Long = at + SUSPEND_WINDOW,
    ): String? = when (change) {
        // Inserted once. A replayed row keeps whatever end its resume row or a status already gave it.
        YpsoPumpModeChange.STOPPED -> if (lookup.byPumpId(pumpId, serial, at) != null) null else {
            // Status records a stop when it first sees one. This row's record covers only the time
            // before that, so the two never overlap: counting the same zero basal twice would
            // understate IOB. If status saw a later stop instead, this stop's Resume row cuts it.
            // A status stop that ended before this Stop row is an earlier stop and says nothing here.
            val seenAt = lookup.statusSuspendsFrom(serial, at - skew).filter { it.start + it.duration > at }.minOfOrNull { it.start }
            val duration = minOf(seenAt ?: lapseAt, lapseAt) - at
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
        /** How long after dispatch a command can still take effect; see [YpsoTbrBleLink.WRITE_CALLBACK_TIMEOUT_S]. */
        private const val COMMAND_SPAN = YpsoTbrBleLink.WRITE_CALLBACK_TIMEOUT_S * 1_000L
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
