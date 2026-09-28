package app.aaps.plugins.main.general.overview.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeChartDataTest {

    private val min = 60_000L
    private val now = 1_700_000_000_000L
    private fun p(minutesAgo: Long, value: Double = 100.0) = GlucosePoint(now - minutesAgo * min, value)

    @Test fun `a reading newer than the last bucketing run still ends the trace`() {
        val readings = (20L downTo 0L).map { p(it, 100.0 + it) }  // 1-minute source
        val bucketed = listOf(p(20, 120.0), p(15, 115.0), p(10, 110.0))
        val data = HomeChartData(from = now - HOUR_MS, now = now, readings = readings, bucketed = bucketed)
        assertEquals(bucketed + readings.filter { it.time > p(10).time }, data.trace)
        assertEquals(now, data.latest?.time)
        assertEquals(readings.last(), data.latest)
    }

    @Test fun `readings older than the bucketed table fill the early edge`() {
        val readings = (40L downTo 0L step 5).map { p(it) }
        val bucketed = listOf(p(20), p(15), p(10), p(5), p(0))
        val data = HomeChartData(from = now - HOUR_MS, now = now, readings = readings, bucketed = bucketed)
        assertEquals(listOf(p(40), p(35), p(30), p(25)) + bucketed, data.trace)
    }

    @Test fun `the latest marker stays at a stale reading's own time`() {
        val readings = listOf(p(50), p(45))
        val data = HomeChartData(from = now - HOUR_MS, now = now, readings = readings)
        assertEquals(p(45).time, data.latest?.time)
        assertTrue(data.latest!!.time < data.now)
    }

    @Test fun `without a usable bucketed series the readings are the trace`() {
        val readings = listOf(p(10), p(5), p(0))
        assertEquals(readings, mergeTrace(readings, emptyList()))
        assertEquals(readings, mergeTrace(readings, listOf(p(5, 90.0))))
    }

    @Test fun `scatter is only drawn for a denser raw source`() {
        val oneMinute = (10L downTo 0L).map { p(it) }
        val bucketed = listOf(p(10), p(5), p(0))
        assertTrue(HomeChartData(from = now - HOUR_MS, now = now, readings = oneMinute, bucketed = bucketed).hasDenseScatter)
        assertFalse(HomeChartData(from = now - HOUR_MS, now = now, readings = bucketed, bucketed = bucketed).hasDenseScatter)
    }

    @Test fun `measured data is required, forecasts alone are not enough`() {
        assertFalse(HomeChartData(from = now - HOUR_MS, now = now).hasData)
        assertFalse(HomeChartData(from = now, now = now, readings = listOf(p(0))).hasData)
        assertTrue(HomeChartData(from = now - HOUR_MS, now = now, readings = listOf(p(0))).hasData)
    }

    @Test fun `insulin scale tops out at a round number above delivered and scheduled rates`() {
        assertEquals(0.25, insulinScaleMax(emptyList()))
        assertEquals(1.0, insulinScaleMax(listOf(BasalStep(0, 0.8, 0.6))))
        assertEquals(2.5, insulinScaleMax(listOf(BasalStep(0, 2.0, 1.2))))
        assertEquals(6.0, insulinScaleMax(listOf(BasalStep(0, 0.0, 5.0))))
        assertEquals(0.25, insulinScaleMax(listOf(BasalStep(0, Double.NaN, Double.NaN))))
    }

    @Test fun `additional axis decimals follow the span`() {
        assertEquals(0, axisDecimals(45.0))
        assertEquals(1, axisDecimals(4.0))
        assertEquals(2, axisDecimals(0.4))
    }
}
