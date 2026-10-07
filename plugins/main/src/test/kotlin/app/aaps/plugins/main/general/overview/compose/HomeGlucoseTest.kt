package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeGlucoseTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private fun reading(minutesAgo: Long, value: Double, arrow: TrendArrow = TrendArrow.NONE) = GV(
        timestamp = now - minutesAgo * minute, value = value, raw = value, noise = null,
        trendArrow = arrow, sourceSensor = SourceSensor.LIBRE_3
    )

    @Test fun `new sensor readings update glucose timestamp delta and arrow between loop buckets`() {
        val previous = reading(5, 110.0)
        val first = HomeGlucose.from(listOf(previous, reading(0, 99.0, TrendArrow.SINGLE_DOWN)), now)
        assertEquals(99.0, first.reading?.value)
        assertEquals(now, first.reading?.timestamp)
        assertEquals(-11.0, first.deltaMgdl)
        assertEquals(TrendArrow.SINGLE_DOWN, first.trend)

        val next = reading(0, 95.0, TrendArrow.FORTY_FIVE_DOWN).copy(timestamp = now + minute)
        val updated = HomeGlucose.from(listOf(previous, first.reading!!, next), now + minute)
        assertEquals(95.0, updated.reading?.value)
        assertEquals(now + minute, updated.reading?.timestamp)
        assertEquals(-12.5, updated.deltaMgdl)
        assertEquals(TrendArrow.FORTY_FIVE_DOWN, updated.trend)
    }

    @Test fun `reporting intervals do not change the five minute delta units`() {
        for (interval in listOf(1L, 3L, 5L)) {
            val readings = (0L..10L step interval).map { reading(it, 100.0 + 2 * it) }
            val glucose = HomeGlucose.from(readings, now)
            assertEquals(100.0, glucose.reading?.value)
            assertEquals(-10.0, glucose.deltaMgdl)
            assertEquals(TrendArrow.SINGLE_DOWN, glucose.trend)
        }
    }

    @Test fun `invalid future and nonfinite readings cannot become the headline`() {
        val valid = reading(1, 99.0)
        val readings = listOf(
            reading(0, 150.0).copy(isValid = false), reading(-1, 180.0),
            reading(0, Double.NaN), reading(0, Double.POSITIVE_INFINITY), reading(0, 0.0), reading(0, -1.0), reading(0, 38.0), valid
        )
        assertEquals(valid, HomeGlucose.from(readings, now).reading)
        assertNull(HomeGlucose.from(readings.dropLast(1), now).reading)
    }

    @Test fun `missing history or a gap does not fabricate a flat trend or delta`() {
        for (samples in listOf(emptyList(), listOf(reading(0, 99.0)), listOf(reading(0, 99.0), reading(15, 130.0)))) {
            val glucose = HomeGlucose.from(samples, now)
            assertNull(glucose.deltaMgdl)
            assertNull(glucose.trend)
        }
        assertEquals(TrendArrow.SINGLE_DOWN, HomeGlucose.from(listOf(reading(0, 99.0, TrendArrow.SINGLE_DOWN)), now).trend)
    }

    @Test fun `duplicate timestamps and very recent readings are not a delta baseline`() {
        val glucose = HomeGlucose.from(listOf(reading(0, 99.0), reading(0, 120.0), reading(1, 115.0), reading(5, 110.0)), now)
        assertEquals(-11.0, glucose.deltaMgdl)
    }

    @Test fun `later inserted sensor record wins timestamp ties independent of input order`() {
        val firstSource = reading(0, 120.0, TrendArrow.SINGLE_UP).copy(id = 2, sourceSensor = SourceSensor.LIBRE_2)
        val laterSource = reading(0, 99.0, TrendArrow.SINGLE_DOWN).copy(id = 3)
        for (samples in listOf(listOf(firstSource, laterSource), listOf(laterSource, firstSource))) {
            val glucose = HomeGlucose.from(samples, now)
            assertEquals(laterSource, glucose.reading)
            assertEquals(TrendArrow.SINGLE_DOWN, glucose.trend)
        }
    }

    @Test fun `irregular timestamps normalize the delta by actual elapsed time`() {
        val previous = reading(5, 110.0).copy(timestamp = now - 330_000L)
        assertEquals(-10.0, HomeGlucose.from(listOf(reading(0, 99.0), previous), now).deltaMgdl)
    }

    @Test fun `sensor freshness uses its real timestamp and preserves the nine minute boundary`() {
        val glucose = HomeGlucose.from(listOf(reading(0, 99.0)), now)
        assertTrue(glucose.isFresh(now + 9 * minute - 1))
        assertFalse(glucose.isFresh(now + 9 * minute))
        assertFalse(glucose.isFresh(now - 1))
        assertFalse(HomeGlucose().isFresh(now))
        val old = HomeGlucose.from(listOf(reading(25 * 60, 99.0)), now)
        assertEquals(99.0, old.reading?.value)
        assertFalse(old.isFresh(now))
    }
}
