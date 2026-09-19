package app.aaps.pump.ypsopump.bolus

import app.aaps.pump.ypsopump.history.YpsoHistoryEvent
import app.aaps.pump.ypsopump.history.YpsoHistoryKind

sealed interface YpsoExtendedBolusReconciliation {
    data class AttemptCompleted(val event: YpsoHistoryEvent, val amountCentiUnits: Int) : YpsoExtendedBolusReconciliation
    data class Unresolved(val reason: Reason) : YpsoExtendedBolusReconciliation

    enum class Reason { NO_COMPATIBLE_HISTORY, MULTIPLE_COMPATIBLE_HISTORY, HISTORY_SHAPE_MISMATCH, HISTORY_AMOUNT_INVALID }
}

/** Links a terminal square/combination history row only to the already proven slow-block sequence. */
object YpsoExtendedBolusReconciler {
    fun reconcile(attempt: YpsoBolusAttempt, newerEvents: List<YpsoHistoryEvent>): YpsoExtendedBolusReconciliation {
        require(attempt.shape != YpsoBolusShape.IMMEDIATE)
        val sequence = attempt.pumpSlowSequence
            ?: return YpsoExtendedBolusReconciliation.Unresolved(YpsoExtendedBolusReconciliation.Reason.NO_COMPATIBLE_HISTORY)
        val kind = when (attempt.shape) {
            YpsoBolusShape.EXTENDED -> YpsoHistoryKind.DELAYED_BOLUS_COMPLETED
            YpsoBolusShape.COMBINED -> YpsoHistoryKind.COMBINED_BOLUS_COMPLETED
            YpsoBolusShape.IMMEDIATE -> error("unreachable")
        }
        val expectedPumpId = historyPumpId(attempt.baseline.historyPumpId, sequence)
        val sameIdentityTerminals = newerEvents.filter {
            it.identity.aapsPumpId == expectedPumpId && it.semantics.kind in setOf(
                YpsoHistoryKind.DELAYED_BOLUS_COMPLETED,
                YpsoHistoryKind.COMBINED_BOLUS_COMPLETED,
            )
        }
        if (sameIdentityTerminals.any { it.semantics.kind != kind }) {
            return YpsoExtendedBolusReconciliation.Unresolved(YpsoExtendedBolusReconciliation.Reason.HISTORY_SHAPE_MISMATCH)
        }
        val compatible = sameIdentityTerminals.filter { it.semantics.kind == kind }
        if (compatible.isEmpty()) return YpsoExtendedBolusReconciliation.Unresolved(YpsoExtendedBolusReconciliation.Reason.NO_COMPATIBLE_HISTORY)
        if (compatible.size > 1) return YpsoExtendedBolusReconciliation.Unresolved(YpsoExtendedBolusReconciliation.Reason.MULTIPLE_COMPATIBLE_HISTORY)
        val event = compatible.single()
        val shapeMatches = when (attempt.shape) {
            YpsoBolusShape.EXTENDED -> event.semantics.durationMinutes == attempt.durationMinutes
            YpsoBolusShape.COMBINED ->
                event.entry.value2 == attempt.immediateCentiUnits && event.entry.value3 == attempt.durationMinutes
            YpsoBolusShape.IMMEDIATE -> false
        }
        if (!shapeMatches) return YpsoExtendedBolusReconciliation.Unresolved(YpsoExtendedBolusReconciliation.Reason.HISTORY_SHAPE_MISMATCH)
        val amount = Math.round(requireNotNull(event.semantics.amountUnits) * 100.0).toInt()
        if (amount !in 0..attempt.requestedCentiUnits) {
            return YpsoExtendedBolusReconciliation.Unresolved(YpsoExtendedBolusReconciliation.Reason.HISTORY_AMOUNT_INVALID)
        }
        return YpsoExtendedBolusReconciliation.AttemptCompleted(event, amount)
    }

    private fun historyPumpId(baselinePumpId: Long, sequence: Long): Long {
        var generation = baselinePumpId ushr 32
        if (sequence < (baselinePumpId and 0xffffffffL)) generation++
        return (generation shl 32) or sequence
    }
}
