package app.aaps.plugins.main.iob

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.whenever

class AutosensDataStoreBucketingReplayTest : TestBaseWithProfile() {

    private fun reading(minutesAgo: Long, value: Double) = GV(
        timestamp = T.hours(2).msecs() - T.mins(minutesAgo).msecs(),
        value = value,
        raw = value,
        noise = 0.0,
        sourceSensor = SourceSensor.LIBRE_3,
        trendArrow = TrendArrow.FLAT
    )

    private fun bucket(minutesAgo: Long, value: Double, filledGap: Boolean = false, trendArrow: TrendArrow = TrendArrow.NONE) = InMemoryGlucoseValue(
        timestamp = T.hours(2).msecs() - T.mins(minutesAgo).msecs(),
        value = value,
        trendArrow = trendArrow,
        filledGap = filledGap,
        sourceSensor = SourceSensor.LIBRE_3
    )

    private fun replay(readings: List<GV>): List<InMemoryGlucoseValue>? =
        AutosensDataStoreObject().also {
            it.bgReadings = readings
            it.createBucketedData(aapsLogger, dateUtil)
        }.bucketedData

    @Test
    fun `five minute replay preserves samples and interpolates missing slot`() {
        val readings = listOf(reading(0, 100.0), reading(5, 110.0), reading(15, 150.0), reading(20, 160.0))

        assertThat(replay(readings)).containsExactly(
            bucket(0, 100.0, trendArrow = TrendArrow.FLAT), bucket(5, 110.0), bucket(10, 130.0, true), bucket(15, 150.0), bucket(20, 160.0)
        ).inOrder()
    }

    @Test
    fun `five minute duplicate samples retain existing averaging`() {
        val readings = listOf(reading(0, 100.0), reading(5, 110.0), reading(5, 130.0), reading(10, 140.0), reading(15, 150.0))

        assertThat(replay(readings)).containsExactly(
            bucket(0, 100.0, trendArrow = TrendArrow.FLAT), bucket(5, 120.0), bucket(10, 140.0), bucket(15, 150.0)
        ).inOrder()
    }

    @Test
    fun `irregular replay preserves interpolation rounding and gap flags`() {
        val readings = listOf(reading(0, 100.0), reading(6, 107.0), reading(11, 130.0))

        // At minute 5: 100 + 7 * 5/6; at minute 10: 107 + 23 * 4/5, rounded to mg/dL.
        assertThat(replay(readings)).containsExactly(
            bucket(0, 100.0, trendArrow = TrendArrow.FLAT), bucket(5, 106.0, true), bucket(10, 125.0, true)
        ).inOrder()
    }

    @Test
    fun `one minute replay preserves grid samples rather than averaging nearby duplicates`() {
        val readings = (0L..11).map { reading(it, 100.0 + 10 * it) }.toMutableList()
        readings.add(6, reading(5, 250.0))

        // All three grid timestamps have measured values. An off-grid reading or a second
        // record at minute 5 must not replace those values with a centered-window mean.
        assertThat(replay(readings)).containsExactly(
            bucket(0, 100.0, trendArrow = TrendArrow.FLAT),
            bucket(5, 150.0, trendArrow = TrendArrow.FLAT),
            bucket(10, 200.0, trendArrow = TrendArrow.FLAT)
        ).inOrder()
    }

    @Test
    fun `one minute replay marks interpolated gap slots and preserves measured slots`() {
        val readings = (0L..5).map { reading(it, 100.0 + 10 * it) } +
            (17L..22).map { reading(it, 100.0 + 10 * it) }

        assertThat(replay(readings)).containsExactly(
            bucket(0, 100.0, trendArrow = TrendArrow.FLAT),
            bucket(5, 150.0, trendArrow = TrendArrow.FLAT),
            bucket(10, 200.0, true), bucket(15, 250.0, true),
            bucket(20, 300.0, trendArrow = TrendArrow.FLAT)
        ).inOrder()
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 5])
    fun `reporting frequency does not add a centered average`(intervalMinutes: Int) {
        val readings = (0L..30L step intervalMinutes.toLong()).map { reading(it, 100.0 + 2 * it) }

        // The same linear signal has the same grid values at every reporting frequency:
        // exact samples for one/five-minute feeds, interpolation where two/three-minute
        // feeds have no measurement at a grid timestamp.
        assertThat(replay(readings)?.map { it.timestamp to it.value }).containsExactlyElementsIn(
            (0L..30L step 5).map { reading(it, 100.0 + 2 * it).let { gv -> gv.timestamp to gv.value } }
        ).inOrder()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `one minute replay passes recent rise or fall to dosing glucose status`(rising: Boolean) {
        val latest = if (rising) 180.0 else 140.0
        val older = if (rising) 140.0 else 180.0
        val readings = (0L..45L).map { minute ->
            reading(minute, when (minute) {
                0L   -> latest
                1L   -> if (rising) 170.0 else 150.0
                2L   -> 160.0
                else -> older
            })
        }
        val store = AutosensDataStoreObject().also {
            it.bgReadings = readings
            it.createBucketedData(aapsLogger, dateUtil)
        }
        whenever(iobCobCalculator.ads).thenReturn(store)
        whenever(dateUtil.now()).thenReturn(readings.first().timestamp)

        val status = glucoseStatusCalculatorSMB.getGlucoseStatusData(false)!!

        // The previous five-minute reading is the old plateau, so the last delta is
        // exactly +/-40 mg/dL per five minutes. A centered newest mean would soften it.
        assertThat(status.glucose).isEqualTo(latest)
        assertThat(status.delta).isEqualTo(latest - older)
        assertThat(status.date).isEqualTo(readings.first().timestamp)

        whenever(dateUtil.now()).thenReturn(readings.first().timestamp + T.mins(7).msecs() + 1)
        assertThat(glucoseStatusCalculatorSMB.getGlucoseStatusData(false)).isNull()
    }

    @Test
    fun `five minute samples stay measured with older one minute history`() {
        val readings = listOf(reading(0, 140.0), reading(5, 150.0), reading(10, 160.0)) +
            (11L..30L).map { reading(it, 180.0) }

        assertThat(replay(readings)?.take(3)).containsExactly(
            bucket(0, 140.0, trendArrow = TrendArrow.FLAT),
            bucket(5, 150.0, trendArrow = TrendArrow.FLAT),
            bucket(10, 160.0, trendArrow = TrendArrow.FLAT)
        ).inOrder()
    }

    @Test
    fun `low error reading is not hidden by averaging adjacent valid readings`() {
        val readings = (0L..10L).map { reading(it, if (it == 0L) 38.0 else 150.0) }

        assertThat(replay(readings)?.first()).isEqualTo(bucket(0, 38.0, trendArrow = TrendArrow.FLAT))
    }

    @Test
    fun `later off-grid reading does not rewrite an existing measured bucket within the same store`() {
        val readings = (0L..15L).map { reading(it, 100.0 + 10 * it) }
        val store = AutosensDataStoreObject().also {
            it.bgReadings = readings
            it.createBucketedData(aapsLogger, dateUtil)
        }
        assertThat(store.bucketedData?.first()).isEqualTo(bucket(0, 100.0, trendArrow = TrendArrow.FLAT))

        store.bgReadings = listOf(reading(-1, 90.0)) + readings
        store.createBucketedData(aapsLogger, dateUtil)

        // Same-instance only: a production clone does not retain the reference time.
        // The established grid still starts at minute 0. Its measurement remains 100,
        // rather than changing as the new minute joins a centered averaging window.
        assertThat(store.bucketedData?.first()).isEqualTo(bucket(0, 100.0, trendArrow = TrendArrow.FLAT))
    }

    @Test
    fun `five minute normalization fallback preserves reference and gap boundary`() {
        // Each interval is within 30 seconds of five minutes, but accumulated normalization
        // exceeds 90 seconds and falls back to interpolation on the oldest reading's phase.
        val readings = (0L..4).map {
            reading(0, 100.0 + 11 * it).also { gv -> gv.timestamp -= it * T.secs(270).msecs() }
        }

        assertThat(replay(readings)).containsExactly(
            bucket(3, 107.0, true), bucket(8, 120.0, true), bucket(13, 132.0),
            bucket(18, 144.0, trendArrow = TrendArrow.FLAT)
        ).inOrder()
    }

    @Test
    fun `duplicate search ties preserve selected sample at interior and oldest timestamps`() {
        val readings = listOf(reading(0, 100.0), reading(5, 110.0), reading(5, 120.0), reading(10, 130.0), reading(10, 140.0))
        val store = AutosensDataStoreObject().also { it.bgReadings = readings }

        assertThat(store.findNewer(readings[1].timestamp)).isSameInstanceAs(readings[1])
        assertThat(store.findOlder(readings[1].timestamp)).isSameInstanceAs(readings[2])
        assertThat(store.findNewer(readings[3].timestamp)).isSameInstanceAs(readings[3])
        assertThat(store.findOlder(readings[3].timestamp)).isSameInstanceAs(readings[3])
        assertThat(store.findNewer(readings.first().timestamp + 1)).isNull()
        assertThat(store.findOlder(readings.last().timestamp - 1)).isNull()
    }

    @Test
    fun `missing or insufficient readings produce no buckets`() {
        assertThat(replay(emptyList())).isNull()
        assertThat(replay(listOf(reading(0, 100.0), reading(5, 110.0)))).isNull()
    }
}
