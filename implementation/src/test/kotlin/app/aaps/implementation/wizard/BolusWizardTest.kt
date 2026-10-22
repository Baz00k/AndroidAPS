package app.aaps.implementation.wizard

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.plugins.aps.openAPSSMB.OpenAPSSMBPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BolusWizardTest : TestBaseWithProfile() {

    private val pumpBolusStep = 0.1

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var loop: Loop
    @Mock lateinit var autosensDataStore: AutosensDataStore
    @Mock lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock lateinit var openAPSSMBPlugin: OpenAPSSMBPlugin
    @Mock lateinit var uel: UserEntryLogger
    @Mock lateinit var automation: Automation
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var persistenceLayer: PersistenceLayer

    @BeforeEach
    fun prepare() {
        whenever(activePlugin.activeAPS).thenReturn(openAPSSMBPlugin)
    }

    @Suppress("SameParameterValue")
    private fun setupProfile(targetLow: Double, targetHigh: Double, insulinSensitivityFactor: Double, insulinToCarbRatio: Double): Profile {
        val profile: Profile = mock()
        whenever(profile.getTargetLowMgdl()).thenReturn(targetLow)
        whenever(profile.getTargetHighMgdl()).thenReturn(targetHigh)
        whenever(profile.getIsfMgdlForCarbs(any(), any(), any(), any())).thenReturn(insulinSensitivityFactor)
        whenever(profile.getIc()).thenReturn(insulinToCarbRatio)
        whenever(profile.units).thenReturn(GlucoseUnit.MGDL)
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)

        whenever(iobCobCalculator.calculateIobFromBolus()).thenReturn(IobTotal(System.currentTimeMillis()))
        whenever(iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(System.currentTimeMillis()))
        testPumpPlugin.pumpDescription = PumpDescription().also {
            it.bolusStep = pumpBolusStep
        }
        whenever(iobCobCalculator.ads).thenReturn(autosensDataStore)

        doAnswer { invocation: InvocationOnMock ->
            invocation.getArgument<Constraint<Double>>(0)
        }.whenever(constraintChecker).applyBolusConstraints(anyOrNull())
        return profile
    }

    @Test
        /** Should calculate the same bolus when different blood glucose but both in target range  */
    fun shouldCalculateTheSameBolusWhenBGsInRange() {
        val profile = setupProfile(4.0, 8.0, 20.0, 12.0)
        var bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                4.2,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForBg42 = bw.calculatedTotalInsulin
        bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                5.4,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForBg54 = bw.calculatedTotalInsulin
        assertThat(bolusForBg54).isWithin(0.01).of(bolusForBg42)
    }

    @Test
    fun shouldCalculateHigherBolusWhenHighBG() {
        val profile = setupProfile(4.0, 8.0, 20.0, 12.0)
        var bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                9.8,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForHighBg = bw.calculatedTotalInsulin
        bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                5.4,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForBgInRange = bw.calculatedTotalInsulin
        assertThat(bolusForHighBg).isGreaterThan(bolusForBgInRange)
    }

    @Test
    fun shouldCalculateLowerBolusWhenLowBG() {
        val profile = setupProfile(4.0, 8.0, 20.0, 12.0)
        var bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                3.6,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForLowBg = bw.calculatedTotalInsulin
        bw =
            BolusWizard(
                aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
                commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
                persistenceLayer, decimalFormatter, processedDeviceStatusData
            ).doCalc(
                profile,
                "",
                null,
                20,
                0.0,
                5.4,
                0.0,
                100,
                useBg = true,
                useCob = true,
                includeBolusIOB = true,
                includeBasalIOB = true,
                useSuperBolus = false,
                useTT = false,
                useTrend = false,
                useAlarm = false
            )
        val bolusForBgInRange = bw.calculatedTotalInsulin
        assertThat(bolusForLowBg).isLessThan(bolusForBgInRange)
    }

    /**
     * A wizard whose only varying inputs are the ones under test. The profile and pump are fixed:
     * ISF 50 mg/dL, IC 10 g, 0.1 U bolus step.
     */
    private fun wizard(
        bgMgdl: Double = 250.0,
        carbs: Int = 30,
        percentage: Int = 100,
        adjustment: Double = 0.0,
        maxBolus: Double? = null,
        bolusIob: Double = 0.0,
        bolusStep: Double = 0.1
    ): BolusWizard {
        val profile = setupProfile(100.0, 110.0, 50.0, 10.0)
        testPumpPlugin.pumpDescription.bolusStep = bolusStep
        if (bolusIob != 0.0)
            whenever(iobCobCalculator.calculateIobFromBolus()).thenReturn(IobTotal(System.currentTimeMillis()).also { it.iob = bolusIob })
        if (maxBolus != null) {
            doAnswer { invocation: InvocationOnMock ->
                invocation.getArgument<Constraint<Double>>(0).setIfSmaller(maxBolus, "test max bolus", this)
            }.whenever(constraintChecker).applyBolusConstraints(anyOrNull())
        }
        return BolusWizard(
            aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
            commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
            persistenceLayer, decimalFormatter, processedDeviceStatusData
        ).doCalc(
            profile, "", null, carbs, 0.0, bgMgdl, adjustment, percentage,
            useBg = true, useCob = false, includeBolusIOB = true, includeBasalIOB = true, useSuperBolus = false,
            useTT = false, useTrend = false, useAlarm = false
        )
    }

    @Test
    fun `a positive adjustment adds exactly its units to the total`() {
        val base = wizard().calculatedTotalInsulin
        val adjusted = wizard(adjustment = 0.5).calculatedTotalInsulin

        assertThat(adjusted - base).isWithin(0.01).of(0.5)
    }

    @Test
    fun `a negative adjustment subtracts exactly its units from the total`() {
        val base = wizard().calculatedTotalInsulin
        val adjusted = wizard(adjustment = -0.5).calculatedTotalInsulin

        assertThat(base - adjusted).isWithin(0.01).of(0.5)
    }

    @Test
    fun `an adjustment is exact at a finer pump step`() {
        val base = wizard(bolusStep = 0.05).calculatedTotalInsulin
        val adjusted = wizard(bolusStep = 0.05, adjustment = 0.05).calculatedTotalInsulin

        assertThat(adjusted - base).isWithin(0.001).of(0.05)
    }

    @Test
    fun `the bolus percentage scales the calculation but not the adjustment`() {
        val full = wizard(percentage = 100).calculatedTotalInsulin
        val half = wizard(percentage = 50).calculatedTotalInsulin
        val halfAdjusted = wizard(percentage = 50, adjustment = 0.5).calculatedTotalInsulin

        // Percentage is still applied to the calculated total...
        assertThat(half).isWithin(0.01).of(full * 0.5)
        // ...but the adjustment is added after it, so half of +0.5 U is not 0.25 U.
        assertThat(halfAdjusted - half).isWithin(0.01).of(0.5)
    }

    @Test
    fun `an adjustment below zero clamps the total at zero and reports the carb equivalent`() {
        val base = wizard().calculatedTotalInsulin
        val adjusted = wizard(adjustment = -20.0)

        assertThat(adjusted.calculatedTotalInsulin).isEqualTo(0.0)
        assertThat(adjusted.insulinAfterConstraints).isEqualTo(0.0)
        // The adjustment is capped at what there was to remove; the rest is still-needed carbs.
        assertThat(adjusted.appliedAdjustment).isWithin(0.01).of(-base)
        assertThat(adjusted.carbsEquivalent).isWithin(1.0).of((20.0 - base) * 10.0)
    }

    @Test
    fun `a positive adjustment does not create a dose from a zero calculation`() {
        val wizard = wizard(bgMgdl = 100.0, carbs = 0, adjustment = 1.0)

        assertThat(wizard.calculatedTotalInsulin).isEqualTo(0.0)
        assertThat(wizard.insulinAfterConstraints).isEqualTo(0.0)
        assertThat(wizard.appliedAdjustment).isEqualTo(0.0)
    }

    @Test
    fun `a calculation below one pump step does not start a dose either`() {
        // 2 mg/dL above target at ISF 50 is 0.04 U, which rounds to 0.0 U at the 0.1 U pump step.
        val wizard = wizard(bgMgdl = 112.0, carbs = 0, adjustment = 1.0)

        assertThat(wizard.calculatedTotalInsulin).isEqualTo(0.0)
        assertThat(wizard.appliedAdjustment).isEqualTo(0.0)
    }

    @Test
    fun `a percentage that scales the dose below one step also leaves nothing to adjust`() {
        // 0.04 U at 50% is 0.02 U, below the pump step, even though the unscaled contribution was real.
        val wizard = wizard(bgMgdl = 112.0, carbs = 0, percentage = 50, adjustment = 1.0)

        assertThat(wizard.calculatedTotalInsulin).isEqualTo(0.0)
        assertThat(wizard.appliedAdjustment).isEqualTo(0.0)
    }

    @Test
    fun `a positive adjustment does not erase the carbs still needed after excess insulin`() {
        // 2 U on board with nothing to cover: the calculation is negative and already clamped at zero.
        val wizard = wizard(bgMgdl = 105.0, carbs = 0, adjustment = 1.0, bolusIob = 2.0)

        assertThat(wizard.calculatedTotalInsulin).isEqualTo(0.0)
        assertThat(wizard.appliedAdjustment).isEqualTo(0.0)
        assertThat(wizard.carbsEquivalent).isWithin(0.1).of(20.0)
    }

    @Test
    fun `an adjustment that exactly cancels the dose leaves nothing to deliver`() {
        val wizard = wizard(bgMgdl = 110.0, carbs = 10, adjustment = -1.0)

        assertThat(wizard.calculatedTotalInsulin).isEqualTo(0.0)
        assertThat(wizard.appliedAdjustment).isWithin(0.01).of(-1.0)
        assertThat(wizard.carbsEquivalent).isEqualTo(0.0)
    }

    @Test
    fun `max bolus still caps an adjusted dose`() {
        // 20 g at IC 10 is 2 U; the +1.5 U adjustment is what pushes it past the 3 U cap.
        val wizard = wizard(bgMgdl = 110.0, carbs = 20, adjustment = 1.5, maxBolus = 3.0)

        assertThat(wizard.calculatedTotalInsulin).isWithin(0.01).of(3.5)
        assertThat(wizard.insulinAfterConstraints).isWithin(0.01).of(3.0)
    }

    @Test
    fun `an off-step adjustment is recorded as the amount the total actually moved`() {
        val base = wizard().calculatedTotalInsulin
        val wizard = wizard(adjustment = 0.25)
        val record = wizard.createBolusCalculatorResult()

        // A caller can supply a finer value than the pump can deliver; the record must match the delta.
        assertThat(record.otherCorrection).isWithin(0.01).of(wizard.calculatedTotalInsulin - base)
        assertThat(record.totalInsulin).isWithin(0.01).of(wizard.calculatedTotalInsulin)
    }

    @Test
    fun `the calculator record keeps the adjustment that reached the total`() {
        assertThat(wizard(adjustment = 0.5).createBolusCalculatorResult().otherCorrection).isWithin(0.01).of(0.5)
        // An adjustment that could not be applied is not recorded as if it had been.
        assertThat(wizard(bgMgdl = 100.0, carbs = 0, adjustment = 1.0).createBolusCalculatorResult().otherCorrection).isEqualTo(0.0)
    }

    @Test
    fun `delivery hands the pump the adjusted dose and its calculator record`() {
        val wizard = wizard(adjustment = 0.5)
        whenever(profileFunction.getProfile()).thenReturn(wizard.profile)
        whenever(persistenceLayer.insertOrUpdateBolusCalculatorResult(any()))
            .thenReturn(Single.just(PersistenceLayer.TransactionResult()))
        val info = argumentCaptor<DetailedBolusInfo>()

        wizard.confirmAndExecute(context, skipConfirmation = true, advisor = false)

        verify(commandQueue).bolus(info.capture(), anyOrNull())
        // 2.8 U of BG correction + 3.0 U of carbs + 0.5 U adjustment.
        assertThat(info.firstValue.insulin).isWithin(0.01).of(6.3)
        assertThat(info.firstValue.bolusCalculatorResult?.otherCorrection).isWithin(0.01).of(0.5)
    }

    private fun advisorWizard(bgMgdl: Double, carbs: Int, carbTime: Int, bolusIob: Double = 0.0): BolusWizard {
        val profile = setupProfile(100.0, 110.0, 50.0, 10.0)
        whenever(iobCobCalculator.calculateIobFromBolus()).thenReturn(IobTotal(System.currentTimeMillis()).also { it.iob = bolusIob })
        return BolusWizard(
            aapsLogger, rh, rxBus, preferences, profileFunction, profileUtil, constraintChecker, activePlugin,
            commandQueue, loop, iobCobCalculator, dateUtil, config, uel, automation, glucoseStatusProvider, uiInteraction,
            persistenceLayer, decimalFormatter, processedDeviceStatusData
        ).doCalc(
            profile, "", null, carbs, 0.0, bgMgdl, 0.0, 100,
            useBg = true, useCob = false, includeBolusIOB = true, includeBasalIOB = true, useSuperBolus = false,
            useTT = false, useTrend = false, useAlarm = false, carbTime = carbTime
        )
    }

    @Test
    fun `bolus advisor applies to a meal eaten now or later while glucose is above 180 mg per dl`() {
        whenever(preferences.get(BooleanKey.OverviewUseBolusAdvisor)).thenReturn(true)
        assertThat(advisorWizard(bgMgdl = 181.0, carbs = 30, carbTime = 0).bolusAdvisorApplies()).isTrue()
        assertThat(advisorWizard(bgMgdl = 250.0, carbs = 30, carbTime = 15).bolusAdvisorApplies()).isTrue()
    }

    @Test
    fun `bolus advisor does not apply at 180, without carbs, for carbs already eaten, or when switched off`() {
        whenever(preferences.get(BooleanKey.OverviewUseBolusAdvisor)).thenReturn(true)
        assertThat(advisorWizard(bgMgdl = 180.0, carbs = 30, carbTime = 0).bolusAdvisorApplies()).isFalse()
        assertThat(advisorWizard(bgMgdl = 250.0, carbs = 0, carbTime = 0).bolusAdvisorApplies()).isFalse()
        assertThat(advisorWizard(bgMgdl = 250.0, carbs = 30, carbTime = -10).bolusAdvisorApplies()).isFalse()
        whenever(preferences.get(BooleanKey.OverviewUseBolusAdvisor)).thenReturn(false)
        assertThat(advisorWizard(bgMgdl = 250.0, carbs = 30, carbTime = 0).bolusAdvisorApplies()).isFalse()
    }

    @Test
    fun `bolus advisor does not apply when there is no dose, so eating later cannot drop the carbs`() {
        whenever(preferences.get(BooleanKey.OverviewUseBolusAdvisor)).thenReturn(true)
        // 30 g at IC 10 plus a correction from 250 is ~5.3 U; 10 U on board more than covers it.
        assertThat(advisorWizard(bgMgdl = 250.0, carbs = 30, carbTime = 0, bolusIob = 10.0).bolusAdvisorApplies()).isFalse()
    }
}
