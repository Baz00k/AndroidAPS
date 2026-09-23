package app.aaps.pump.ypsopump.bolus

import app.aaps.pump.ypsopump.history.YpsoBolusPumpIdentity
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
        val expectedPumpId = YpsoBolusPumpIdentity.of(attempt.baseline.historyPumpId, sequence)
            ?: return YpsoExtendedBolusReconciliation.Unresolved(YpsoExtendedBolusReconciliation.Reason.NO_COMPATIBLE_HISTORY)
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
        val amount = Math.round(requireNotNull(event.semantics.amountUnits) * 100.0).toInt()
        if (amount !in 0..attempt.requestedCentiUnits) {
            return YpsoExtendedBolusReconciliation.Unresolved(YpsoExtendedBolusReconciliation.Reason.HISTORY_AMOUNT_INVALID)
        }
        val partial = amount < attempt.requestedCentiUnits
        val shapeMatches = when (attempt.shape) {
            // A pump-side or AAPS-side cancellation can rewrite the terminal duration while preserving
            // the proven slow sequence and reporting an authoritative partial amount. Full completion
            // still requires the programmed duration so a malformed same-sequence row fails closed.
            YpsoBolusShape.EXTENDED ->
                partial || attempt.cancelRequestId != null || event.semantics.durationMinutes == attempt.durationMinutes
            YpsoBolusShape.COMBINED ->
                event.entry.value2 == attempt.immediateCentiUnits &&
                    (partial || attempt.cancelRequestId != null || event.entry.value3 == attempt.durationMinutes)
            YpsoBolusShape.IMMEDIATE -> false
        }
        if (!shapeMatches) return YpsoExtendedBolusReconciliation.Unresolved(YpsoExtendedBolusReconciliation.Reason.HISTORY_SHAPE_MISMATCH)
        return YpsoExtendedBolusReconciliation.AttemptCompleted(event, amount)
    }
}
