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
    /** Start and duration of the valid record with this pump ID starting at [start], or null. */
    fun byPumpId(pumpId: Long, pumpSerial: String, start: Long): Pair<Long, Long>?

    /** Whether any valid YpsoPump PUMP_SUSPEND record is still active at [at]. */
    fun suspendActiveAt(pumpSerial: String, at: Long): Boolean
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
) {
    /** Applies one reconciled history event; returns a blocking reason or null when applied. */
    fun apply(event: YpsoHistoryEvent, pumpSerial: String, zone: ZoneId): String? {
        val kind = event.semantics.kind
        val tbr = kind in TBR_KINDS
        val mode = kind == YpsoHistoryKind.PUMP_MODE_CHANGED
        if (!tbr && !mode) return null
        val start = (YpsoPumpLocalTime.resolve(event.entry.factorySeconds, zone) as? YpsoPumpLocalTime.Resolution.Resolved)
            ?.instant?.toEpochMilli() ?: return "TBR or pump-mode timestamp is ambiguous"
        val pumpId = event.identity.aapsPumpId
        if (mode) return pumpMode(event.semantics.modeChange, start, pumpId, pumpSerial)
        val percent = event.entry.value1
        // Type 9 carries requested minutes; type 10 carries elapsed whole minutes, equal to the request
        // after natural expiry. Floored minutes keep a cancelled TBR within the minute the pump reports.
        val running = kind == YpsoHistoryKind.TEMP_BASAL_STARTED
        val minutes = event.entry.value2
        val attempt = journal.boundTo(pumpId) ?: when (val candidate = bindCandidate(pumpId, percent, if (running) minutes else null, start, pumpSerial)) {
            Candidate.Ambiguous -> return "TBR row $pumpId matches more than one AAPS start"
            Candidate.None -> null
            is Candidate.One -> candidate.attempt
        }
        if (attempt != null) return syncAttempt(attempt, pumpId, minutes * MINUTE)
        val duration = (minutes * MINUTE).coerceAtLeast(1L)
        return sync(pumpId, pumpSerial, start, duration) {
            pumpSync.syncTemporaryBasalWithPumpId(
                start, percent.toDouble(), duration, false, PumpSync.TemporaryBasalType.NORMAL, pumpId, PumpType.YPSOPUMP, pumpSerial,
            )
        }
    }

    private sealed interface Candidate {
        data object None : Candidate
        data object Ambiguous : Candidate
        data class One(val attempt: YpsoTbrAttempt) : Candidate
    }

    /**
     * The AAPS start that this row is. The pump was proven idle right before each start was sent, so
     * its row began inside [dispatch, latest effect], widened only by pump/phone clock skew. Rows older
     * than the attempt's history baseline are never its own; more than one match blocks attribution.
     */
    private fun bindCandidate(pumpId: Long, percent: Int, requestedMinutes: Int?, pumpStart: Long, serial: String): Candidate {
        val matches = journal.all().filter {
            it.awaitsBinding && it.pumpSerial == serial && it.percent == percent &&
                (requestedMinutes == null || it.durationMinutes == requestedMinutes) &&
                (it.baselinePumpId == null || it.baselinePumpId < pumpId) &&
                pumpStart >= checkNotNull(it.dispatchedAt) - CLOCK_SKEW &&
                pumpStart <= (it.effectiveBy ?: checkNotNull(it.effectiveAt)) + CLOCK_SKEW
        }
        return when (matches.size) {
            0 -> Candidate.None
            1 -> Candidate.One(matches.single())
            else -> Candidate.Ambiguous
        }
    }

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
            if (lookup.byPumpId(pumpId, attempt.pumpSerial, start) == null) return "PumpSync did not bind temporary basal $pumpId"
            // A row read while running can be rewritten later; its duration is corrected then.
            verify(pumpId, attempt.pumpSerial, start, duration)?.let { return it }
            journal.bound(attempt.id, pumpId)
            return null
        }
        return sync(pumpId, attempt.pumpSerial, start, duration) {
            pumpSync.syncTemporaryBasalWithPumpId(start, attempt.percent.toDouble(), duration, false, type, pumpId, PumpType.YPSOPUMP, attempt.pumpSerial)
        }
    }

    private fun sync(pumpId: Long, serial: String, start: Long, duration: Long, write: () -> Unit): String? {
        if (lookup.byPumpId(pumpId, serial, start) == start to duration) return null
        write()
        return verify(pumpId, serial, start, duration)
    }

    private fun verify(pumpId: Long, serial: String, start: Long, duration: Long): String? =
        if (lookup.byPumpId(pumpId, serial, start) == start to duration) null
        else "PumpSync did not save temporary basal $pumpId"

    /**
     * Stop and resume rows carry the pump's own time, so the zero-delivery window is not inferred.
     * A stop without a resume row stays recorded for one day, then lapses.
     */
    private fun pumpMode(change: YpsoPumpModeChange?, at: Long, pumpId: Long, serial: String): String? = when (change) {
        // Inserted once. A replayed row keeps whatever end its resume row or a status already gave it.
        YpsoPumpModeChange.STOPPED -> if (lookup.byPumpId(pumpId, serial, at) != null) null else {
            pumpSync.syncTemporaryBasalWithPumpId(
                at, 0.0, SUSPEND_WINDOW, true, PumpSync.TemporaryBasalType.PUMP_SUSPEND, pumpId, PumpType.YPSOPUMP, serial,
            )
            if (lookup.byPumpId(pumpId, serial, at) != null) null else "PumpSync did not save pump stop $pumpId"
        }
        // Ends the running suspend at the pump's resume time; a repeated row is a no-op by end ID.
        YpsoPumpModeChange.RESUMED -> {
            pumpSync.syncStopTemporaryBasalWithPumpId(at, pumpId, PumpType.YPSOPUMP, serial)
            if (lookup.suspendActiveAt(serial, at)) "pump resume $pumpId did not end the recorded stop" else null
        }
        null -> null
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val SUSPEND_WINDOW = 24 * 60 * MINUTE
        /** Profile reads reject pump clock drift above 30 s; this also covers the pump's whole-second rows. */
        private const val CLOCK_SKEW = 90_000L
        private val TBR_KINDS = setOf(
            YpsoHistoryKind.TEMP_BASAL_STARTED,
            YpsoHistoryKind.TEMP_BASAL_COMPLETED,
            YpsoHistoryKind.TEMP_BASAL_CANCELLED,
            YpsoHistoryKind.TEMP_BASAL_TERMINAL_UNRESOLVED,
        )
    }
}
