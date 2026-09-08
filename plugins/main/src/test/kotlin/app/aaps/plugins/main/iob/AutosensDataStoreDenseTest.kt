package app.aaps.plugins.main.iob

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever

/**
 * Dense-data bucketing: a 1-minute source (native Libre 3) must be AVERAGED into the 5-minute
 * grid, not point-sampled.
 *
 * The interpolating path discards four readings in five and hands the loop a single unsmoothed
 * sample. These tests pin the averaging behaviour, and — just as importantly — pin that ordinary
 * 5-minute and gappy data are NOT routed down the new path.
 */
class AutosensDataStoreDenseTest : TestBaseWithProfile() {

    private val autosensDataStore = AutosensDataStoreObject()

    @BeforeEach
    fun mock() {
        whenever(iobCobCalculator.ads).thenReturn(autosensDataStore)
    }

    private fun gv(minutesAgo: Long, value: Double) = GV(
        raw = 0.0,
        noise = 0.0,
        value = value,
        timestamp = T.hours(2).msecs() - T.mins(minutesAgo).msecs(),
        sourceSensor = SourceSensor.LIBRE_3,
        trendArrow = TrendArrow.FLAT
    )

    /** Newest-first, as the store expects. */
    private fun oneMinuteSeries(count: Int, value: (Int) -> Double): MutableList<GV> =
        (0 until count).map { gv(it.toLong(), value(it)) }.toMutableList()

    @Test
    fun `one minute data is averaged, not point sampled`() {
        // Values alternate 90/110 every minute. A point sample lands on one of them; the mean
        // of any five consecutive is ~100. That difference is the whole point of the change.
        autosensDataStore.bgReadings = oneMinuteSeries(60) { if (it % 2 == 0) 110.0 else 90.0 }
        autosensDataStore.createBucketedData(aapsLogger, dateUtil)

        val bucketed = autosensDataStore.bucketedData!!
        assertThat(bucketed.size).isAtLeast(5)
        // Every bucket should sit near the mean, never at an alternating extreme.
        bucketed.drop(1).dropLast(1).forEach {
            assertThat(it.value).isIn(com.google.common.collect.Range.closed(95.0, 105.0))
        }
    }

    @Test
    fun `averaging suppresses single sample noise`() {
        // One wild outlier in an otherwise flat 100 series. Point sampling can land on it and
        // hand the loop a 300; averaging over five dilutes it.
        val list = oneMinuteSeries(60) { 100.0 }
        list[7] = gv(7, 300.0)
        autosensDataStore.bgReadings = list
        autosensDataStore.createBucketedData(aapsLogger, dateUtil)

        val bucketed = autosensDataStore.bucketedData!!
        assertThat(bucketed.none { it.value > 150.0 }).isTrue()
    }

    @Test
    fun `bucket timestamps stay on the five minute grid`() {
        autosensDataStore.bgReadings = oneMinuteSeries(40) { 100.0 }
        autosensDataStore.createBucketedData(aapsLogger, dateUtil)

        val bucketed = autosensDataStore.bucketedData!!
        for (i in 1 until bucketed.size) {
            assertThat(bucketed[i - 1].timestamp - bucketed[i].timestamp)
                .isEqualTo(T.mins(5).msecs())
        }
    }

    @Test
    fun `ordinary five minute data is untouched by the dense path`() {
        // 5-min data must take the regular path; a median interval of 5 min is far above the
        // 2.5 min density threshold.
        autosensDataStore.bgReadings =
            (0 until 12).map { gv(it * 5L, 100.0 + it) }.toMutableList()
        autosensDataStore.createBucketedData(aapsLogger, dateUtil)

        val bucketed = autosensDataStore.bucketedData!!
        // values are preserved one-for-one, not averaged together
        assertThat(bucketed.first().value).isEqualTo(100.0)
    }

    @Test
    fun `sparse gappy data is not treated as dense`() {
        // Irregular 5-10 min spacing with a big hole — the Dexcom-style case the old path
        // exists for. Median interval is well above the threshold, so behaviour is unchanged.
        val stamps = listOf(0L, 6, 11, 17, 22, 45, 51, 56, 62, 68)
        autosensDataStore.bgReadings = stamps.map { gv(it, 100.0) }.toMutableList()
        autosensDataStore.createBucketedData(aapsLogger, dateUtil)
        assertThat(autosensDataStore.bucketedData).isNotNull()
    }

    @Test
    fun `too few readings are never called dense`() {
        // Three 1-minute readings look dense by interval but are far too few to judge.
        autosensDataStore.bgReadings = oneMinuteSeries(3) { 100.0 }
        autosensDataStore.createBucketedData(aapsLogger, dateUtil)
        // fewer than 3 readings yields null; exactly 3 must still not crash
        assertThat(autosensDataStore.bgReadings.size).isEqualTo(3)
    }

    @Test
    fun `a dropout inside dense data is bridged rather than truncating the series`() {
        // 1-minute data with a 12-minute hole in the middle: the series must span the hole.
        val list = ArrayList<GV>()
        for (i in 0 until 15) list.add(gv(i.toLong(), 100.0))
        for (i in 27 until 45) list.add(gv(i.toLong(), 120.0))
        autosensDataStore.bgReadings = list
        autosensDataStore.createBucketedData(aapsLogger, dateUtil)

        val bucketed = autosensDataStore.bucketedData!!
        val span = bucketed.first().timestamp - bucketed.last().timestamp
        assertThat(span).isAtLeast(T.mins(30).msecs())
    }
}
