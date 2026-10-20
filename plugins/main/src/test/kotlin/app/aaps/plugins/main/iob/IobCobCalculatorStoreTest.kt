package app.aaps.plugins.main.iob

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.rx.events.EventNewHistoryData
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.plugins.main.iob.iobCobCalculator.IobCobCalculatorPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

class IobCobCalculatorStoreTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var overviewData: OverviewData
    @Mock lateinit var calculationWorkflow: CalculationWorkflow

    private lateinit var plugin: IobCobCalculatorPlugin

    @BeforeEach
    fun setup() {
        plugin = IobCobCalculatorPlugin(
            aapsLogger, aapsSchedulers, rxBus, preferences, rh, profileFunction, activePlugin, fabricPrivacy, dateUtil,
            persistenceLayer, overviewData, calculationWorkflow, decimalFormatter, processedTbrEbData
        )
    }

    private fun readings(newestMinute: Long) = (newestMinute downTo newestMinute - 30L).map {
        GV(
            timestamp = now + T.mins(it).msecs(), value = 100.0 + it, raw = null,
            noise = null, sourceSensor = SourceSensor.LIBRE_3, trendArrow = TrendArrow.NONE
        )
    }

    private fun load(newestMinute: Long) = plugin.updateAds {
        it.bgReadings = readings(newestMinute)
        it.createBucketedData(aapsLogger, dateUtil)
    }

    @Test
    fun `a calculation cloned before newer glucose was loaded cannot replace it`() {
        load(-1)
        val outdated = plugin.ads.clone()
        load(0)

        assertThat(plugin.publishAds(outdated)).isFalse()
        assertThat(plugin.ads.getLatestBucketedDataCopy()!!.first().timestamp).isEqualTo(now)

        val current = plugin.ads.clone()
        assertThat(plugin.publishAds(current)).isTrue()
        assertThat(plugin.ads).isSameInstanceAs(current)
    }

    @Test
    fun `a calculation cloned before history was invalidated cannot replace it`() {
        load(0)
        val outdated = plugin.ads.clone()
        plugin.updateAds { it.newHistoryData(now - T.mins(30).msecs(), aapsLogger, dateUtil) }

        assertThat(plugin.publishAds(outdated)).isFalse()
    }

    @Test
    fun `glucose is pending while a stored reading is newer than the loaded ones`() {
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), eq(false))).thenReturn(readings(0))

        // Nothing loaded yet, then the previous reading, then the newest.
        assertThat(plugin.isGlucoseCalculationPending()).isTrue()
        load(-1)
        assertThat(plugin.isGlucoseCalculationPending()).isTrue()
        load(0)
        assertThat(plugin.isGlucoseCalculationPending()).isFalse()

        // Without recent stored glucose there is nothing to wait for; the APS rejects stale data.
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), eq(false))).thenReturn(emptyList())
        assertThat(plugin.isGlucoseCalculationPending()).isFalse()
    }

    @Test
    fun `a pending reading requests its own calculation once`() {
        val requests = mutableListOf<EventNewHistoryData>()
        rxBus.toObservable(EventNewHistoryData::class.java).subscribe(requests::add)
        load(-1)
        // Stored beyond the last load's window, so no calculation of its own is under way.
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), eq(false))).thenReturn(readings(0))

        repeat(3) { assertThat(plugin.isGlucoseCalculationPending()).isTrue() }

        assertThat(requests).hasSize(1)
        assertThat(requests.single().reloadBgData).isTrue()
        assertThat(requests.single().newestGlucoseValueTimestamp).isEqualTo(now)
    }

    @Test
    fun `newer glucose is detected only after the reading a calculation used`() {
        val used = now - T.mins(1).msecs()
        val from = argumentCaptor<Long>()
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(from.capture(), any(), eq(false))).thenReturn(readings(0))
        assertThat(plugin.isNewerGlucoseStored(used)).isTrue()
        assertThat(from.lastValue).isEqualTo(used + 1)

        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), eq(false))).thenReturn(emptyList())
        assertThat(plugin.isNewerGlucoseStored(used)).isFalse()
    }
}
