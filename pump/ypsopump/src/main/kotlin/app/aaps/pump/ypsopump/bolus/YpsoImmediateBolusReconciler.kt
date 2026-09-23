package app.aaps.pump.ypsopump.bolus

import app.aaps.pump.ypsopump.history.YpsoHistoryEvent
import app.aaps.pump.ypsopump.history.YpsoHistoryKind
import app.aaps.pump.ypsopump.comm.commands.BolusCommand

data class YpsoImmediateBolusStatus(
    val fastSequence: Long,
    val statusCode: Int,
    val programmedCentiUnits: Int,
    val deliveredCentiUnits: Int,
) {
    init {
        require(fastSequence in 0..0xffffffffL)
        require(programmedCentiUnits in 0..BolusCommand.MAX_BOLUS_X100)
        require(deliveredCentiUnits in 0..programmedCentiUnits)
    }
}

sealed interface YpsoImmediateBolusReconciliation {
    /** Pump-confirmed insulin event. It is ingestible regardless of command origin. */
    data class ConfirmedInsulin(val event: YpsoHistoryEvent, val amountCentiUnits: Int) : YpsoImmediateBolusReconciliation
    /** The insulin event is also linked to this exact attempt through pump status identity. */
    data class AttemptCompleted(
        val event: YpsoHistoryEvent,
        val status: YpsoImmediateBolusStatus,
        val amountCentiUnits: Int,
    ) : YpsoImmediateBolusReconciliation
    data class Unresolved(val reason: Reason, val confirmedInsulin: ConfirmedInsulin? = null) : YpsoImmediateBolusReconciliation

    enum class Reason {
        NO_COMPATIBLE_HISTORY,
        MULTIPLE_COMPATIBLE_HISTORY,
        STATUS_SEQUENCE_NOT_NEWER,
        STATUS_AMOUNT_MISMATCH,
        HISTORY_STATUS_AMOUNT_MISMATCH,
        STATUS_IDENTITY_UNPROVEN,
        STATUS_IDENTITY_CHANGED,
    }
}

object YpsoImmediateBolusReconciler {
    fun reconcile(
        attempt: YpsoBolusAttempt,
        status: YpsoImmediateBolusStatus?,
        newerEvents: List<YpsoHistoryEvent>,
    ): YpsoImmediateBolusReconciliation {
        require(attempt.shape == YpsoBolusShape.IMMEDIATE) { "immediate reconciler cannot interpret other bolus shapes" }
        // Type 29 is identified as an aborted immediate bolus, but its amount field has not yet been
        // target-paired as delivered versus requested. It cannot create insulin until bench evidence
        // establishes that relationship.
        val confirmedEvents = newerEvents.filter {
            it.semantics.kind == YpsoHistoryKind.IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED
        }
        val compatible = attempt.pumpFastSequence?.let { provenSequence ->
            confirmedEvents.filter { it.identity.sequence == provenSequence }
        } ?: if (status == null) confirmedEvents else confirmedEvents.filter {
            Math.round(requireNotNull(it.semantics.amountUnits) * 100.0).toInt() == status.deliveredCentiUnits
        }
        if (compatible.isEmpty()) return YpsoImmediateBolusReconciliation.Unresolved(YpsoImmediateBolusReconciliation.Reason.NO_COMPATIBLE_HISTORY)
        if (compatible.size > 1) return YpsoImmediateBolusReconciliation.Unresolved(YpsoImmediateBolusReconciliation.Reason.MULTIPLE_COMPATIBLE_HISTORY)
        val event = compatible.single()
        val historyAmount = Math.round(requireNotNull(event.semantics.amountUnits) * 100.0).toInt()
        val confirmed = YpsoImmediateBolusReconciliation.ConfirmedInsulin(event, historyAmount)
        if (attempt.pumpFastSequence == null) {
            return YpsoImmediateBolusReconciliation.Unresolved(
                YpsoImmediateBolusReconciliation.Reason.STATUS_IDENTITY_UNPROVEN,
                confirmed,
            )
        }
        if (!strictlyNewer(attempt.pumpFastSequence, attempt.baseline.fastSequence)) {
            return YpsoImmediateBolusReconciliation.Unresolved(
                YpsoImmediateBolusReconciliation.Reason.STATUS_SEQUENCE_NOT_NEWER,
                confirmed,
            )
        }
        // A type-2 history row is itself pump-originated terminal evidence. Once the command was
        // already bound to this exact fast sequence, a lost follow-up status read must not discard
        // the terminal event or advance history past recoverable evidence.
        if (status == null) return YpsoImmediateBolusReconciliation.AttemptCompleted(
            event,
            YpsoImmediateBolusStatus(attempt.pumpFastSequence, BolusCommand.STATUS_IDLE, 0, 0),
            historyAmount,
        )
        val terminalStatusCleared = status.statusCode == BolusCommand.STATUS_IDLE &&
            status.programmedCentiUnits == 0 && status.deliveredCentiUnits == 0
        if (!terminalStatusCleared) return YpsoImmediateBolusReconciliation.Unresolved(
            YpsoImmediateBolusReconciliation.Reason.STATUS_IDENTITY_CHANGED,
            confirmed,
        )
        return YpsoImmediateBolusReconciliation.AttemptCompleted(event, status, historyAmount)
    }

    private fun strictlyNewer(candidate: Long, baseline: Long): Boolean {
        val delta = (candidate - baseline + 0x1_0000_0000L) % 0x1_0000_0000L
        return delta in 1 until 0x8000_0000L
    }
}
