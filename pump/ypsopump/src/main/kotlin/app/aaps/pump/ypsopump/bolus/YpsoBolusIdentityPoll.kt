package app.aaps.pump.ypsopump.bolus

import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.history.YpsoBolusPumpIdentity

/**
 * Decides, from one post-dispatch bolus status, whether the pump has proven this command's delivery
 * identity.
 *
 * Getting this wrong is dangerous in both directions: adopting a foreign identity aims a later
 * cancellation at somebody else's insulin, while refusing a valid one leaves delivered insulin
 * unattributed. The pump offers no origin field, so the only usable evidence is the programmed
 * shape plus the ordering of the block sequence.
 */
object YpsoBolusIdentityPoll {

    sealed interface Step {
        /** The pump reports this command's programmed shape under a newer block sequence. */
        data class Proven(val status: BolusCommand) : Step

        /** A second distinct delivery appeared, so no identity here can be trusted to be ours. */
        data object Abandon : Step

        data class KeepGoing(val firstObservedSequence: Long?) : Step
    }

    fun evaluate(
        attempt: YpsoBolusAttempt,
        request: YpsoValidatedBolusRequest,
        status: BolusCommand?,
        firstObservedSequence: Long?,
    ): Step {
        val immediate = request.shape == YpsoBolusShape.IMMEDIATE
        val baseline = if (immediate) attempt.baseline.fastSequence else attempt.baseline.slowSequence
        val sequence = status?.let { if (immediate) it.fastSequence else it.extendedSequence }
        var firstObserved = firstObservedSequence
        if (sequence != null && YpsoBolusPumpIdentity.isStrictlyNewer(sequence, baseline)) {
            // The pump shares one counter between its blocks and its history rows, so an unrelated
            // delivery appearing during polling is also "newer" than the baseline. Only the first
            // block identity reported after dispatch can be this command's; adopting a later one
            // would attribute a manual dose to AAPS and make it the cancellation target.
            if (firstObserved == null) firstObserved = sequence
            else if (sequence != firstObserved) return Step.Abandon
        }
        val proven = status != null && when (request.shape) {
            YpsoBolusShape.IMMEDIATE ->
                YpsoBolusPumpIdentity.isStrictlyNewer(status.fastSequence, attempt.baseline.fastSequence) &&
                    cents(status.totalProgrammedUnits) == attempt.requestedCentiUnits &&
                    status.extendedStatusCode == BolusCommand.STATUS_IDLE
            YpsoBolusShape.EXTENDED, YpsoBolusShape.COMBINED ->
                YpsoBolusPumpIdentity.isStrictlyNewer(status.extendedSequence, attempt.baseline.slowSequence) &&
                    cents(status.extendedTotalUnits) == attempt.requestedCentiUnits &&
                    status.extendedMinutesTotal == attempt.durationMinutes &&
                    cents(status.comboImmediateTotalUnits) == attempt.immediateCentiUnits &&
                    status.bolusStatusCode == BolusCommand.STATUS_IDLE
        }
        return if (proven) Step.Proven(status!!) else Step.KeepGoing(firstObserved)
    }

    private fun cents(units: Double): Int = Math.round(units * 100.0).toInt()
}
