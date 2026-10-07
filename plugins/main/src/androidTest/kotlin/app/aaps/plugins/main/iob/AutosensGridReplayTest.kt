package app.aaps.plugins.main.iob

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.Mockito.mock

/** Isolated replay on Android: no app configuration, database, sensor or pump commands. */
class AutosensGridReplayTest {

    @Test
    fun successiveOneMinuteCalculationsKeepTheFiveMinuteGrid() {
        val logger = mock(AAPSLogger::class.java)
        val dateUtil = mock(DateUtil::class.java)
        val start = System.currentTimeMillis() - T.mins(10).msecs()
        var store = AutosensDataStoreObject()
        val newestBuckets = mutableListOf<Long>()
        for (minute in 0L..10L) {
            store.bgReadings = (minute downTo minute - 30L).map {
                GV(
                    timestamp = start + T.mins(it).msecs(), value = 100.0, raw = 100.0,
                    noise = 0.0, sourceSensor = SourceSensor.LIBRE_2, trendArrow = TrendArrow.FLAT
                )
            }
            store.createBucketedData(logger, dateUtil)
            store = store.clone() as AutosensDataStoreObject
            newestBuckets.add(store.lastBg()!!.timestamp)
        }

        assertThat(newestBuckets).containsExactlyElementsIn(
            listOf(0L, 0, 0, 0, 0, 5, 5, 5, 5, 5, 10).map { start + T.mins(it).msecs() }
        ).inOrder()
    }
}
