package app.aaps.plugins.main.iob

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalSMB
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
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
