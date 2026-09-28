package app.aaps.plugins.main.general.overview.compose

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.icons.AapsIcons
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.plugins.main.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** Every panel uses the same side gutters, so their time axes line up. */
internal val CHART_PAD_START = 40.dp
internal val CHART_PAD_END = 40.dp

/** Axis labels follow the phone's 12/24-hour setting and the current time zone. */
@Composable
internal fun rememberChartClock(now: Long): ChartClock {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    // Keyed on the snapshot time so a zone change (travel, DST) is picked up on the next refresh.
    return remember(locale, is24Hour, now) {
        val hourPattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (is24Hour) "HH" else "ha")
        ChartClock(ZoneId.systemDefault(), DateTimeFormatter.ofPattern(hourPattern, locale), DateTimeFormatter.ofPattern("EEE", locale))
    }
}

// ---- horizontal panning ----

/**
 * Turns horizontal drags on one panel into moves of the shared [ChartPanState]. Each panel owns one,
 * because each converts pixels to time with its own plot width; they all move the same timestamp,
 * so every panel stays aligned. Plain fields are refreshed after each composition and read only by
 * gesture callbacks on the UI thread.
 */
@Stable
internal class ChartPanController(
    private val state: ChartPanState,
    private val decay: DecayAnimationSpec<Float>,
    private val scope: CoroutineScope
) {

    var window: ChartWindow = ChartWindow.of(CHART_RANGES_HOURS.first(), false)
    var dataFrom: Long = 0L
    var now: Long = 0L
    var enabled: Boolean = false
    var plotWidthPx: Float = 0f
    var snapPx: Float = 0f

    val draggable = DraggableState { delta -> panBy(delta) }

    private fun msPerPx(): Float = window.widthMs / plotWidthPx.coerceAtLeast(1f)

    /** @return false when nothing moved (disabled, or already at a bound). */
    fun panBy(deltaPx: Float): Boolean {
        if (!enabled || plotWidthPx <= 0f) return false
        val current = state.pausedEnd(window) ?: window.liveEnd(now)
        val next = window.pan(current, (deltaPx * msPerPx()).roundToLong(), dataFrom, now)
        if (next == current) return false
        state.moveTo(window, next)
        return true
    }

    fun stop() = state.stopMotion()

    fun fling(velocityPx: Float) {
        state.stopMotion()
        state.motion = scope.launch {
            var previous = 0f
            AnimationState(initialValue = 0f, initialVelocity = velocityPx).animateDecay(decay) {
                val delta = value - previous
                previous = value
                if (!panBy(delta)) cancelAnimation()
            }
            settle()
        }
    }

    /** Snap only on release, so a slow drag past "now" is not sticky. */
    fun settle() {
        val end = state.pausedEnd(window) ?: return
        state.moveTo(window, window.settle(end, now, (snapPx * msPerPx()).roundToLong()))
    }

    fun returnToNow() {
        val window = window
        val start = state.pausedEnd(window) ?: return
        val target = window.liveEnd(now)
        state.stopMotion()
        state.motion = scope.launch {
            animate(0f, 1f, animationSpec = tween(320, easing = FastOutSlowInEasing)) { fraction, _ ->
                state.moveTo(window, start + ((target - start) * fraction).roundToLong())
            }
            state.moveTo(window, null)
        }
    }
}

@Composable
internal fun rememberChartPan(state: ChartPanState, window: ChartWindow, data: HomeChartData): ChartPanController {
    val decay = rememberSplineBasedDecay<Float>()
    val scope = rememberCoroutineScope()
    val controller = remember(state, decay, scope) { ChartPanController(state, decay, scope) }
    val snapPx = with(LocalDensity.current) { 16.dp.toPx() }
    SideEffect {
        controller.window = window
        controller.dataFrom = data.from
        controller.now = data.now
        controller.enabled = data.hasData
        controller.snapPx = snapPx
    }
    return controller
}

/**
 * Horizontal drag + fling at the same scale. Only claims the gesture after horizontal touch slop, so a
 * vertical swipe that starts on the graph still scrolls the page. Any touch stops a running fling.
 */
@Composable
internal fun Modifier.chartPan(controller: ChartPanController, enabled: Boolean): Modifier {
    val view = LocalView.current
    val gutterPx = with(LocalDensity.current) { (CHART_PAD_START + CHART_PAD_END).toPx() }
    return this
        .onSizeChanged { controller.plotWidthPx = it.width - gutterPx }
        .pointerInput(controller, view) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                controller.stop()
                // Overview is hosted inside ViewPager2. Reserve this touch stream before its
                // RecyclerView can intercept horizontal slop; Compose's own vertical scroll
                // still participates normally. The fixture activity has no such View parent.
                view.parent?.requestDisallowInterceptTouchEvent(true)
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                    } while (event.changes.any { it.pressed })
                } finally {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        .draggable(
            state = controller.draggable,
            orientation = Orientation.Horizontal,
            enabled = enabled,
            onDragStarted = { controller.stop() },
            onDragStopped = { velocity -> controller.fling(velocity) }
        )
}

@Composable
internal fun ReturnToNowPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AapsTheme.colors
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .clip(AapsTheme.shape.pill)
            .background(colors.controlFill)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.overview_graph_return_to_now), onClick = onClick)
            .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(stringResource(R.string.overview_graph_now), style = AapsTheme.type.caption, color = colors.textPrimary)
        Icon(AapsIcons.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
    }
}

// ---- legend ----

internal enum class LegendMark { LINE, DASH, DOT, AREA, BAR }

@Composable
internal fun LegendItem(label: String, color: Color, mark: LegendMark, muted: Boolean = false) {
    val colors = AapsTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.size(width = 14.dp, height = 10.dp)) {
            val tint = if (muted) color.copy(alpha = 0.4f) else color
            val midY = size.height / 2
            when (mark) {
                LegendMark.LINE -> drawLine(tint, Offset(0f, midY), Offset(size.width, midY), 2.dp.toPx(), StrokeCap.Round)
                LegendMark.DASH -> drawLine(
                    tint, Offset(0f, midY), Offset(size.width, midY), 1.8.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.dp.toPx()))
                )

                LegendMark.DOT  -> drawCircle(tint, 3.5.dp.toPx(), Offset(size.width / 2, midY))
                LegendMark.AREA -> drawRect(tint.copy(alpha = tint.alpha * 0.45f), Offset(0f, midY - 3.dp.toPx()), Size(size.width, 6.dp.toPx()))
                LegendMark.BAR  -> {
                    val w = 2.5.dp.toPx()
                    listOf(0.25f to 0.8f, 0.5f to 0.45f, 0.75f to 0.65f).forEach { (fx, fh) ->
                        drawRect(tint, Offset(size.width * fx - w / 2, size.height * (1 - fh)), Size(w, size.height * fh))
                    }
                }
            }
        }
        Text(label, style = AapsTheme.type.caption, color = if (muted) colors.textTertiary else colors.textSecondary)
    }
}

// ---- shared drawing ----

/** A label whose baseline sits at [y]; skipped rather than drawn off-canvas. */
internal fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    style: TextStyle,
    alignEnd: Boolean = false,
    center: Boolean = false
) {
    val laid = measurer.measure(text, style)
    val dx = when {
        alignEnd -> x - laid.size.width
        center   -> x - laid.size.width / 2f
        else     -> x
    }
    if (dx < -1f || dx + laid.size.width > size.width + 1f) return
    drawText(laid, topLeft = Offset(dx, y - laid.size.height))
}

/** An axis value vertically centred on [centerY], kept inside the canvas. */
internal fun DrawScope.drawAxisValue(measurer: TextMeasurer, text: String, x: Float, centerY: Float, style: TextStyle, alignEnd: Boolean) {
    val laid = measurer.measure(text, style)
    val left = if (alignEnd) x - laid.size.width else x
    val top = (centerY - laid.size.height / 2f).coerceIn(0f, (size.height - laid.size.height).coerceAtLeast(0f))
    drawText(laid, topLeft = Offset(left, top))
}

/** Hour ticks for this viewport at a step whose labels fit. */
internal fun DrawScope.timeTicks(viewport: ChartViewport, plotWidth: Float, clock: ChartClock, measurer: TextMeasurer, style: TextStyle): List<ChartTick> {
    // A Wednesday 22:00: wide enough for both an hour ("22", "10 PM") and a day ("Wed") label.
    val sample = ZonedDateTime.of(2024, 1, 3, 22, 0, 0, 0, clock.zone)
    val widest = maxOf(measurer.measure(clock.hour.format(sample), style).size.width, measurer.measure(clock.day.format(sample), style).size.width)
    return chartTicks(viewport.start, viewport.end, tickStepHours(viewport.span, plotWidth, widest + 12.dp.toPx()), clock)
}

/** Faint vertical gridlines; day boundaries slightly stronger. */
internal fun DrawScope.drawTickGrid(ticks: List<ChartTick>, x: (Long) -> Float, top: Float, bottom: Float, color: Color) {
    ticks.forEach { tick ->
        drawLine(color.copy(alpha = color.alpha * if (tick.dayStart) 1f else 0.5f), Offset(x(tick.time), top), Offset(x(tick.time), bottom), 1f)
    }
}

internal fun DrawScope.drawTickLabels(
    ticks: List<ChartTick>, x: (Long) -> Float, baseline: Float, measurer: TextMeasurer, style: TextStyle, dayStyle: TextStyle
) {
    var lastRight = Float.NEGATIVE_INFINITY
    ticks.forEach { tick ->
        val laid = measurer.measure(tick.label, if (tick.dayStart) dayStyle else style)
        val left = x(tick.time) - laid.size.width / 2f
        if (left < 0f || left + laid.size.width > size.width || left < lastRight + 4.dp.toPx()) return@forEach
        drawText(laid, topLeft = Offset(left, baseline - laid.size.height))
        lastRight = left + laid.size.width
    }
}

/** The future is a quiet tint to the right of "now", identical in every panel. */
internal fun DrawScope.drawFutureShade(viewport: ChartViewport, now: Long, x: (Long) -> Float, plotRight: Float, top: Float, bottom: Float, color: Color) {
    if (viewport.end <= now) return
    val left = x(maxOf(now, viewport.start))
    if (plotRight > left) drawRect(color, Offset(left, top), Size(plotRight - left, bottom - top))
}

internal fun DrawScope.drawNowLine(viewport: ChartViewport, now: Long, x: (Long) -> Float, top: Float, bottom: Float, color: Color) {
    if (now !in viewport) return
    drawLine(color, Offset(x(now), top), Offset(x(now), bottom), 1.dp.toPx())
}
