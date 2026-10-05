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
    fun `dense replay includes duplicate readings in centered means`() {
        val readings = (0L..11).map { reading(it, 100.0 + 10 * it) }.toMutableList()
        readings.add(6, reading(5, 250.0))

        // Newest slot has 3 samples; the middle has 6 including the duplicate; the oldest has 4.
        assertThat(replay(readings)).containsExactly(
            bucket(0, 110.0), bucket(5, 1000.0 / 6), bucket(10, 195.0)
        ).inOrder()
    }

    @Test
    fun `dense replay bridges empty slot without changing sample provenance`() {
        val readings = (0L..5).map { reading(it, 100.0 + 10 * it) } +
            (17L..22).map { reading(it, 100.0 + 10 * it) }

        assertThat(replay(readings)).containsExactly(
            bucket(0, 110.0), bucket(5, 140.0), bucket(10, 200.0, true), bucket(15, 270.0), bucket(20, 300.0)
        ).inOrder()
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
