package app.aaps.plugins.aps.openAPS

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAutoISF.DetermineBasalAutoISF
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalSMB
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource

class DetermineBasalUntrustedValueTest : TestBaseWithProfile() {

    enum class Algorithm { SMB, AUTO_ISF }

    data class Inputs(
        val bg: Double = 180.0,
        val iob: Double = 0.5,
        val activity: Double = 0.005,
        val meal: MealData = MealData(),
        val profile: OapsProfile = profile(),
        val autosensRatio: Double = 1.0,
        val currentTemp: CurrentTemp = CurrentTemp(duration = 0, rate = 1.0, minutesrunning = 0),
        // Dynamic ISF for SMB, AutoISF for AutoISF
        val sensitivityMode: Boolean = false,
        val autoIsfSmbRatio: Double = 0.5
    )

    private val highTemp = CurrentTemp(duration = 30, rate = 3.0, minutesrunning = 5)

    private fun run(algorithm: Algorithm, inputs: Inputs): RT {
        val iobArray = Array(48) { step ->
            val time = now + T.mins(step * 5L).msecs()
            IobTotal(time = time, iob = inputs.iob, activity = inputs.activity, iobWithZeroTemp = IobTotal(time = time, iob = inputs.iob, activity = inputs.activity))
        }
        val autosens = AutosensResult(ratio = inputs.autosensRatio)
        return when (algorithm) {
            Algorithm.SMB      -> DetermineBasalSMB(profileUtil, fabricPrivacy).determine_basal(
                GlucoseStatusSMB(glucose = inputs.bg, delta = 2.0, shortAvgDelta = 2.0, longAvgDelta = 2.0, date = now),
                inputs.currentTemp, iobArray, inputs.profile, autosens, inputs.meal, true, now, false, inputs.sensitivityMode
            )

            Algorithm.AUTO_ISF -> DetermineBasalAutoISF(profileUtil).determine_basal(
                GlucoseStatusAutoIsf(glucose = inputs.bg, delta = 2.0, shortAvgDelta = 2.0, longAvgDelta = 2.0, date = now),
                inputs.currentTemp, iobArray, autoIsfProfile(inputs.profile), autosens, inputs.meal,
                true, now, false, inputs.sensitivityMode, "AAPS", 100, inputs.autoIsfSmbRatio, 1.0, 100, mutableListOf(), mutableListOf()
            )
        }
    }

    private fun assertAborted(rT: RT, reason: String, rate: Double? = null, duration: Int? = null) {
        assertThat(rT.reason.toString()).startsWith("Aborting run: $reason")
        assertThat(rT.rate).isEqualTo(rate)
        assertThat(rT.duration).isEqualTo(duration)
        assertThat(rT.units).isNull()
    }

    @ParameterizedTest
    @EnumSource(Algorithm::class)
    fun `COB left after its carbs leave the window does not abort the run or lift the low guard`(algorithm: Algorithm) {
        fun minGuardBG(rT: RT) = Regex("minGuardBG ([^,]+)").find(rT.reason)!!.groupValues[1]
        val rT = run(algorithm, Inputs(meal = MealData(carbs = 0.0, mealCOB = 20.0, slopeFromMinDeviation = 999.0)))
        val withoutCob = run(algorithm, Inputs(meal = MealData(carbs = 0.0, mealCOB = 0.0, slopeFromMinDeviation = 999.0)))

        assertThat(rT.reason.toString()).doesNotContain("Aborting")
        assertThat(minGuardBG(rT)).isEqualTo(minGuardBG(withoutCob))
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("untrustedInputs")
    fun `untrusted input aborts the run without dosing`(algorithm: Algorithm, reason: String, inputs: Inputs) {
        assertAborted(run(algorithm, inputs), reason)
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("overflowingInputs")
    fun `a value too large to round aborts the run without dosing`(algorithm: Algorithm, case: String, inputs: Inputs) {
        assertAborted(run(algorithm, inputs), "rounded value")
    }

    @ParameterizedTest
    @EnumSource(Algorithm::class)
    fun `aborted run replaces a running high temp with the profile basal`(algorithm: Algorithm) {
        val rT = run(algorithm, Inputs(autosensRatio = Double.NaN, currentTemp = highTemp))

        assertAborted(rT, "autosens ratio=NaN", rate = 1.0, duration = 30)
    }

    @ParameterizedTest
    @EnumSource(Algorithm::class)
    fun `aborted run shortens a long zero temp to 30 minutes`(algorithm: Algorithm) {
        val rT = run(algorithm, Inputs(autosensRatio = Double.NaN, currentTemp = CurrentTemp(duration = 90, rate = 0.0, minutesrunning = 5)))

        assertAborted(rT, "autosens ratio=NaN", rate = 0.0, duration = 30)
    }

    @ParameterizedTest
    @EnumSource(Algorithm::class)
    fun `aborted run cancels a running temp when the basal cannot be trusted`(algorithm: Algorithm) {
        for (basal in listOf(0.0, Double.POSITIVE_INFINITY)) {
            val rT = run(algorithm, Inputs(profile = profile().copy(current_basal = basal), currentTemp = highTemp))

            assertAborted(rT, "current_basal=$basal", rate = 0.0, duration = 0)
        }
    }

    @ParameterizedTest
    @EnumSource(Algorithm::class)
    fun `aborted run cancels a running temp when its rate cannot be trusted`(algorithm: Algorithm) {
        val rT = run(algorithm, Inputs(currentTemp = highTemp.copy(rate = Double.NaN)))

        assertAborted(rT, "currenttemp.rate=NaN", rate = 0.0, duration = 0)
    }

    companion object {

        private fun forEachAlgorithm(vararg cases: Pair<String, Inputs>): List<Arguments> =
            Algorithm.entries.flatMap { algorithm -> cases.map { (name, inputs) -> Arguments.of(algorithm, name, inputs) } }

        @JvmStatic
        fun untrustedInputs() = forEachAlgorithm(
            "bg=NaN" to Inputs(bg = Double.NaN),
            "iob=NaN" to Inputs(iob = Double.NaN),
            "mealCOB=Infinity" to Inputs(meal = MealData(carbs = 20.0, mealCOB = Double.POSITIVE_INFINITY)),
            "sens=-50.0" to Inputs(profile = profile().copy(sens = -50.0)),
            "carb_ratio=Infinity" to Inputs(profile = profile().copy(carb_ratio = Double.POSITIVE_INFINITY)),
            "max_basal=NaN" to Inputs(profile = profile().copy(max_basal = Double.NaN)),
            // No temp is running, so the fallback must not set one from the rejected basal.
            "current_basal=0.0" to Inputs(profile = profile().copy(current_basal = 0.0)),
            "variable_sens=0.0" to Inputs(profile = profile().copy(variable_sens = 0.0), sensitivityMode = true)
        ) + Arguments.of(Algorithm.AUTO_ISF, "smb_ratio=Infinity", Inputs(autoIsfSmbRatio = Double.POSITIVE_INFINITY))

        // Finite inputs whose results cannot be rounded: IOB × ISF overflows to -Infinity, and the BG impact of the activity
        // stays finite but exceeds a Long once scaled, where Math.round would saturate instead of failing.
        @JvmStatic
        fun overflowingInputs() = forEachAlgorithm(
            "iob=1e308" to Inputs(iob = 1e308),
            "activity=-1e15" to Inputs(activity = -1e15)
        )

        fun profile() = OapsProfile(
            dia = 5.0, min_5m_carbimpact = 8.0, max_iob = 3.0, max_daily_basal = 1.0, max_basal = 3.0,
            min_bg = 100.0, max_bg = 100.0, target_bg = 100.0, carb_ratio = 10.0, sens = 50.0,
            autosens_adjust_targets = false, max_daily_safety_multiplier = 3.0, current_basal_safety_multiplier = 4.0,
            high_temptarget_raises_sensitivity = false, low_temptarget_lowers_sensitivity = false,
            sensitivity_raises_target = false, resistance_lowers_target = false, adv_target_adjustments = false,
            exercise_mode = false, half_basal_exercise_target = 160, maxCOB = 120, skip_neutral_temps = false,
            remainingCarbsCap = 90, enableUAM = true, A52_risk_enable = false, SMBInterval = 3,
            enableSMB_with_COB = false, enableSMB_with_temptarget = false, allowSMB_with_high_temptarget = false,
            enableSMB_always = true, enableSMB_after_carbs = false, maxSMBBasalMinutes = 30, maxUAMSMBBasalMinutes = 30,
            bolus_increment = 0.05, carbsReqThreshold = 1, current_basal = 1.0, temptargetSet = false, autosens_max = 1.2,
            out_units = "mg/dL", lgsThreshold = 70, variable_sens = 50.0, insulinDivisor = 55, TDD = 40.0
        )

        private fun autoIsfProfile(base: OapsProfile) = OapsProfileAutoIsf(
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
            variable_sens = base.variable_sens, autoISF_version = "3.0.1", enable_autoISF = true,
            autoISF_max = 1.5, autoISF_min = 0.7, bgAccel_ISF_weight = 0.1, bgBrake_ISF_weight = 0.1,
            pp_ISF_weight = 0.05, lower_ISFrange_weight = 1.0, higher_ISFrange_weight = 1.0, dura_ISF_weight = 0.5,
            smb_delivery_ratio = 0.5, smb_delivery_ratio_min = 0.5, smb_delivery_ratio_max = 0.5,
            smb_delivery_ratio_bg_range = 0.0, smb_max_range_extension = 1.0,
            enableSMB_EvenOn_OddOff_always = false, iob_threshold_percent = 100, profile_percentage = 100
        )
    }
}
