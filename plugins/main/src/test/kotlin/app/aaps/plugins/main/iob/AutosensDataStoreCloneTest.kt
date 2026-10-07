package app.aaps.plugins.main.iob

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.implementation.iob.AutosensDataObject
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.provider.ValueSource

class AutosensDataStoreCloneTest : TestBaseWithProfile() {

    private fun readings(fiveMinute: Boolean): List<GV> =
        (if (fiveMinute) listOf(0L, 5, 10, 15, 20, 25) else listOf(0L, 6, 11, 17, 22, 28)).map {
            GV(
                timestamp = T.hours(2).msecs() - T.mins(it).msecs(),
                value = 100.0,
                raw = 100.0,
                noise = 0.0,
                sourceSensor = SourceSensor.LIBRE_3,
                trendArrow = TrendArrow.FLAT
            )
        }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `published clone retains mode and cache when mode is unchanged`(fiveMinute: Boolean) {
        val original = AutosensDataStoreObject()
        original.bgReadings = readings(fiveMinute)
        original.createBucketedData(aapsLogger, dateUtil)
        val cachedTime = T.hours(1).msecs()
        val cached = AutosensDataObject(aapsLogger, preferences, dateUtil).also { it.time = cachedTime }
        original.autosensDataTable.put(cachedTime, cached)

        val published = original.clone()
        assertThat(published.lastUsed5minCalculation).isEqualTo(fiveMinute)
        published.createBucketedData(aapsLogger, dateUtil)

        assertThat(published.autosensDataTable[cachedTime]).isSameInstanceAs(cached)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `published clone invalidates cache on mode transition`(fiveMinute: Boolean) {
        val original = AutosensDataStoreObject()
        original.bgReadings = readings(fiveMinute)
        original.createBucketedData(aapsLogger, dateUtil)
        val cachedTime = T.hours(1).msecs()
        val cached = AutosensDataObject(aapsLogger, preferences, dateUtil).also { it.time = cachedTime }
        original.autosensDataTable.put(cachedTime, cached)

        val published = original.clone()
        published.bgReadings = readings(!fiveMinute)
        published.createBucketedData(aapsLogger, dateUtil)

        assertThat(published.lastUsed5minCalculation).isEqualTo(!fiveMinute)
        assertThat(published.autosensDataTable.size()).isEqualTo(0)
        assertThat(published.bucketedData).isNotEmpty()
        // Invalidating the published cache must not clear the original store's table.
        assertThat(original.autosensDataTable[cachedTime]).isSameInstanceAs(cached)
    }

    @Test
    fun `new one minute measurement survives clone publication and rebuild`() {
        val end = T.hours(2).msecs()
        val history = (0L..15L).map { minute ->
            GV(timestamp = end - T.mins(minute).msecs(), value = 100.0 + 10 * minute,
               raw = null, noise = null, sourceSensor = SourceSensor.LIBRE_3, trendArrow = TrendArrow.FLAT)
        }
        val original = AutosensDataStoreObject().also {
            it.bgReadings = history
            it.createBucketedData(aapsLogger, dateUtil)
        }
        val published = original.clone()
        val newest = history.first().copy(timestamp = end + T.mins(1).msecs(), value = 90.0)
        published.bgReadings = listOf(newest) + history
        published.createBucketedData(aapsLogger, dateUtil)

        // Unlike the same-store test, the current publication flow establishes a fresh
        // grid on the latest measurement. Pin values/timestamps, not reference internals.
        // This does not claim cached autosens entries on the old grid were invalidated.
        assertThat(published.bucketedData!!.map { it.timestamp to it.value }).containsExactly(
            newest.timestamp to 90.0,
            (end - T.mins(4).msecs()) to 140.0,
            (end - T.mins(9).msecs()) to 190.0,
            (end - T.mins(14).msecs()) to 240.0
        ).inOrder()
        assertThat(original.bucketedData!!.first().timestamp).isEqualTo(end)
        assertThat(original.bucketedData!!.first().value).isEqualTo(100.0)
    }
}
