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
        val delta: Double = 2.0,
        val iob: Double = 0.5,
        val activity: Double = 0.005,
        val meal: MealData = MealData(),
        val profile: OapsProfile = profile(),
        val autosensRatio: Double = 1.0,
        val currentTemp: CurrentTemp = CurrentTemp(duration = 0, rate = 1.0, minutesrunning = 0),
        // Dynamic ISF for SMB, AutoISF for AutoISF
        val sensitivityMode: Boolean = false
    )

    private val smb by lazy { DetermineBasalSMB(profileUtil, fabricPrivacy) }
    private val autoIsf by lazy { DetermineBasalAutoISF(profileUtil) }

    private fun run(algorithm: Algorithm, inputs: Inputs, smb: DetermineBasalSMB = this.smb, autoIsf: DetermineBasalAutoISF = this.autoIsf): RT {
        val iobArray = Array(48) { step ->
            val time = now + T.mins(step * 5L).msecs()
            IobTotal(time = time, iob = inputs.iob, activity = inputs.activity, iobWithZeroTemp = IobTotal(time = time, iob = inputs.iob, activity = inputs.activity))
        }
        val autosens = AutosensResult(ratio = inputs.autosensRatio)
        return when (algorithm) {
            Algorithm.SMB      -> smb.determine_basal(
                GlucoseStatusSMB(glucose = inputs.bg, delta = inputs.delta, shortAvgDelta = inputs.delta, longAvgDelta = inputs.delta, date = now),
                inputs.currentTemp, iobArray, inputs.profile, autosens, inputs.meal, true, now, false, inputs.sensitivityMode
            )

            Algorithm.AUTO_ISF -> autoIsf.determine_basal(
                GlucoseStatusAutoIsf(glucose = inputs.bg, delta = inputs.delta, shortAvgDelta = inputs.delta, longAvgDelta = inputs.delta, date = now),
                inputs.currentTemp, iobArray, autoIsfProfile(inputs.profile), autosens, inputs.meal,
                true, now, false, inputs.sensitivityMode, "AAPS", 100, 0.5, 1.0, 100, mutableListOf(), mutableListOf()
            )
        }
    }

    private fun assertAbortedWithoutDosing(rT: RT, reason: String) {
        assertThat(rT.reason.toString()).startsWith("Aborting run: $reason")
        assertThat(rT.rate).isNull()
        assertThat(rT.duration).isNull()
        assertThat(rT.units).isNull()
        assertThat(rT.predBGs).isNull()
    }

    @ParameterizedTest
    @EnumSource(Algorithm::class)
    fun `COB left after carbs leave the window gives a finite decision`(algorithm: Algorithm) {
        val rT = run(algorithm, Inputs(meal = MealData(carbs = 0.0, mealCOB = 20.0, slopeFromMinDeviation = 999.0)))

        assertThat(rT.reason.toString()).doesNotContain("Aborting")
        assertThat(rT.reason.toString()).doesNotContain("NaN")
        assertThat(rT.eventualBG!!.isFinite()).isTrue()
        assertThat(rT.insulinReq!!.isFinite()).isTrue()
        assertThat(rT.predBGs!!.UAM).isNotEmpty()
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("untrustedInputs")
    fun `untrusted input aborts the run without dosing`(algorithm: Algorithm, reason: String, inputs: Inputs) {
        assertAbortedWithoutDosing(run(algorithm, inputs), reason)
    }

    @ParameterizedTest
    @EnumSource(Algorithm::class)
    fun `overflow inside the calculation aborts the run without dosing`(algorithm: Algorithm) {
        // Finite inputs, but IOB × ISF overflows to -Infinity in the eventual BG.
        assertAbortedWithoutDosing(run(algorithm, Inputs(iob = 1e308)), "rounded value=-Infinity")
    }

    @ParameterizedTest
    @EnumSource(Algorithm::class)
    fun `aborted run replaces a running high temp with the profile basal`(algorithm: Algorithm) {
        val rT = run(algorithm, Inputs(autosensRatio = Double.NaN, currentTemp = CurrentTemp(duration = 30, rate = 3.0, minutesrunning = 5)))

        assertThat(rT.reason.toString()).startsWith("Aborting run: autosens ratio=NaN")
        assertThat(rT.rate).isEqualTo(1.0)
        assertThat(rT.duration).isEqualTo(30)
        assertThat(rT.units).isNull()
    }

    @ParameterizedTest
    @EnumSource(Algorithm::class)
    fun `aborted run shortens a long zero temp to 30 minutes`(algorithm: Algorithm) {
        val rT = run(algorithm, Inputs(autosensRatio = Double.NaN, currentTemp = CurrentTemp(duration = 90, rate = 0.0, minutesrunning = 5)))

        assertThat(rT.rate).isEqualTo(0.0)
        assertThat(rT.duration).isEqualTo(30)
        assertThat(rT.units).isNull()
    }

    @ParameterizedTest
    @EnumSource(Algorithm::class)
    fun `a valid run after an aborted one is unaffected`(algorithm: Algorithm) {
        val expected = run(algorithm, Inputs(), DetermineBasalSMB(profileUtil, fabricPrivacy), DetermineBasalAutoISF(profileUtil))
        run(algorithm, Inputs(profile = profile().copy(sens = Double.NaN)))
        val afterAbort = run(algorithm, Inputs())

        assertThat(expected.units).isGreaterThan(0.0)
        assertThat(afterAbort.reason.toString()).isEqualTo(expected.reason.toString())
        assertThat(afterAbort.rate).isEqualTo(expected.rate)
        assertThat(afterAbort.duration).isEqualTo(expected.duration)
        assertThat(afterAbort.units).isEqualTo(expected.units)
    }

    companion object {

        @JvmStatic
        fun untrustedInputs(): List<Arguments> = Algorithm.entries.flatMap { algorithm ->
            listOf(
                "bg=NaN" to Inputs(bg = Double.NaN),
                "delta=Infinity" to Inputs(delta = Double.POSITIVE_INFINITY),
                "iob=NaN" to Inputs(iob = Double.NaN),
                "activity=NaN" to Inputs(activity = Double.NaN),
                "mealCOB=Infinity" to Inputs(meal = MealData(carbs = 20.0, mealCOB = Double.POSITIVE_INFINITY)),
                "sens=NaN" to Inputs(profile = profile().copy(sens = Double.NaN)),
                "sens=0.0" to Inputs(profile = profile().copy(sens = 0.0)),
                "sens=-50.0" to Inputs(profile = profile().copy(sens = -50.0)),
                "carb_ratio=0.0" to Inputs(profile = profile().copy(carb_ratio = 0.0)),
                "carb_ratio=Infinity" to Inputs(profile = profile().copy(carb_ratio = Double.POSITIVE_INFINITY)),
                "autosens ratio=0.0" to Inputs(autosensRatio = 0.0),
                "autosens ratio=Infinity" to Inputs(autosensRatio = Double.POSITIVE_INFINITY),
                "max_basal=NaN" to Inputs(profile = profile().copy(max_basal = Double.NaN)),
                "variable_sens=NaN" to Inputs(profile = profile().copy(variable_sens = Double.NaN), sensitivityMode = true),
                "variable_sens=0.0" to Inputs(profile = profile().copy(variable_sens = 0.0), sensitivityMode = true)
            ).map { (reason, inputs) -> Arguments.of(algorithm, reason, inputs) }
        }

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
