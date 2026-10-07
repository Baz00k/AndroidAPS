package app.aaps.plugins.main.iob

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.implementation.iob.AutosensDataObject
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AutosensDataStoreGridTest : TestBaseWithProfile() {

    private val start = T.hours(2).msecs()

    private fun reading(time: Long, value: Double = 100.0) = GV(
        timestamp = time,
        value = value,
        raw = value,
        noise = 0.0,
        sourceSensor = SourceSensor.LIBRE_2,
        trendArrow = TrendArrow.FLAT
    )

    @ParameterizedTest
    @ValueSource(longs = [1, 3, 5])
    fun `successive published calculations extend the same grid`(intervalMinutes: Long) {
        var store = AutosensDataStoreObject()
        val newestBuckets = mutableListOf<Long>()
        val cachedTime = start - T.mins(10).msecs()
        val cached = AutosensDataObject(aapsLogger, preferences, dateUtil).also { it.time = cachedTime }
        for (minute in 0L..15L step intervalMinutes) {
            store.bgReadings = (minute downTo minute - 60L step intervalMinutes).map { reading(start + T.mins(it).msecs()) }
            store.createBucketedData(aapsLogger, dateUtil)
            if (minute == 0L) store.autosensDataTable.put(cachedTime, cached)
            // Both IOB/COB workers replace the live store with their calculation clone.
            store = store.clone() as AutosensDataStoreObject
            val buckets = store.bucketedData!!
            newestBuckets.add(buckets.first().timestamp)
            assertThat(buckets.any { it.timestamp == cachedTime }).isTrue()
            assertThat(store.getAutosensDataAtTime(cachedTime)).isSameInstanceAs(cached)
        }

        // Expectations follow the fixed five-minute slots, not the incoming reading cadence.
        val expected = when (intervalMinutes) {
            1L   -> listOf(0L, 0, 0, 0, 0, 5, 5, 5, 5, 5, 10, 10, 10, 10, 10, 15)
            3L   -> listOf(0L, 0, 5, 5, 10, 15)
            else -> listOf(0L, 5, 10, 15)
        }.map { start + T.mins(it).msecs() }
        assertThat(newestBuckets).containsExactlyElementsIn(expected).inOrder()
    }

    @ParameterizedTest
    @ValueSource(longs = [-120, 120])
    fun `five minute sensor phase change takes the current readings phase`(offsetSeconds: Long) {
        val original = AutosensDataStoreObject().also {
            it.bgReadings = (0L..11L).map { i -> reading(start - T.mins(5 * i).msecs()) }
            it.createBucketedData(aapsLogger, dateUtil)
        }
        original.bgReadings = (0L..11L).map { i -> reading(start + T.mins(60 - 5 * i).msecs() + T.secs(offsetSeconds).msecs()) }

        original.createBucketedData(aapsLogger, dateUtil)

        assertThat(original.bucketedData!!.map { it.timestamp }).containsExactlyElementsIn(original.bgReadings.map { it.timestamp }).inOrder()
        assertThat(original.bucketedData!!.any { it.filledGap }).isFalse()
    }

    @Test
    fun `five minute normalization measures jitter separately from anchor shift`() {
        val store = AutosensDataStoreObject().also {
            it.referenceTime = start - T.mins(60).msecs() + T.secs(60).msecs()
            // 40 seconds of drift plus a 60-second anchor shift must not cause interpolation.
            it.bgReadings = (0L..11L).map { i ->
                val drift = when (i) {
                    0L   -> T.secs(40).msecs()
                    1L   -> T.secs(20).msecs()
                    else -> 0L
                }
                reading(start - T.mins(5 * i).msecs() - drift)
            }
        }

        store.createBucketedData(aapsLogger, dateUtil)

        assertThat(store.bucketedData!!.first().timestamp).isEqualTo(start + T.secs(60).msecs())
        assertThat(store.bucketedData!!.any { it.filledGap }).isFalse()
    }

    @Test
    fun `readings before a retained anchor stay on its grid`() {
        val store = AutosensDataStoreObject().also {
            it.referenceTime = start + T.secs(30).msecs()
            it.bgReadings = (0L..11L).map { i -> reading(start - T.mins(5 * i).msecs()) }
        }

        store.createBucketedData(aapsLogger, dateUtil)

        assertThat(store.bucketedData!!.map { it.timestamp }).containsExactlyElementsIn(
            (0L..11L).map { i -> start + T.secs(30).msecs() - T.mins(5 * i).msecs() }
        ).inOrder()
    }

    @ParameterizedTest
    @ValueSource(longs = [1, 30000, 30001])
    fun `interpolation retains a measured newest value only within the jitter tolerance`(offsetMillis: Long) {
        val store = AutosensDataStoreObject().also {
            it.referenceTime = start - T.mins(5).msecs() + offsetMillis
            // Four sparse readings force interpolation even before dense averaging is removed.
            it.bgReadings = listOf(0L, 5, 10, 17).map { minutes -> reading(start - T.mins(minutes).msecs(), 100.0 - minutes) }
        }

        store.createBucketedData(aapsLogger, dateUtil)

        val newest = store.bucketedData!!.first()
        if (offsetMillis <= T.secs(30).msecs()) {
            assertThat(newest.timestamp).isEqualTo(start + offsetMillis)
            assertThat(newest.value).isEqualTo(100.0)
            assertThat(newest.filledGap).isFalse()
        } else {
            assertThat(newest.timestamp).isEqualTo(start - T.mins(5).msecs() + offsetMillis)
        }
    }

    @Test
    fun `jitter must not invent a lone flat trend without historical buckets`() {
        val store = AutosensDataStoreObject().also {
            it.referenceTime = start - T.mins(5).msecs() + T.secs(10).msecs()
            it.bgReadings = (0L..2L).map { minutes -> reading(start - T.mins(minutes).msecs()) }
        }

        store.createBucketedData(aapsLogger, dateUtil)

        assertThat(store.bucketedData).isEmpty()
    }

    @Test
    fun `duplicate readings and a dropout do not move the retained grid`() {
        val original = AutosensDataStoreObject().also {
            it.bgReadings = (0L downTo -30L).map { minute -> reading(start + T.mins(minute).msecs()) }
            it.createBucketedData(aapsLogger, dateUtil)
        }
        val published = original.clone() as AutosensDataStoreObject
        published.bgReadings = listOf(reading(start + T.mins(11).msecs()), reading(start + T.mins(11).msecs())) + original.bgReadings

        published.createBucketedData(aapsLogger, dateUtil)

        assertThat(published.lastBg()!!.timestamp).isEqualTo(start + T.mins(10).msecs())
        val emptySlot = published.bucketedData!!.first { it.timestamp == start + T.mins(5).msecs() }
        assertThat(emptySlot.value).isEqualTo(100.0)
        assertThat(emptySlot.filledGap).isTrue()
    }
}
