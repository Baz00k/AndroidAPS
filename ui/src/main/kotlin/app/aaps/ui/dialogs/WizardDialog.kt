package app.aaps.ui.dialogs

import app.aaps.core.data.model.GlucoseUnit
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.ui.dialogs.compose.CalculatorGlucose
import app.aaps.ui.dialogs.compose.CalculatorOutcome
import app.aaps.ui.dialogs.compose.DoseDrift
import app.aaps.ui.dialogs.compose.GlucoseSource
import app.aaps.ui.dialogs.compose.GlucoseTone
import app.aaps.ui.dialogs.compose.WizardInputs
import app.aaps.ui.dialogs.compose.WizardCarbControls
import app.aaps.ui.dialogs.compose.PumpReadyGate
import app.aaps.ui.dialogs.compose.WizardResult
import app.aaps.ui.dialogs.compose.WizardScreen
import app.aaps.core.ui.dialogs.DaggerBottomSheetFragment
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.abs

/**
 * The Calculator. The UI is Compose ([WizardScreen]); all dosing math reuses the existing [BolusWizard]
 * (`doCalc`) and delivery reuses [BolusWizard.confirmAndExecute] — the same constraint and execution path
 * as before. What the user reviewed is exactly what is committed: the dose is recomputed at confirmation
 * and nothing is sent if it changed ([DoseDrift]).
 */
class WizardDialog : DaggerBottomSheetFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var ctx: Context
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var bolusWizardProvider: Provider<BolusWizard>
    @Inject lateinit var pumpReadyGate: PumpReadyGate

    @Suppress("unused")
    private val handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)

    private var initialCarbs = 0

    /**
     * The HandlerThread started above is not a daemon, so without this it outlives the dialog and
     * one thread leaks per wizard open — ten of them were live on device. ErrorDialog already does
     * exactly this; the wizard was simply missing it.
     */
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        arguments?.let { initialCarbs = it.getDouble("carbs_input", 0.0).toInt() }
        isCancelable = true
        aapsLogger.debug(LTag.APS, "Dialog opened: ${this.javaClass.simpleName}")
        return sheetContent {
            WizardScreen(
                compute = ::compute,
                onCommit = ::commit,
                onCancel = { dismiss() },
                initialInputs = WizardInputs(carbs = initialCarbs),
                carbControls = WizardCarbControls.fromOverviewIncrements(
                    listOf(
                        preferences.get(IntKey.OverviewCarbsButtonIncrement1),
                        preferences.get(IntKey.OverviewCarbsButtonIncrement2),
                        preferences.get(IntKey.OverviewCarbsButtonIncrement3)
                    ),
                    maxCarbs = constraintChecker.getMaxCarbsAllowed().value()
                )
            )
        }
    }

    private fun glucose(inputs: WizardInputs): CalculatorGlucose {
        val last = iobCobCalculator.ads.lastBg()
        return CalculatorGlucose.resolve(
            manualMgdl = inputs.manualBg?.let { profileUtil.convertToMgdl(it, profileFunction.getUnits()) },
            sensorMgdl = last?.recalculated,
            sensorTimestamp = last?.timestamp,
            now = dateUtil.now()
        )
    }

    private fun outcome(w: BolusWizard, carbs: Int) = CalculatorOutcome.of(
        calculatedInsulin = w.calculatedTotalInsulin,
        insulinAfterConstraints = w.insulinAfterConstraints,
        carbsEquivalent = w.carbsEquivalent,
        carbs = carbs,
        bolusStep = activePlugin.activePump.pumpDescription.bolusStep
    )

    /** Build the wizard for [inputs] and format its components for display. Pure — no side effects. */
    private fun compute(inputs: WizardInputs): WizardResult {
        val profile = profileFunction.getProfile() ?: return WizardResult()
        val units = profileFunction.getUnits()
        val mmol = units == GlucoseUnit.MMOL
        val glucose = glucose(inputs)
        val carbs = constraintChecker.applyCarbsConstraints(ConstraintObject(inputs.carbs, aapsLogger)).value()
        val w = buildWizard(inputs, profile, glucose, carbs)

        // Shown value: the one in use, or a stale reading for context.
        val shownMgdl = when (glucose) {
            is CalculatorGlucose.Stale -> glucose.mgdl
            else                       -> glucose.usedMgdl
        }
        // Colour against the display low/high marks — the band the rest of the app colours BG by, not
        // the target band (a single-point target would make an at-target reading look "high").
        val tone = shownMgdl?.let { mgdl ->
            val shown = profileUtil.fromMgdlToUnits(mgdl, units)
            when {
                shown < preferences.get(UnitDoubleKey.OverviewLowMark)  -> GlucoseTone.LOW
                shown > preferences.get(UnitDoubleKey.OverviewHighMark) -> GlucoseTone.HIGH
                else                                                    -> GlucoseTone.IN_RANGE
            }
        } ?: GlucoseTone.NONE
        val delta = w.glucoseStatus?.delta ?: 0.0
        return WizardResult(
            available = true,
            glucoseText = shownMgdl?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "--",
            glucoseSource = when (glucose) {
                is CalculatorGlucose.Sensor -> GlucoseSource.SENSOR
                is CalculatorGlucose.Stale  -> GlucoseSource.STALE
                is CalculatorGlucose.Manual -> GlucoseSource.MANUAL
                CalculatorGlucose.None      -> GlucoseSource.NONE
            },
            glucoseTone = tone,
            glucoseAge = when (glucose) {
                is CalculatorGlucose.Sensor -> dateUtil.minOrSecAgo(rh, glucose.timestamp)
                is CalculatorGlucose.Stale  -> dateUtil.minOrSecAgo(rh, glucose.timestamp)
                else                        -> ""
            },
            // A trend is a property of a live sensor trace; none for a stale, missing or typed-in value.
            trendArrow = if (glucose is CalculatorGlucose.Sensor) when { delta > 3 -> "↗"; delta < -3 -> "↘"; else -> "→" } else "",
            bgEntryMin = if (mmol) 1.0 else 20.0,
            bgEntryMax = if (mmol) 30.0 else 540.0,
            bgEntryStep = if (mmol) 0.1 else 1.0,
            bgEntryDecimals = if (mmol) 1 else 0,
            bgUnitsLabel = if (mmol) "mmol/L" else "mg/dL",
            carbsInsulin = signed(w.insulinFromCarbs),
            bgInsulin = signed(w.insulinFromBG),
            iobInsulin = signed(-w.insulinFromBolusIOB - w.insulinFromBasalIOB),
            trendInsulin = signed(w.insulinFromTrend),
            superBolusInsulin = signed(w.insulinFromSuperBolus),
            scaledPercent = preferences.get(IntKey.OverviewBolusPercentage).takeIf { it != 100 },
            outcome = outcome(w, carbs),
            advisorAvailable = w.bolusAdvisorApplies(),
            siteWarning = freshSiteWarning(w.insulinAfterConstraints),
            superBolusAvailable = false
        )
    }

    /**
     * Advisory when bolusing into a cannula less than [FRESH_SITE_H] old.
     *
     * Only fires for a dose big enough to matter -- a fresh site handles a basal trickle fine, and the
     * handicap is only expressed under a large single bolus. Silent when no cannula change has ever
     * been recorded, so a database with no site history never nags.
     */
    private fun freshSiteWarning(insulin: Double): String {
        if (insulin < FRESH_SITE_MIN_BOLUS_U) return ""
        val last = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)?.timestamp ?: return ""
        val ageH = (dateUtil.now() - last) / T.hours(1).msecs().toDouble()
        if (ageH >= FRESH_SITE_H || ageH < 0) return ""
        return String.format(
            Locale.getDefault(),
            "New cannula (%.0fh) — insulin peaks ~2× slower here. Consider splitting this dose, and give corrections time.",
            ageH
        )
    }

    /** What confirming will actually do, rebuilt from current data. Compared, never trusted from the screen. */
    private class Plan(val wizard: BolusWizard, val outcome: CalculatorOutcome, val advisor: Boolean)

    private fun plan(inputs: WizardInputs): Plan? {
        val profile = profileFunction.getProfile() ?: return null
        val carbs = constraintChecker.applyCarbsConstraints(ConstraintObject(inputs.carbs, aapsLogger)).value()
        val w = buildWizard(inputs, profile, glucose(inputs), carbs)
        val outcome = outcome(w, carbs)
        // "Eat later" is only a real choice for a dose the advisor applies to. The answer is always
        // explicit: a null would let BolusWizard ask again in a popup, after the hold.
        val advisorApplies = w.bolusAdvisorApplies() && outcome.commit == CalculatorOutcome.Commit.DELIVER
        return Plan(w, outcome, advisorApplies && inputs.eatLater)
    }

    /** The reviewed plan still holds: same dose and carbs, and the same answer to the advisor. */
    private fun Plan.matches(inputs: WizardInputs, reviewed: CalculatorOutcome): Boolean =
        !DoseDrift.changed(reviewed, outcome, activePlugin.activePump.pumpDescription.bolusStep) &&
            outcome.commit != CalculatorOutcome.Commit.NONE &&
            (!inputs.eatLater || advisor)

    /** Set on the first commit; a second confirmation (a repeated tap or accessibility action) is ignored. */
    private var committing = false

    /**
     * Commit what the user reviewed, once. The plan is rebuilt from current data and compared with
     * [reviewed] at the moment it would execute — after the pump pre-flight, which can wait on the
     * user. If anything moved, nothing is sent and [onChanged] asks the screen for a fresh review.
     */
    private fun commit(inputs: WizardInputs, reviewed: CalculatorOutcome, onChanged: () -> Unit) {
        if (committing) return
        val activity = activity ?: return
        committing = true
        fun rejected() {
            committing = false
            onChanged()
        }

        fun execute() {
            val plan = plan(inputs)?.takeIf { it.matches(inputs, reviewed) } ?: return rejected()
            // skipConfirmation: Review and its hold ARE the confirmation. Constraints, audit and the
            // command queue still run.
            plan.wizard.confirmAndExecute(activity, skipConfirmation = true, advisor = plan.advisor)
            dismiss()
        }
        when (plan(inputs)?.takeIf { it.matches(inputs, reviewed) }?.outcome?.commit) {
            CalculatorOutcome.Commit.DELIVER   -> pumpReadyGate.runWhenPumpCanDeliver(activity, onCancel = { committing = false }) { execute() }
            // Carbs alone need no pump command, so no pump pre-flight either.
            CalculatorOutcome.Commit.LOG_CARBS -> execute()
            else                               -> rejected()
        }
    }

    private fun buildWizard(inputs: WizardInputs, profile: app.aaps.core.interfaces.profile.Profile, glucose: CalculatorGlucose, carbs: Int): BolusWizard =
        bolusWizardProvider.get().doCalc(
            profile = profile,
            profileName = profileFunction.getProfileName(),
            tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()),
            carbs = carbs,
            cob = 0.0,
            bg = glucose.usedMgdl?.let { profileUtil.fromMgdlToUnits(it, profileFunction.getUnits()) } ?: 0.0,
            correction = 0.0,
            // pre-bolus: BolusWizard timestamps the carbs at now + carbTime (see its carbsTimestamp), so the
            // bolus goes in immediately while the loop is told when the carbs actually land.
            carbTime = inputs.carbTime,
            // extended carbs: declares a slow meal's absorption per-meal (AAPS expands to 15-min chunks)
            carbDurationHours = inputs.carbDurationHours,
            percentageCorrection = preferences.get(IntKey.OverviewBolusPercentage),
            // No trustworthy glucose, no correction: a stale reading is never corrected from.
            useBg = inputs.useBg && glucose.usedMgdl != null,
            useCob = false,
            includeBolusIOB = inputs.useIob,
            includeBasalIOB = inputs.useIob,
            useSuperBolus = inputs.useSuperBolus,
            useTT = true,
            // Trend is a property of a live sensor trace; not of a typed-in, stale or missing value.
            useTrend = inputs.useTrend && glucose is CalculatorGlucose.Sensor,
            // Eat reminder for a pre-bolus: scheduled by BolusWizard once the bolus has gone through.
            useAlarm = inputs.remindToEat && inputs.carbTime > 0 && !inputs.eatLater
        )

    private fun signed(v: Double): String {
        val rounded = if (abs(v) < 0.005) 0.0 else v
        val sign = if (rounded > 0) "+" else ""
        return sign + String.format(Locale.getDefault(), "%.2f U", rounded)
    }

    companion object {

        /** Day-1 window. The measured effect spans days; 24h captures the worst of it. */
        const val FRESH_SITE_H = 24.0

        /** Below this, a single bolus is small enough that depot surface-to-volume is not the issue. */
        const val FRESH_SITE_MIN_BOLUS_U = 1.5
    }

}
