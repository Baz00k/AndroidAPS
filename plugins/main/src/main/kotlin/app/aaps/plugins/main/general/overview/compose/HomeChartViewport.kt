package app.aaps.plugins.main.general.overview.compose

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

internal const val HOUR_MS = 3_600_000L

/** History loaded per background refresh; dragging never queries providers. */
internal const val CHART_HISTORY_MS = 24 * HOUR_MS

/** Fixed future area shown when any forecast is enabled — never fitted to the forecast's length. */
internal const val CHART_FUTURE_MS = 3 * HOUR_MS

/** How far a user may drag into the future: the full length of an OpenAPS forecast. */
internal const val CHART_MAX_FUTURE_MS = 4 * HOUR_MS

internal val CHART_RANGES_HOURS = listOf(6, 12, 24)

/** Same rule as the range selector: the first offered range that covers the stored value. */
internal fun normalizeRangeHours(hours: Int): Int = CHART_RANGES_HOURS.firstOrNull { it >= hours } ?: CHART_RANGES_HOURS.last()

/** Fixed history/future widths determined by settings, not prediction availability. */
@Immutable
data class ChartWindow(val historyMs: Long, val futureMs: Long, val maxFutureMs: Long) {

    init {
        require(historyMs > 0 && futureMs >= 0 && maxFutureMs >= futureMs)
    }

    val widthMs: Long get() = historyMs + futureMs

    /** Right edge while following the present. */
    fun liveEnd(now: Long): Long = now + futureMs

    /** Clamp inspection to loaded history and the enabled forecast horizon. */
    fun endBounds(dataFrom: Long, now: Long): LongRange =
        minOf(dataFrom + widthMs, liveEnd(now))..(now + maxFutureMs)

    fun viewport(dataFrom: Long, now: Long, pausedEnd: Long?): ChartViewport {
        val end = pausedEnd?.coerceIn(endBounds(dataFrom, now)) ?: liveEnd(now)
        return ChartViewport(end - widthMs, end, live = pausedEnd == null)
    }

    /** Dragging content right ([deltaMs] > 0) reveals earlier data, so the right edge moves back. */
    fun pan(end: Long, deltaMs: Long, dataFrom: Long, now: Long): Long =
        (end - deltaMs).coerceIn(endBounds(dataFrom, now))

    /** A window released within [snapMs] of the live edge follows "now" again; anything else stays put. */
    fun settle(end: Long, now: Long, snapMs: Long): Long? = if (abs(end - liveEnd(now)) <= snapMs) null else end

    companion object {

        fun of(rangeHours: Int, forecastsEnabled: Boolean) = ChartWindow(
            historyMs = normalizeRangeHours(rangeHours) * HOUR_MS,
            futureMs = if (forecastsEnabled) CHART_FUTURE_MS else 0L,
            maxFutureMs = if (forecastsEnabled) CHART_MAX_FUTURE_MS else 0L
        )
    }
}

/** The resolved visible time range. [live] = following "now" rather than a paused inspection window. */
@Immutable
data class ChartViewport(val start: Long, val end: Long, val live: Boolean) {

    val span: Long get() = (end - start).coerceAtLeast(1L)
    operator fun contains(time: Long): Boolean = time in start..end
}

/** A paused window is only valid for the window it was paused in; a new range or forecast choice goes live. */
@Immutable
internal data class PanPosition(val window: ChartWindow, val end: Long)

internal fun PanPosition?.endFor(window: ChartWindow): Long? = this?.takeIf { it.window == window }?.end

/** UI-thread pan state shared by all panels. An absolute timestamp keeps inspection stable across refreshes. */
@Stable
class ChartPanState {

    internal var position: PanPosition? by mutableStateOf(null)
        private set

    /** The running fling / return animation, shared so touching ANY panel stops it. UI thread only. */
    internal var motion: Job? = null

    fun pausedEnd(window: ChartWindow): Long? = position.endFor(window)
    fun isPaused(window: ChartWindow): Boolean = pausedEnd(window) != null
    fun viewport(window: ChartWindow, dataFrom: Long, now: Long): ChartViewport = window.viewport(dataFrom, now, pausedEnd(window))

    internal fun moveTo(window: ChartWindow, end: Long?) {
        position = end?.let { PanPosition(window, it) }
    }

    internal fun stopMotion() {
        motion?.cancel()
        motion = null
    }

    /** Follow "now" again immediately (e.g. when the screen is resumed). */
    fun returnToLive() {
        stopMotion()
        position = null
    }
}

// ---- time axis ----

@Immutable
internal data class ChartTick(val time: Long, val label: String, val dayStart: Boolean)

/** Zone and formatters for axis labels; built from the phone's 12/24-hour setting by the caller. */
@Immutable
internal class ChartClock(val zone: ZoneId, val hour: DateTimeFormatter, val day: DateTimeFormatter)

internal val TICK_STEPS_HOURS = listOf(1, 2, 3, 4, 6, 12)

/** The finest hour step whose labels do not collide at this scale. */
internal fun tickStepHours(spanMs: Long, plotWidthPx: Float, minLabelSpacingPx: Float): Int {
    if (spanMs <= 0L || plotWidthPx <= 0f) return TICK_STEPS_HOURS.last()
    val pxPerHour = plotWidthPx * HOUR_MS.toFloat() / spanMs.toFloat()
    return TICK_STEPS_HOURS.firstOrNull { it * pxPerHour >= minLabelSpacingPx } ?: TICK_STEPS_HOURS.last()
}

/** Local-hour ticks with weekday labels at midnight. Walk instants to handle DST gaps and repeats. */
internal fun chartTicks(start: Long, end: Long, stepHours: Int, clock: ChartClock): List<ChartTick> {
    if (end <= start) return emptyList()
    val step = stepHours.coerceAtLeast(1)
    var t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(start), clock.zone).truncatedTo(ChronoUnit.HOURS)
    while (t.toInstant().toEpochMilli() < start) t = t.plusHours(1)
    val ticks = ArrayList<ChartTick>()
    var previous: Long? = null
    while (true) {
        val ms = t.toInstant().toEpochMilli()
        if (ms > end) break
        val spaced = previous == null || ms - previous >= step * HOUR_MS - HOUR_MS / 2
        if (t.hour % step == 0 && spaced) {
            val dayStart = t.hour == 0 && t.minute == 0
            ticks.add(ChartTick(ms, (if (dayStart) clock.day else clock.hour).format(t), dayStart))
            previous = ms
        }
        t = t.plusHours(1)
    }
    return ticks
}

// ---- slicing ----

/** Sorted points within [start]..[end], plus one neighbour on each side for clipping. O(log n) lookup. */
internal fun <T> List<T>.visibleSlice(start: Long, end: Long, time: (T) -> Long): List<T> {
    if (isEmpty()) return this
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (time(this[mid]) < start) lo = mid + 1 else hi = mid
    }
    val first = (lo - 1).coerceAtLeast(0)
    lo = first
    hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (time(this[mid]) <= end) lo = mid + 1 else hi = mid
    }
    val last = lo.coerceAtMost(size - 1)
    return subList(first, last + 1)
}

/** Split where consecutive points are more than [maxGapMs] apart: gaps stay gaps. */
internal fun <T> List<T>.segments(maxGapMs: Long, time: (T) -> Long): List<List<T>> {
    if (isEmpty()) return emptyList()
    val result = ArrayList<List<T>>()
    var from = 0
    for (i in 1 until size) {
        if (time(this[i]) - time(this[i - 1]) > maxGapMs) {
            result.add(subList(from, i))
            from = i
        }
    }
    result.add(subList(from, size))
    return result
}
