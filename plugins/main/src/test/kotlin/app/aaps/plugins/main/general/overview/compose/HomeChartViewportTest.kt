package app.aaps.plugins.main.general.overview.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class HomeChartViewportTest {

    private val now = 1_700_000_000_000L
    private val from = now - CHART_HISTORY_MS
    private val min = 60_000L

    // ---- window ----

    @Test fun `stored ranges normalize like the range selector`() {
        assertEquals(6, normalizeRangeHours(0))
        assertEquals(6, normalizeRangeHours(3))
        assertEquals(6, normalizeRangeHours(6))
        assertEquals(12, normalizeRangeHours(7))
        assertEquals(12, normalizeRangeHours(12))
        assertEquals(24, normalizeRangeHours(18))
        assertEquals(24, normalizeRangeHours(48))
    }

    @Test fun `window width depends on the range and forecast choice only`() {
        assertEquals(9 * HOUR_MS, ChartWindow.of(6, forecastsEnabled = true).widthMs)
        assertEquals(6 * HOUR_MS, ChartWindow.of(6, forecastsEnabled = false).widthMs)
        assertEquals(27 * HOUR_MS, ChartWindow.of(24, forecastsEnabled = true).widthMs)
        // Forecast availability is not an input: a snapshot with or without predictions gets the same viewport.
        val window = ChartWindow.of(12, forecastsEnabled = true)
        val withForecast = HomeChartData(from = from, now = now, predictions = listOf(ChartPrediction(PredictionKind.IOB, listOf(GlucosePoint(now + 5 * min, 100.0)))))
        val without = withForecast.copy(predictions = emptyList())
        assertEquals(window.viewport(withForecast.from, withForecast.now, null), window.viewport(without.from, without.now, null))
    }

    @Test fun `live viewport ends a fixed future after now, or at now without forecasts`() {
        val forecasting = ChartWindow.of(6, true).viewport(from, now, null)
        assertEquals(now - 6 * HOUR_MS, forecasting.start)
        assertEquals(now + CHART_FUTURE_MS, forecasting.end)
        assertTrue(forecasting.live)
        val plain = ChartWindow.of(6, false).viewport(from, now, null)
        assertEquals(now - 6 * HOUR_MS, plain.start)
        assertEquals(now, plain.end)
        assertTrue(now in plain)
    }

    @Test fun `pan bounds reach the oldest loaded sample and at most the forecast horizon`() {
        val forecasting = ChartWindow.of(6, true)
        assertEquals((from + 9 * HOUR_MS)..(now + CHART_MAX_FUTURE_MS), forecasting.endBounds(from, now))
        val plain = ChartWindow.of(6, false)
        // No endless empty future when forecasts are off.
        assertEquals((from + 6 * HOUR_MS)..now, plain.endBounds(from, now))
        assertEquals(now, plain.pan(now, -HOUR_MS, from, now))
        assertEquals(from + 6 * HOUR_MS, plain.pan(now, 100 * HOUR_MS, from, now))
        assertEquals(now + CHART_MAX_FUTURE_MS, forecasting.pan(now, -100 * HOUR_MS, from, now))
    }

    @Test fun `dragging content right reveals earlier data at the same scale`() {
        val window = ChartWindow.of(6, false)
        val end = window.pan(now, HOUR_MS, from, now)
        assertEquals(now - HOUR_MS, end)
        val vp = window.viewport(from, now, end)
        assertEquals(6 * HOUR_MS, vp.end - vp.start)
        assertFalse(vp.live)
    }

    @Test fun `a sample shorter than the window cannot be panned`() {
        val window = ChartWindow.of(24, false)
        val shortFrom = now - 2 * HOUR_MS
        assertEquals(now..now, window.endBounds(shortFrom, now))
        assertEquals(now, window.pan(now, HOUR_MS, shortFrom, now))
    }

    @Test fun `release near the live edge follows now again, anywhere else stays put`() {
        val window = ChartWindow.of(6, true)
        val live = window.liveEnd(now)
        assertNull(window.settle(live - 5 * min, now, 10 * min))
        assertNull(window.settle(live + 5 * min, now, 10 * min))
        assertEquals(live - 30 * min, window.settle(live - 30 * min, now, 10 * min))
    }

    // ---- pan state ----

    @Test fun `a paused window stays on its moment while now advances`() {
        val window = ChartWindow.of(6, false)
        val state = ChartPanState()
        state.moveTo(window, now - 3 * HOUR_MS)
        val later = now + 10 * min
        val vp = state.viewport(window, later - CHART_HISTORY_MS, later)
        assertEquals(now - 3 * HOUR_MS, vp.end)
        assertFalse(vp.live)
        assertTrue(state.isPaused(window))
    }

    @Test fun `a paused position is clamped into the current bounds rather than showing unloaded time`() {
        val window = ChartWindow.of(6, false)
        val state = ChartPanState()
        state.moveTo(window, from + 6 * HOUR_MS)
        val later = now + HOUR_MS
        val vp = state.viewport(window, later - CHART_HISTORY_MS, later)
        assertEquals(later - CHART_HISTORY_MS, vp.start)
    }

    @Test fun `changing range or forecast choice goes live, and return to live clears the pause`() {
        val six = ChartWindow.of(6, false)
        val state = ChartPanState()
        state.moveTo(six, now - HOUR_MS)
        assertFalse(state.isPaused(ChartWindow.of(12, false)))
        assertFalse(state.isPaused(ChartWindow.of(6, true)))
        assertTrue(state.viewport(ChartWindow.of(12, false), from, now).live)
        assertNull(PanPosition(six, 1L).endFor(ChartWindow.of(24, false)))
        assertEquals(1L, PanPosition(six, 1L).endFor(six))
        state.returnToLive()
        assertFalse(state.isPaused(six))
        assertEquals(now, state.viewport(six, from, now).end)
    }

    // ---- time axis ----

    private fun clock(zone: String, hourPattern: String = "HH") =
        ChartClock(ZoneId.of(zone), DateTimeFormatter.ofPattern(hourPattern, Locale.US), DateTimeFormatter.ofPattern("EEE", Locale.US))

    private fun at(zone: String, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test fun `ticks sit on whole local hours and midnight shows the day`() {
        val ticks = chartTicks(at("UTC", 2024, 1, 3, 21, 30), at("UTC", 2024, 1, 4, 3, 10), 1, clock("UTC"))
        assertEquals(listOf("22", "23", "Thu", "01", "02", "03"), ticks.map { it.label })
        assertEquals(listOf(false, false, true, false, false, false), ticks.map { it.dayStart })
        assertEquals(at("UTC", 2024, 1, 4, 0), ticks[2].time)
    }

    @Test fun `twelve hour phones get twelve hour labels`() {
        val ticks = chartTicks(at("UTC", 2024, 1, 3, 21, 30), at("UTC", 2024, 1, 4, 1, 10), 1, clock("UTC", "h a"))
        assertEquals(listOf("10 PM", "11 PM", "Thu", "1 AM"), ticks.map { it.label })
    }

    @Test fun `coarse steps stay on multiples of the step`() {
        val ticks = chartTicks(at("UTC", 2024, 1, 3, 20), at("UTC", 2024, 1, 4, 7), 3, clock("UTC"))
        assertEquals(listOf("21", "Thu", "03", "06"), ticks.map { it.label })
    }

    @Test fun `spring forward skips the missing hour without a gap in spacing`() {
        val zone = "Europe/Prague"
        val start = at(zone, 2024, 3, 31, 0)
        val end = at(zone, 2024, 3, 31, 6)
        val hourly = chartTicks(start, end, 1, clock(zone))
        assertEquals(listOf("Sun", "01", "03", "04", "05", "06"), hourly.map { it.label })
        hourly.zipWithNext().forEach { (a, b) -> assertEquals(HOUR_MS, b.time - a.time) }
        assertEquals(listOf("Sun", "04", "06"), chartTicks(start, end, 2, clock(zone)).map { it.label })
    }

    @Test fun `fall back keeps both repeated hours hourly but never crowds a coarse step`() {
        val zone = "Europe/Prague"
        val start = at(zone, 2024, 10, 27, 0)
        val end = start + 5 * HOUR_MS // 00, 01, 02 CEST, 02 CET, 03, 04
        val hourly = chartTicks(start, end, 1, clock(zone))
        assertEquals(listOf("Sun", "01", "02", "02", "03", "04"), hourly.map { it.label })
        assertEquals(listOf("Sun", "02", "04"), chartTicks(start, end, 2, clock(zone)).map { it.label })
    }

    @Test fun `tick step is the finest one whose labels fit`() {
        assertEquals(1, tickStepHours(6 * HOUR_MS, 600f, 60f))
        assertEquals(3, tickStepHours(24 * HOUR_MS, 600f, 60f))
        assertEquals(12, tickStepHours(27 * HOUR_MS, 100f, 60f))
        assertEquals(12, tickStepHours(0L, 600f, 60f))
    }

    // ---- slicing ----

    @Test fun `visible slice keeps one neighbour on each side`() {
        val times = listOf(0L, 10L, 20L, 30L, 40L)
        assertEquals(listOf(10L, 20L, 30L), times.visibleSlice(15, 25) { it })
        assertEquals(listOf(10L, 20L, 30L), times.visibleSlice(20, 20) { it })
        assertEquals(times, times.visibleSlice(0, 40) { it })
        assertEquals(listOf(40L), times.visibleSlice(100, 200) { it })
        assertEquals(listOf(0L), times.visibleSlice(-100, -50) { it })
        assertTrue(emptyList<Long>().visibleSlice(0, 10) { it }.isEmpty())
    }

    @Test fun `segments split on gaps and keep isolated points`() {
        assertEquals(listOf(listOf(0L, 5L, 10L), listOf(40L, 45L), listOf(90L)), listOf(0L, 5L, 10L, 40L, 45L, 90L).segments(20) { it })
        assertEquals(listOf(listOf(0L, 20L)), listOf(0L, 20L).segments(20) { it })
        assertTrue(emptyList<Long>().segments(20) { it }.isEmpty())
    }
}
