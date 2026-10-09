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
import app.aaps.database.persistence.converters.fromDb
import app.aaps.database.persistence.converters.toDb
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.TimeZone

class LoopGridDosingReplayTest : TestBaseWithProfile() {

    private fun replay(
        glucose: Double,
        lastBolusAgeMinutes: Long? = null,
        glucoseAt: (Long) -> Double = { glucose },
        expectedStatuses: Map<Long, Pair<Double, Double>> = emptyMap()
    ): List<RT> {
        val start = now
        var store = AutosensDataStoreObject()
        val results = mutableListOf<RT>()
        var lastTriggeredTimestamp = 0L
        for (minute in 0L..10L) {
            now = start + T.mins(minute).msecs()
            whenever(dateUtil.now()).thenReturn(now)
            store.bgReadings = (minute downTo minute - 30L).map {
                val value = glucoseAt(it)
                GV(
                    timestamp = start + T.mins(it).msecs(), value = value, raw = value,
                    noise = 0.0, sourceSensor = SourceSensor.LIBRE_2, trendArrow = TrendArrow.FLAT
                )
            }
            store.createBucketedData(aapsLogger, dateUtil)
            store = store.clone() as AutosensDataStoreObject
            val bucketTimestamp = store.lastBg()!!.timestamp
            if (bucketTimestamp <= lastTriggeredTimestamp) continue
            lastTriggeredTimestamp = bucketTimestamp
            whenever(iobCobCalculator.ads).thenReturn(store)
            val status = glucoseStatusCalculatorSMB.getGlucoseStatusData(false)!!
            expectedStatuses[minute]?.let { (expectedGlucose, expectedDelta) ->
                assertThat(status.date).isEqualTo(bucketTimestamp)
                assertThat(status.glucose).isEqualTo(expectedGlucose)
                assertThat(status.delta).isEqualTo(expectedDelta)
            }
            // Zero-insulin predictions and explicit limits isolate grid-to-decision behavior.
            results.add(
                DetermineBasalSMB(profileUtil, fabricPrivacy).determine_basal(
                    glucose_status = status,
                    currenttemp = CurrentTemp(duration = 0, rate = 1.0, minutesrunning = 0),
                    iob_data_array = Array(48) { step ->
                        val time = now + T.mins(step * 5L).msecs()
                        IobTotal(time = time, iobWithZeroTemp = IobTotal(time = time)).also {
                            it.lastBolusTime = lastBolusAgeMinutes?.let { age -> now - T.mins(age).msecs() } ?: 0L
                        }
                    },
                    profile = profile(),
                    autosens_data = AutosensResult(),
                    meal_data = MealData(),
                    microBolusAllowed = true,
                    currentTime = now,
                    flatBGsDetected = false,
                    dynIsfMode = false
                )
            )
        }
        assertThat(results).hasSize(3)
        return results
    }

    @Test
    fun `low glucose remains zero basal with no SMB across published calculations`() {
        replay(55.0).forEach {
            assertThat(it.rate).isEqualTo(0.0)
            assertThat(it.units).isNull()
        }
    }

    @Test
    fun `high glucose decisions remain within explicit basal and SMB limits`() {
        replay(180.0).forEach {
            assertThat(it.units!!).isGreaterThan(0.0)
            assertThat(it.units!!).isAtMost(0.5) // 1 U/h basal, 30-minute SMB cap.
            assertThat(it.rate!!).isAtLeast(0.0)
            assertThat(it.rate!!).isAtMost(3.0)
        }
    }

    @Test
    fun `a recent bolus still prevents SMB at each eligible grid slot`() {
        replay(180.0, lastBolusAgeMinutes = 1).forEach { assertThat(it.units).isNull() }
    }

    @Test
    fun `APS persistence replay keeps decisions and delivery deadline through Warsaw transitions`() {
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"))
            listOf("2026-03-29T00:55:00Z", "2026-10-25T00:55:00Z").forEach { start ->
                listOf(55.0, 180.0).forEach { glucose ->
                    now = Instant.parse(start).toEpochMilli()
                    replay(glucose).forEachIndexed { index, decision ->
                        val decisionTime = Instant.parse(start).toEpochMilli() + index * 300_000L
                        // Exercise the actual APS database converters, not just a JSON round trip.
                        val original = apsResultProvider.get().with(decision).also { it.algorithm = app.aaps.core.interfaces.aps.APSResult.Algorithm.SMB }
                        val restored = original.toDb().fromDb(apsResultProvider)
                        val persisted = restored.rawData() as RT
                        assertThat(restored.deliverAt).isEqualTo(original.deliverAt)
                        assertThat(restored.smb).isEqualTo(original.smb)
                        assertThat(persisted.timestamp).isEqualTo(decision.timestamp)
                        assertThat(persisted.deliverAt).isEqualTo(decision.deliverAt)
                        assertThat(persisted.rate).isEqualTo(decision.rate)
                        assertThat(persisted.units).isEqualTo(decision.units)
                        assertThat(persisted.duration).isEqualTo(decision.duration)
                        assertThat(persisted.IOB).isEqualTo(decision.IOB)
                        assertThat(persisted.reason.toString()).isEqualTo(decision.reason.toString())
                        if (glucose == 55.0) {
                            assertThat(persisted.rate).isEqualTo(0.0)
                            assertThat(persisted.units).isNull()
                        } else {
                            assertThat(persisted.units!!).isAtMost(0.5)
                            assertThat(persisted.deliverAt).isEqualTo(decisionTime)
                            // SMB queue's one-minute expiry must not move after persistence.
                            assertThat(persisted.deliverAt!! + 60_000).isEqualTo(decisionTime + 60_000)
                        }
                    }
                }
            }
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun `rising then low glucose advances measured values and deltas across published slots`() {
        // Three measured plateaus keep expectations identical for interpolation and the
        // centered averaging that #171 removes: slot 0=100, slot 5=150, slot 10=55.
        // At those boundaries the five-minute deltas must be 0, +50 and -95 mg/dL.
        val results = replay(
            glucose = 100.0,
            glucoseAt = { minute ->
                when {
                    minute < 3 -> 100.0
                    minute < 8 -> 150.0
                    else       -> 55.0
                }
            },
            expectedStatuses = mapOf(0L to (100.0 to 0.0), 5L to (150.0 to 50.0), 10L to (55.0 to -95.0))
        )

        assertThat(results.map { it.bg }).containsExactly(100.0, 150.0, 55.0).inOrder()
        assertThat(results.last().rate).isEqualTo(0.0)
        assertThat(results.last().units).isNull()
    }

    @Test
    fun `stale and absent bucket history are rejected by glucose status`() {
        val store = AutosensDataStoreObject()
        whenever(iobCobCalculator.ads).thenReturn(store)
        assertThat(glucoseStatusCalculatorSMB.getGlucoseStatusData(false)).isNull()
        store.bgReadings = (0L..30L).map {
            GV(
                timestamp = now - T.mins(10 + it).msecs(), value = 180.0, raw = 180.0,
                noise = 0.0, sourceSensor = SourceSensor.LIBRE_2, trendArrow = TrendArrow.FLAT
            )
        }
        store.createBucketedData(aapsLogger, dateUtil)
        whenever(iobCobCalculator.ads).thenReturn(store.clone())
        assertThat(glucoseStatusCalculatorSMB.getGlucoseStatusData(false)).isNull()
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
