package app.aaps.plugins.main.iob

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.smoothing.Smoothing
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.plugins.aps.openAPSAutoISF.DetermineBasalAutoISF
import app.aaps.plugins.aps.openAPSAutoISF.GlucoseStatusCalculatorAutoIsf
import app.aaps.plugins.aps.openAPSAutoISF.OpenAPSAutoISFPlugin
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalSMB
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import app.aaps.plugins.smoothing.AvgSmoothingPlugin
import app.aaps.plugins.smoothing.ExponentialSmoothingPlugin
import app.aaps.plugins.smoothing.NoSmoothingPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GlucoseInputDosingReplayTest : TestBaseWithProfile() {

    private fun replay(latest: Double, older: Double, currentTemp: CurrentTemp = CurrentTemp(duration = 30, rate = 4.0, minutesrunning = 0)): RT {
        val store = AutosensDataStoreObject().also {
            it.bgReadings = (0L..45L).map { minute ->
                GV(
                    timestamp = now - T.mins(minute).msecs(),
                    value = if (minute == 0L) latest else older,
                    raw = null,
                    noise = null,
                    trendArrow = TrendArrow.NONE,
                    sourceSensor = SourceSensor.LIBRE_3
                )
            }
            it.createBucketedData(aapsLogger, dateUtil)
        }
        whenever(iobCobCalculator.ads).thenReturn(store)
        val status = glucoseStatusCalculatorSMB.getGlucoseStatusData(false)!!
        assertThat(status.glucose).isEqualTo(latest)

        // Isolated SMB decision replay: no pump, carbs, IOB, or dynamic ISF. Zero-insulin
        // predictions and explicit limits make the fallback and safety-cap expectations clear.
        return DetermineBasalSMB(profileUtil, fabricPrivacy).determine_basal(
            glucose_status = status,
            currenttemp = currentTemp,
            iob_data_array = Array(48) { step ->
                val time = now + T.mins(step * 5L).msecs()
                IobTotal(time = time, iobWithZeroTemp = IobTotal(time = time))
            },
            profile = profile(),
            autosens_data = AutosensResult(),
            meal_data = MealData(),
            microBolusAllowed = true,
            currentTime = now,
            flatBGsDetected = false,
            dynIsfMode = false
        )
    }

    @Test
    fun `sensor error in one minute feed cancels high temp without requesting SMB`() {
        val result = replay(latest = 38.0, older = 150.0)

        // 38 is the xDrip error sentinel, not glucose to average with valid neighbours.
        assertThat(result.rate).isEqualTo(1.0)
        assertThat(result.duration).isEqualTo(30)
        assertThat(result.units).isNull()
        assertThat(result.reason.toString()).contains("CGM is calibrating")
    }

    @Test
    fun `new low in one minute feed requests zero temp without SMB`() {
        val result = replay(latest = 55.0, older = 150.0)

        assertThat(result.bg).isEqualTo(55.0)
        assertThat(result.rate).isEqualTo(0.0)
        assertThat(result.duration!!).isAtLeast(30)
        assertThat(result.duration!!).isAtMost(120)
        assertThat(result.units).isNull()
    }

    @Test
    fun `high one minute feed remains bounded by SMB and basal limits`() {
        val result = replay(latest = 180.0, older = 180.0, currentTemp = CurrentTemp(duration = 0, rate = 1.0, minutesrunning = 0))

        assertThat(result.bg).isEqualTo(180.0)
        // With a 1 U/h basal and 30-minute SMB cap, no microbolus exceeds 0.5 U.
        assertThat(result.units!!).isGreaterThan(0.0)
        assertThat(result.units!!).isAtMost(0.5)
        assertThat(result.rate!!).isAtLeast(0.0)
        assertThat(result.rate!!).isAtMost(3.0)
    }

    @ParameterizedTest
    @ValueSource(strings = ["none", "average", "exponential"])
    fun `published one minute excursion and recovery reaches smoothing and AutoISF decisions`(smoothingName: String) {
        val smoothing = smoothing(smoothingName)
        val autoStatus = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, decimalFormatter, deltaCalculator)
        val autoPlugin = autoIsfPlugin(autoStatus)
        val therapyProfile = mock<Profile>()
        whenever(therapyProfile.getProfileIsfMgdl()).thenReturn(50.0)
        whenever(therapyProfile.getTargetMgdl()).thenReturn(100.0)
        var store = AutosensDataStoreObject()
        val history = (-45L..0L).map { reading(it, 100.0) }.reversed().toMutableList()

        for (minute in 0L..70L) {
            val glucose = when {
                minute <= 10 -> 100.0 + 10 * minute
                minute <= 20 -> 300.0 - 10 * minute
                else         -> 100.0
            }
            if (minute > 0) history.add(0, reading(minute, glucose))
            val clock = now + T.mins(minute).msecs()
            whenever(dateUtil.now()).thenReturn(clock)
            store.bgReadings = history.toList()
            store.createBucketedData(aapsLogger, dateUtil)
            assertThat(store.bucketedData!!.first().value).isEqualTo(glucose)
            assertThat(store.bucketedData!!.first().filledGap).isFalse()
            store.bucketedData = smoothing.smooth(store.bucketedData!!)

            // Production computes on a clone and publishes it. The reference/grid is not
            // retained by clone today: do not turn same-instance stability into a claim
            // about worker replacement, or adopt the deferred reference-time fix here.
            store = store.clone() as AutosensDataStoreObject
            whenever(iobCobCalculator.ads).thenReturn(store)
            val status = autoStatus.getGlucoseStatusData(false)!!
            val smbStatus = glucoseStatusCalculatorSMB.getGlucoseStatusData(false)!!
            assertThat(status.date).isEqualTo(clock)
            assertThat(status.glucose).isEqualTo(smbStatus.glucose)
            assertThat(status.delta).isEqualTo(smbStatus.delta)
            assertThat(status.glucose.isFinite()).isTrue()
            assertThat(status.bgAcceleration.isFinite()).isTrue()
            if (smoothingName != "exponential") assertThat(status.glucose).isEqualTo(glucose)
            if (minute == 10L) {
                assertThat(status.delta).isGreaterThan(0.0)
                if (smoothingName != "exponential") assertThat(status.delta).isEqualTo(50.0)
                else {
                    // From a 100 plateau, grid samples 150 then 200 give first-order
                    // 162.5 and second-order 164. Their 40/60 blend rounds to 163.
                    assertThat(status.glucose).isEqualTo(163.0)
                }
            }
            if (minute == 20L) {
                assertThat(status.delta).isLessThan(0.0)
                if (smoothingName != "exponential") assertThat(status.delta).isEqualTo(-50.0)
            }
            if (minute == 15L && smoothingName != "exponential") {
                // At the turn, average smoothing changes the preceding peak to
                // (150 + 200 + 150)/3. It must not behave as a no-op smoother.
                val expected = if (smoothingName == "average") -16.67 else -50.0
                assertThat(status.delta).isWithin(0.01).of(expected)
            }
            if (minute == 70L) {
                // Recovery stays inside AutoISF's 5% plateau band. Exponential
                // smoothing can still ring after the excursion; do not claim zero lag.
                assertThat(status.glucose).isWithin(5.0).of(100.0)
                assertThat(status.delta).isWithin(5.0).of(0.0)
                // At least two preceding measured grid points must be in that band,
                // enough for the duration adaptation to see a recovered plateau.
                assertThat(status.duraISFminutes).isAtLeast(10.0)
            }

            val sensitivity = autoPlugin.autoISF(therapyProfile)
            // Base ISF 50 mg/dL/U, with factor constrained to 0.7..1.5, no autosens/TT.
            assertThat(sensitivity).isAtLeast(33.3)
            assertThat(sensitivity).isAtMost(71.5)
            if (minute == 10L) assertThat(sensitivity).isLessThan(50.0)
            // Back at target, the configured low-BG weighting may deliberately raise
            // ISF above its base value; recovery must not retain the high-side reduction.
            if (minute == 70L) assertThat(sensitivity).isAtLeast(50.0)
            val result = autoDecision(status, sensitivity, clock)
            assertThat(result.bg).isEqualTo(status.glucose)
            assertDecisionLimits(result)
            val smb = DetermineBasalSMB(profileUtil, fabricPrivacy).determine_basal(
                smbStatus, CurrentTemp(0, 1.0, 0), zeroIob(clock), profile(), AutosensResult(), MealData(),
                true, clock, false, false
            )
            assertDecisionLimits(smb)
            if (minute == 10L) {
                // High and rising with zero IOB must produce a correction proposal.
                // This rise exceeds the existing max-delta SMB guard: verify the guard
                // remains effective and the bounded basal correction is not absent.
                assertThat(result.units).isNull()
                assertThat(smb.units).isNull()
                assertThat(result.rate ?: 0.0).isGreaterThan(1.0)
                assertThat(smb.rate ?: 0.0).isGreaterThan(1.0)
            }
        }

        // Missing input stays missing; smoothed data is not a reason to bypass freshness.
        whenever(dateUtil.now()).thenReturn(now + T.mins(77).msecs() + 1)
        assertThat(autoStatus.getGlucoseStatusData(false)).isNull()
        assertThat(glucoseStatusCalculatorSMB.getGlucoseStatusData(false)).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["none", "average", "exponential"])
    fun `smoothing and publication preserve gap provenance for AutoISF`(smoothingName: String) {
        val store = AutosensDataStoreObject().also {
            it.bgReadings = listOf(reading(0, 140.0), reading(-1, 140.0), reading(-2, 140.0)) +
                (-20L downTo -45L).map { minute -> reading(minute, 140.0) }
            it.createBucketedData(aapsLogger, dateUtil)
            assertThat(it.bucketedData!!.take(5).map { bucket -> bucket.value }).containsExactly(140.0, 140.0, 140.0, 140.0, 140.0).inOrder()
            it.bucketedData = smoothing(smoothingName).smooth(it.bucketedData!!)
        }.clone()
        whenever(iobCobCalculator.ads).thenReturn(store)

        // Interpolated -5/-10/-15-minute points remain flagged, even if smoothing adds
        // values. Constant glucose is inside the plateau band, so only the gap/provenance
        // check can prevent it being counted as a continuous measured plateau.
        assertThat(store.bucketedData!!.take(5).map { it.filledGap }).containsExactly(false, true, true, true, false).inOrder()
        val status = GlucoseStatusCalculatorAutoIsf(aapsLogger, iobCobCalculator, dateUtil, decimalFormatter, deltaCalculator).getGlucoseStatusData(false)!!
        assertThat(status.duraISFminutes).isEqualTo(0.0)
        assertThat(status.parabolaMinutes).isEqualTo(0.0)
        assertThat(status.bgAcceleration).isEqualTo(0.0)
        assertDecisionLimits(autoDecision(status, 50.0, now))
    }

    private fun reading(minutesFromStart: Long, glucose: Double) = GV(
        timestamp = now + T.mins(minutesFromStart).msecs(), value = glucose, raw = null, noise = null,
        trendArrow = TrendArrow.NONE, sourceSensor = SourceSensor.LIBRE_3
    )

    private fun smoothing(name: String): Smoothing = when (name) {
        "none"        -> NoSmoothingPlugin(aapsLogger, rh)
        "average"     -> AvgSmoothingPlugin(aapsLogger, rh)
        "exponential" -> ExponentialSmoothingPlugin(aapsLogger, rh)
        else          -> error("Unknown smoothing: $name")
    }

    private fun zeroIob(clock: Long) = Array(48) { step ->
        val time = clock + T.mins(step * 5L).msecs()
        IobTotal(time = time, iobWithZeroTemp = IobTotal(time = time))
    }

    private fun assertDecisionLimits(result: RT) {
        // These are proposal limits, not checks of the pump command/delivery path.
        result.units?.let {
            assertThat(it.isFinite()).isTrue()
            assertThat(it).isAtLeast(0.0)
            assertThat(it).isAtMost(0.5)
        }
        result.rate?.let {
            assertThat(it.isFinite()).isTrue()
            assertThat(it).isAtLeast(0.0)
            assertThat(it).isAtMost(3.0)
        }
    }

    private fun autoIsfPlugin(calculator: GlucoseStatusCalculatorAutoIsf): OpenAPSAutoISFPlugin {
        whenever(constraintsChecker.isAutosensModeEnabled()).thenReturn(ConstraintObject(false, aapsLogger))
        whenever(preferences.get(BooleanKey.ApsUseAutoIsfWeights)).thenReturn(true)
        mapOf(
            DoubleKey.ApsAutoIsfMin to 0.7, DoubleKey.ApsAutoIsfMax to 1.5,
            DoubleKey.ApsAutoIsfBgAccelWeight to 0.1, DoubleKey.ApsAutoIsfBgBrakeWeight to 0.1,
            DoubleKey.ApsAutoIsfLowBgWeight to 1.0, DoubleKey.ApsAutoIsfHighBgWeight to 1.0,
            DoubleKey.ApsAutoIsfPpWeight to 0.05, DoubleKey.ApsAutoIsfDuraWeight to 0.5
        ).forEach { (key, value) -> whenever(preferences.get(key)).thenReturn(value) }
        val provider = object : GlucoseStatusProvider {
            override val glucoseStatusData get() = calculator.getGlucoseStatusData(false)
            override fun getGlucoseStatusData(allowOldData: Boolean) = calculator.getGlucoseStatusData(allowOldData)
        }
        return OpenAPSAutoISFPlugin(
            aapsLogger, rxBus, constraintsChecker, rh, profileFunction, profileUtil, config, activePlugin,
            iobCobCalculator, hardLimits, preferences, dateUtil, processedTbrEbData, mock(), provider,
            mock(), mock(), DetermineBasalAutoISF(profileUtil), mock(), calculator, apsResultProvider
        )
    }

    private fun autoDecision(status: GlucoseStatusAutoIsf, sensitivity: Double, clock: Long): RT =
        DetermineBasalAutoISF(profileUtil).determine_basal(
            status, CurrentTemp(0, 1.0, 0), zeroIob(clock), autoProfile(sensitivity), AutosensResult(), MealData(),
            true, clock, false, true, "AAPS", 100, 0.5, 1.0, 100, mutableListOf(), mutableListOf()
        )

    private fun autoProfile(sensitivity: Double) = profile().let { base ->
        OapsProfileAutoIsf(
            dia = base.dia, min_5m_carbimpact = base.min_5m_carbimpact, max_iob = base.max_iob,
            max_daily_basal = base.max_daily_basal, max_basal = base.max_basal,
            min_bg = base.min_bg, max_bg = base.max_bg, target_bg = base.target_bg,
            carb_ratio = base.carb_ratio, sens = base.sens, autosens_adjust_targets = base.autosens_adjust_targets,
            max_daily_safety_multiplier = base.max_daily_safety_multiplier,
            current_basal_safety_multiplier = base.current_basal_safety_multiplier,
            high_temptarget_raises_sensitivity = base.high_temptarget_raises_sensitivity,
            low_temptarget_lowers_sensitivity = base.low_temptarget_lowers_sensitivity,
            sensitivity_raises_target = base.sensitivity_raises_target, resistance_lowers_target = base.resistance_lowers_target,
            adv_target_adjustments = base.adv_target_adjustments, exercise_mode = base.exercise_mode,
            half_basal_exercise_target = base.half_basal_exercise_target, maxCOB = base.maxCOB,
            skip_neutral_temps = base.skip_neutral_temps, remainingCarbsCap = base.remainingCarbsCap,
            enableUAM = base.enableUAM, A52_risk_enable = base.A52_risk_enable, SMBInterval = base.SMBInterval,
            enableSMB_with_COB = base.enableSMB_with_COB, enableSMB_with_temptarget = base.enableSMB_with_temptarget,
            allowSMB_with_high_temptarget = base.allowSMB_with_high_temptarget, enableSMB_always = base.enableSMB_always,
            enableSMB_after_carbs = base.enableSMB_after_carbs, maxSMBBasalMinutes = base.maxSMBBasalMinutes,
            maxUAMSMBBasalMinutes = base.maxUAMSMBBasalMinutes, bolus_increment = base.bolus_increment,
            carbsReqThreshold = base.carbsReqThreshold, current_basal = base.current_basal, temptargetSet = base.temptargetSet,
            autosens_max = base.autosens_max, out_units = base.out_units, lgsThreshold = base.lgsThreshold,
            variable_sens = sensitivity, autoISF_version = "3.0.1", enable_autoISF = true,
            autoISF_max = 1.5, autoISF_min = 0.7, bgAccel_ISF_weight = 0.1, bgBrake_ISF_weight = 0.1,
            pp_ISF_weight = 0.05, lower_ISFrange_weight = 1.0, higher_ISFrange_weight = 1.0, dura_ISF_weight = 0.5,
            smb_delivery_ratio = 0.5, smb_delivery_ratio_min = 0.5, smb_delivery_ratio_max = 0.5,
            smb_delivery_ratio_bg_range = 0.0, smb_max_range_extension = 1.0,
            enableSMB_EvenOn_OddOff_always = false, iob_threshold_percent = 100, profile_percentage = 100
        )
    }

    private fun profile() = OapsProfile(
        dia = 5.0,
        min_5m_carbimpact = 8.0,
        max_iob = 3.0,
        max_daily_basal = 1.0,
        max_basal = 3.0,
        min_bg = 100.0,
        max_bg = 100.0,
        target_bg = 100.0,
        carb_ratio = 10.0,
        sens = 50.0,
        autosens_adjust_targets = false,
        max_daily_safety_multiplier = 3.0,
        current_basal_safety_multiplier = 4.0,
        high_temptarget_raises_sensitivity = false,
        low_temptarget_lowers_sensitivity = false,
        sensitivity_raises_target = false,
        resistance_lowers_target = false,
        adv_target_adjustments = false,
        exercise_mode = false,
        half_basal_exercise_target = 160,
        maxCOB = 120,
        skip_neutral_temps = false,
        remainingCarbsCap = 90,
        enableUAM = false,
        A52_risk_enable = false,
        SMBInterval = 3,
        enableSMB_with_COB = false,
        enableSMB_with_temptarget = false,
        allowSMB_with_high_temptarget = false,
        enableSMB_always = true,
        enableSMB_after_carbs = false,
        maxSMBBasalMinutes = 30,
        maxUAMSMBBasalMinutes = 30,
        bolus_increment = 0.05,
        carbsReqThreshold = 1,
        current_basal = 1.0,
        temptargetSet = false,
        autosens_max = 1.2,
        out_units = "mg/dL",
        lgsThreshold = 70,
        variable_sens = 50.0,
        insulinDivisor = 55,
        TDD = 40.0
    )
}
