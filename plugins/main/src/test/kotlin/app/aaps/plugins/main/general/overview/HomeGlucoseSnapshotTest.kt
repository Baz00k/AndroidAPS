package app.aaps.plugins.main.general.overview

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.plugins.main.general.overview.compose.GlucosePoint
import app.aaps.plugins.main.general.overview.compose.HomeChartData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class HomeGlucoseSnapshotTest {
    private val now = 1_700_000_000_000L
    private val from = now - 24 * 60 * 60_000L
    private val persistence: PersistenceLayer = mock()
    private fun reading(time: Long, value: Double, id: Long, arrow: TrendArrow = TrendArrow.NONE) = GV(
        id = id, timestamp = time, value = value, raw = value, noise = null,
        trendArrow = arrow, sourceSensor = SourceSensor.LIBRE_3
    )

    @Test fun `duplicate sensor timestamps share one headline and chart marker independent of query order`() {
        val older = reading(now - 300_000, 110.0, 1)
        val firstSource = reading(now, 120.0, 2, TrendArrow.SINGLE_UP).copy(sourceSensor = SourceSensor.LIBRE_2)
        val laterSource = reading(now, 99.0, 3, TrendArrow.SINGLE_DOWN)
        for (input in listOf(listOf(older, firstSource, laterSource), listOf(laterSource, firstSource, older))) {
            whenever(persistence.getBgReadingsDataFromTimeToTime(from, now, true)).thenReturn(input)
            val snapshot = HomeGlucoseSnapshot.load(persistence, from, now)
            val chart = HomeChartData(from = from, now = now, readings = snapshot.readings.map { GlucosePoint(it.timestamp, it.value) })
            assertEquals(99.0, snapshot.glucose.reading?.value)
            assertEquals(now, snapshot.glucose.reading?.timestamp)
            assertEquals(TrendArrow.SINGLE_DOWN, snapshot.glucose.trend)
            assertEquals(-11.0, snapshot.glucose.deltaMgdl)
            assertEquals(GlucosePoint(now, 99.0), chart.latest)
            assertEquals(2, snapshot.readings.size)
        }
    }

    @Test fun `a newer unusable reading does not hide valid stale history outside the chart`() {
        val valid = reading(from - 60 * 60_000, 99.0, 1)
        val error = reading(now - 60_000, 38.0, 2)
        val future = reading(now + 60_000, 120.0, 3)
        whenever(persistence.getBgReadingsDataFromTimeToTime(from, now, true)).thenReturn(listOf(error, future))
        whenever(persistence.getLastGlucoseValue()).thenReturn(future)
        whenever(persistence.getBgReadingsDataFromTimeToTime(0, from - 1, false)).thenReturn(listOf(valid))
        val snapshot = HomeGlucoseSnapshot.load(persistence, from, now)
        assertEquals(valid, snapshot.glucose.reading)
        assertFalse(snapshot.glucose.isFresh(now))
        assertTrue(snapshot.readings.isEmpty())
        assertNull(snapshot.glucose.deltaMgdl)
    }

    @Test fun `usable chart history needs no older-history fallback`() {
        whenever(persistence.getBgReadingsDataFromTimeToTime(from, now, true)).thenReturn(listOf(reading(now, 99.0, 1)))
        HomeGlucoseSnapshot.load(persistence, from, now)
        verify(persistence).getBgReadingsDataFromTimeToTime(from, now, true)
        verifyNoMoreInteractions(persistence)
    }

    @Test fun `empty history remains unavailable`() {
        whenever(persistence.getBgReadingsDataFromTimeToTime(from, now, true)).thenReturn(emptyList())
        whenever(persistence.getBgReadingsDataFromTimeToTime(0, from - 1, false)).thenReturn(emptyList())
        assertNull(HomeGlucoseSnapshot.load(persistence, from, now).glucose.reading)
    }
}
