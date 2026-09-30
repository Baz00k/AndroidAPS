package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.Immutable

/** User-editable Calculator inputs. Carbs + the factor toggles shown as "included in this dose". */
@Immutable
data class WizardInputs(
    val carbs: Int = 0,
    /**
     * Pre-bolus: minutes from NOW until the carbs are actually eaten (stock AAPS "carb time"). The bolus is
     * delivered immediately and [BolusWizard] timestamps the carbs at `now + carbTime`, so fast carbs get the
     * insulin head start they need. Negative = carbs already eaten. Range ±60, matching EditQuickWizardSheet.
     */
    val carbTime: Int = 0,
    /**
     * Extended carbs: hours the carbs are declared to absorb over (0 = all at once). Slow fat/protein meals
     * genuinely absorb over hours; declaring that per-meal is the correct fix, because the model's absorption
     * constant (tMaxG) is GLOBAL and drains every meal through one shared gut compartment — so it cannot
     * describe a fast and a slow meal at once. AAPS expands the entry into 15-min chunks, which is what the
     * APS/COB path already reads.
     */
    val carbDurationHours: Int = 0,
    /**
     * Manually entered glucose, in the user's DISPLAY units, or null to use the CGM.
     *
     * Exists because the CGM is not always the truth: a failed sensor, a warm-up gap, or a fingerstick that
     * disagrees all leave the Calculator correcting from a number the user can see is wrong.
     *
     * Entering one also forces the TREND contribution off: a CGM-derived trend describes the sensor trace,
     * and if the sensor were trustworthy there would be no reason to override it.
     */
    val manualBg: Double? = null,
    val useBg: Boolean = true,
    val useIob: Boolean = true,
    val useTrend: Boolean = false,
    val useSuperBolus: Boolean = false,
    /**
     * Bolus advisor: glucose is high, so bolus now and eat once it has come down. The carbs are not
     * logged yet; an eat reminder is scheduled instead. Only offered while the advisor applies.
     */
    val eatLater: Boolean = false
)

/** How the Calculator's glucose is shown: where it came from and whether it counts. */
enum class GlucoseSource { SENSOR, STALE, MANUAL, NONE }

enum class GlucoseTone { LOW, IN_RANGE, HIGH, NONE }

/** Computed result of running [WizardInputs] through the existing BolusWizard (strings pre-formatted). */
@Immutable
data class WizardResult(
    /** False without a profile: there is nothing to calculate with. */
    val available: Boolean = false,
    val glucoseText: String = "--",
    val glucoseSource: GlucoseSource = GlucoseSource.NONE,
    val glucoseTone: GlucoseTone = GlucoseTone.NONE,
    /** Reading age, e.g. "3 min ago"; blank for a manual or missing value. */
    val glucoseAge: String = "",
    val trendArrow: String = "",
    /** Entry bounds for the manual glucose field, in display units (mmol/L vs mg/dL differ by 18x). */
    val bgEntryMin: Double = 1.0,
    val bgEntryMax: Double = 30.0,
    val bgEntryStep: Double = 0.1,
    val bgEntryDecimals: Int = 1,
    val bgUnitsLabel: String = "",
    val carbsInsulin: String = "+0.00 U",
    val bgInsulin: String = "+0.00 U",
    val iobInsulin: String = "0.00 U",
    val trendInsulin: String = "+0.00 U",
    val superBolusInsulin: String = "+0.00 U",
    /** The bolus percentage from settings, when it is not 100. */
    val scaledPercent: Int? = null,
    val outcome: CalculatorOutcome = CalculatorOutcome(),
    val advisorAvailable: Boolean = false,
    /**
     * Advisory shown when the cannula is new. Insulin peaks about twice as slowly at a fresh site
     * (time-to-peak 110 min on day 1 vs 56 min on day 4, Hildebrandt 1991), and a single large bolus
     * makes it worse still: the same dose split into smaller ones gave a 1.8x higher depot
     * surface-to-volume ratio and significantly faster onset (Diabetes Care 2013). The two compound,
     * which is exactly the failure seen on 2026-08-13 -- a correctly-dosed 6.70 U lunch bolus into a
     * ~6h-old site ran glucose to 15.9, and the corrections added while waiting all landed at once.
     *
     * Advisory only. Splitting the dose is the user's call and the loop cannot do it for them, so
     * this is the only place the finding can actually reach the decision.
     */
    val siteWarning: String = "",
    val superBolusAvailable: Boolean = false
) {

    val bgCorrectionAvailable: Boolean get() = glucoseSource == GlucoseSource.SENSOR || glucoseSource == GlucoseSource.MANUAL
    val trendAvailable: Boolean get() = glucoseSource == GlucoseSource.SENSOR
}
