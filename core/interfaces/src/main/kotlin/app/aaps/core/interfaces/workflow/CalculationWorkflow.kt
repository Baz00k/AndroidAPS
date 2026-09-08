package app.aaps.core.interfaces.workflow

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.MAIN_CALCULATION

interface CalculationWorkflow {
    companion object {

        const val MAIN_CALCULATION = "calculation"
        const val PASS = "pass"
    }

    /**
     * Passes reported through `EventIobCalculationProgress`. The seven graph-series passes are gone with
     * the workers that raised them; the weights below must still total 100 (asserted at startup).
     */
    enum class ProgressData(val pass: Int, val percentOfTotal: Int) {
        IOB_COB_OREF(0, 99),
        DRAW_FINAL(1, 1);

        fun finalPercent(progress: Int): Int {
            var total = 0
            for (i in entries) if (i.pass < pass) total += i.percentOfTotal
            total += (percentOfTotal.toDouble() * progress / 100.0).toInt()
            return total
        }
    }

    fun stopCalculation(job: String, from: String)

    /**
     * Start calculation of data needed for displaying graphs
     *
     * @param job unique-work name, always [MAIN_CALCULATION]
     */
    fun runCalculation(
        job: String,
        iobCobCalculator: IobCobCalculator,
        overviewData: OverviewData,
        reason: String,
        end: Long,
        bgDataReload: Boolean,
        cause: Event?
    )

    /**
     * Redraw the overview after a therapy event changed
     */
    fun runOnEventTherapyEventChange()

    /**
     * Reload the chart after the displayed range changed
     */
    fun runOnScaleChanged(iobCobCalculator: IobCobCalculator, overviewData: OverviewData)

    /**
     * Reload the chart data without touching IOB/COB or invoking the loop.
     *
     * [runCalculation] skips the presentation path while no AAPS screen is visible, so the readings are
     * stale whenever the overview comes back. Call this from onResume to catch up; nothing in it can
     * affect dosing.
     */
    fun runGraphsOnly(iobCobCalculator: IobCobCalculator, overviewData: OverviewData)
}