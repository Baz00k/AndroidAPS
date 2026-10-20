package app.aaps.core.objects.ui

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.db.PersistenceLayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SensorGlucoseSnapshotTest {
    private val now = 1_700_000_000_000L
    private val from = now - 24 * 60 * 60_000L
    private val persistence: PersistenceLayer = mock()
    private fun reading(time: Long, value: Double, id: Long, arrow: TrendArrow = TrendArrow.NONE) = GV(
        id = id, timestamp = time, value = value, raw = value, noise = null,
        trendArrow = arrow, sourceSensor = SourceSensor.LIBRE_3
    )

    @Test fun `duplicate sensor timestamps share one headline and chart reading independent of query order`() {
        val older = reading(now - 300_000, 110.0, 1)
        val firstSource = reading(now, 120.0, 2, TrendArrow.SINGLE_UP).copy(sourceSensor = SourceSensor.LIBRE_2)
        val laterSource = reading(now, 99.0, 3, TrendArrow.SINGLE_DOWN)
        for (input in listOf(listOf(older, firstSource, laterSource), listOf(laterSource, firstSource, older))) {
            whenever(persistence.getBgReadingsDataFromTimeToTime(from, now, true)).thenReturn(input)
            val snapshot = SensorGlucoseSnapshot.load(persistence, from, now)
            assertEquals(laterSource, snapshot.glucose.reading)
            assertEquals(-11.0, snapshot.glucose.deltaMgdl)
            assertEquals(listOf(older, laterSource), snapshot.readings)
        }
    }

    @Test fun `empty chart window keeps the last reading as stale headline only`() {
        val valid = reading(from - 60 * 60_000, 99.0, 1)
        whenever(persistence.getBgReadingsDataFromTimeToTime(from, now, true)).thenReturn(emptyList())
        whenever(persistence.getLastGlucoseValue()).thenReturn(valid)
        val snapshot = SensorGlucoseSnapshot.load(persistence, from, now)
        assertEquals(valid, snapshot.glucose.reading)
        assertFalse(snapshot.glucose.isFresh(now))
        assertTrue(snapshot.readings.isEmpty())
    }

    @Test fun `fallback does not trust a missing invalid or future last record`() {
        whenever(persistence.getBgReadingsDataFromTimeToTime(from, now, true)).thenReturn(emptyList())
        for (last in listOf(null, reading(now + 1, 120.0, 1), reading(now, 120.0, 1).copy(isValid = false))) {
            whenever(persistence.getLastGlucoseValue()).thenReturn(last)
            assertNull(SensorGlucoseSnapshot.load(persistence, from, now).glucose.reading)
        }
    }

    @Test fun `latest is the newest one-minute reading, fresh enough to act on only while under nine minutes old`() {
        val window = now - SensorGlucoseSnapshot.LATEST_WINDOW_MS
        val oneMinute = (0..5).map { reading(now - (5 - it) * 60_000L, 170.0 + 6 * it, it.toLong() + 1) }
        whenever(persistence.getBgReadingsDataFromTimeToTime(window, now, true)).thenReturn(oneMinute)
        val latest = SensorGlucoseSnapshot.latest(persistence, now)
        assertEquals(oneMinute.last(), latest.freshReading(now))
        assertEquals(30.0, latest.deltaMgdl)
        assertNull(latest.freshReading(now + 9 * 60_000L))
    }
}
