package app.aaps.pump.ypsopump.tbr

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.ypsopump.history.YpsoHistoryEvent
import app.aaps.pump.ypsopump.history.YpsoHistoryKind
import app.aaps.pump.ypsopump.history.YpsoPumpModeChange
import app.aaps.pump.ypsopump.history.YpsoPumpLocalTime
import java.time.ZoneId

/**
 * Basal accounting from pump history (see docs/history.md). A TBR row keeps its sequence while the
 * pump rewrites it from running (type 9) to ended (type 10), so the row identity is the PumpSync
 * pump ID for the whole life of the TBR. An AAPS-started TBR is already recorded under a temporary
 * ID; its row binds to that record instead of importing a duplicate. Every call is idempotent.
 */
class YpsoTbrHistoryAccounting(
    private val pumpSync: PumpSync,
    private val journal: YpsoTbrJournal,
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
        if (mode) return pumpMode(event, start, pumpId, pumpSerial)
        val percent = event.entry.value1
        return when (kind) {
            YpsoHistoryKind.TEMP_BASAL_STARTED -> running(pumpId, percent, event.entry.value2, start, pumpSerial)
            else -> ended(pumpId, percent, event.entry.value2, start, pumpSerial)
        }
    }

    private fun running(pumpId: Long, percent: Int, requestedMinutes: Int, pumpStart: Long, serial: String): String? {
        val bound = journal.boundTo(pumpId) ?: bindCandidate(pumpId, percent, requestedMinutes, serial)
        if (bound != null) return syncBound(bound, pumpId, bound.durationMinutes * MINUTE)
        if (requestedMinutes <= 0) return null
        pumpSync.syncTemporaryBasalWithPumpId(
            pumpStart, percent.toDouble(), requestedMinutes * MINUTE, false,
            PumpSync.TemporaryBasalType.NORMAL, pumpId, PumpType.YPSOPUMP, serial,
        )
        return null
    }

    /** A terminal row: value 2 is elapsed whole minutes, equal to the request after natural expiry. */
    private fun ended(pumpId: Long, percent: Int, elapsedMinutes: Int, pumpStart: Long, serial: String): String? {
        val bound = journal.boundTo(pumpId) ?: bindCandidate(pumpId, percent, null, serial)
        // Elapsed minutes are floored by the pump; the recorded end stays within the minute the pump reports.
        val elapsed = (elapsedMinutes * MINUTE).coerceAtLeast(1L)
        if (bound != null) return syncBound(bound, pumpId, minOf(elapsed, bound.durationMinutes * MINUTE))
        pumpSync.syncTemporaryBasalWithPumpId(
            pumpStart, percent.toDouble(), elapsed, false,
            PumpSync.TemporaryBasalType.NORMAL, pumpId, PumpType.YPSOPUMP, serial,
        )
        return null
    }

    /**
     * The only started, unbound AAPS attempt newer than its history baseline with this percent (and
     * requested duration, when the row still carries it). The pump runs one TBR at a time and the
     * controller confirmed this one by status, so a unique match is its row.
     */
    private fun bindCandidate(pumpId: Long, percent: Int, requestedMinutes: Int?, serial: String): YpsoTbrAttempt? {
        val candidates = journal.all().filter {
            it.awaitsBinding && it.pumpSerial == serial && it.percent == percent &&
                (requestedMinutes == null || it.durationMinutes == requestedMinutes) &&
                (it.baselinePumpId == null || it.baselinePumpId < pumpId)
        }
        return candidates.singleOrNull()
    }

    private fun syncBound(attempt: YpsoTbrAttempt, pumpId: Long, pumpDuration: Long): String? {
        val startedAt = checkNotNull(attempt.startedAt)
        // An AAPS stop already cut this record at a pump-confirmed time; history must not extend it.
        val duration = attempt.stoppedAt?.let { (it - startedAt).coerceIn(1L, pumpDuration.coerceAtLeast(1L)) } ?: pumpDuration
        val type = PumpSync.TemporaryBasalType.valueOf(attempt.type)
        if (attempt.pumpId == null) {
            val bound = pumpSync.syncTemporaryBasalWithTempId(
                startedAt, attempt.percent.toDouble(), duration, false, attempt.temporaryId, type, pumpId,
                PumpType.YPSOPUMP, attempt.pumpSerial,
            )
            if (!bound) {
                // AAPS never saved the provisional record; the pump row is the only evidence left.
                pumpSync.syncTemporaryBasalWithPumpId(
                    startedAt, attempt.percent.toDouble(), duration, false, type, pumpId, PumpType.YPSOPUMP, attempt.pumpSerial,
                )
            }
            journal.bound(attempt.id, pumpId)
            return null
        }
        if (attempt.stoppedAt == null) {
            pumpSync.syncTemporaryBasalWithPumpId(
                startedAt, attempt.percent.toDouble(), duration, false, type, pumpId, PumpType.YPSOPUMP, attempt.pumpSerial,
            )
        }
        return null
    }

    /** Stop and resume rows carry the pump's own time, so the zero-delivery window is not inferred. */
    private fun pumpMode(event: YpsoHistoryEvent, at: Long, pumpId: Long, serial: String): String? {
        when (event.semantics.modeChange) {
            YpsoPumpModeChange.STOPPED -> pumpSync.syncTemporaryBasalWithPumpId(
                at, 0.0, SUSPEND_WINDOW, true, PumpSync.TemporaryBasalType.PUMP_SUSPEND, pumpId, PumpType.YPSOPUMP, serial,
            )
            YpsoPumpModeChange.RESUMED -> pumpSync.syncStopTemporaryBasalWithPumpId(at, pumpId, PumpType.YPSOPUMP, serial)
            null -> Unit
        }
        return null
    }

    companion object {
        private const val MINUTE = 60_000L
        /** A stop without a resume row stays recorded for the pump's longest TBR, then lapses. */
        private const val SUSPEND_WINDOW = 24 * 60 * MINUTE
        private val TBR_KINDS = setOf(
            YpsoHistoryKind.TEMP_BASAL_STARTED,
            YpsoHistoryKind.TEMP_BASAL_COMPLETED,
            YpsoHistoryKind.TEMP_BASAL_CANCELLED,
            YpsoHistoryKind.TEMP_BASAL_TERMINAL_UNRESOLVED,
        )
    }
}
