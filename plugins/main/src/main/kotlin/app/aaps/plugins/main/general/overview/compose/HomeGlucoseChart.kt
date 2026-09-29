package app.aaps.plugins.main.general.overview.compose

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.compose.theme.AapsColors
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.plugins.main.general.overview.TARGET_SAMPLE_INTERVAL_MS
import app.aaps.plugins.main.R
import java.util.Locale
import kotlin.math.sqrt

/** Glucose, treatments and basal panels sharing a fixed-width, pannable time axis. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeGlucoseChart(
    data: HomeChartData,
    window: ChartWindow,
    panState: ChartPanState,
    modifier: Modifier = Modifier,
    settings: HomeGraphSettings = HomeGraphSettings(),
    insets: ChartInsets = rememberChartInsets(data, AdditionalGraphSettings.decode(""))
) {
    // Clear the old position, not just ignore it: switching back must not revive an earlier pan.
    LaunchedEffect(window, panState) { panState.returnToLive() }
    val colors = AapsTheme.colors
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val caption = AapsTheme.type.caption
    val axisStyle = caption.copy(fontSize = 9.sp, color = colors.textTertiary)
    val dayStyle = axisStyle.copy(color = colors.textSecondary)
    val valueStyle = caption.copy(fontSize = 13.sp)
    val clock = rememberChartClock(data.now)
    // Scales are fixed per snapshot, so nothing jumps vertically while dragging sideways.
    val glucoseScale = remember(data) { data.glucoseBounds() }
    val insulinScale = remember(data) { insulinScaleMax(data.basal) }
    val pan = rememberChartPan(panState, window, data)
    val paused by remember(panState, window) { derivedStateOf { panState.isPaused(window) } }
    val noForecast = if (settings.forecasts.isNotEmpty() && data.predictions.isEmpty()) stringResource(R.string.overview_graph_no_forecast) else null
    val basalUnit = stringResource(R.string.overview_graph_units_basal)
    val description = stringResource(R.string.overview_graph_a11y, (window.historyMs / HOUR_MS).toInt())

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .chartPan(pan, data.hasData, insets)
                    .semantics { contentDescription = description }
            ) {
                if (!data.hasData) return@Canvas
                // The pan position is read here, in the draw phase: dragging redraws without recomposing.
                val viewport = panState.viewport(window, data.from, data.now)
                drawChart(
                    data, viewport, settings, glucoseScale, insulinScale, clock, colors, measurer,
                    ChartStyles(axisStyle, dayStyle, valueStyle), noForecast, basalUnit, insets
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = paused,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = insets.end),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ReturnToNowPill(onClick = { pan.returnToNow() })
            }
        }
        val showScheduled = settings.visible(GlucoseOverlay.BASAL) && data.basal.any { it.scheduled > 0.0 }
        if (data.targets.isNotEmpty() || data.predictions.isNotEmpty() || showScheduled)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (data.targets.isNotEmpty())
                    LegendItem(stringResource(R.string.overview_graph_target), colors.accent, LegendMark.DASH)
                data.predictions.forEach { series ->
                    LegendItem(stringResource(series.kind.labelResource()), series.kind.color(), LegendMark.DASH)
                }
                if (showScheduled)
                    LegendItem(stringResource(R.string.overview_graph_scheduled_basal), colors.textTertiary, LegendMark.DASH)
            }
    }
}

private class ChartStyles(val axis: TextStyle, val day: TextStyle, val value: TextStyle)

private const val RAIL_WEIGHT = 0.10f      // treatment rail
private const val INSULIN_WEIGHT = 0.28f   // delivered insulin

private fun DrawScope.drawChart(
    d: HomeChartData,
    vp: ChartViewport,
    settings: HomeGraphSettings,
    glucoseScale: Pair<Double, Double>,
    insulinMax: Double,
    clock: ChartClock,
    colors: AapsColors,
    measurer: TextMeasurer,
    styles: ChartStyles,
    noForecast: String?,
    basalUnit: String,
    insets: ChartInsets
) {
    val plotLeft = insets.start.toPx()
    val plotRight = size.width - insets.end.toPx()
    val plotW = plotRight - plotLeft
    val axisH = 16.dp.toPx()
    if (plotW <= 0f || size.height <= axisH) return

    val gTop = 4.dp.toPx()
    val bodyBottom = size.height - axisH
    val bodyH = bodyBottom - gTop
    val showBasal = settings.visible(GlucoseOverlay.BASAL)
    val showTreatments = settings.visible(GlucoseOverlay.TREATMENTS)
    val iH = if (showBasal) bodyH * INSULIN_WEIGHT else 0f
    val railH = if (showTreatments) bodyH * RAIL_WEIGHT else 0f
    val gH = bodyH - iH - railH
    val gBottom = gTop + gH
    val railY = gBottom + railH / 2
    val iTop = gBottom + railH

    val span = vp.span.toFloat()
    fun x(t: Long): Float = plotLeft + (t - vp.start) / span * plotW

    // Glucose scale: always show the band plus a little headroom, and grow for excursions.
    val (gLo, gHi) = glucoseScale
    fun y(v: Double): Float = gTop + ((gHi - v.coerceIn(gLo, gHi)) / (gHi - gLo)).toFloat() * gH

    // ---- ground: future tint and hour grid, behind everything ----
    val ticks = timeTicks(vp, plotW, clock, measurer, styles.axis)
    drawFutureShade(vp, d.now, ::x, plotRight, gTop, bodyBottom, colors.textOnSurfaceStrong.copy(alpha = 0.04f))
    drawTickGrid(ticks, ::x, gTop, bodyBottom, colors.divider)

    // ---- display threshold band ----
    drawRect(
        color = colors.inRange.copy(alpha = 0.08f),
        topLeft = Offset(plotLeft, y(d.highMark)),
        size = Size(plotW, y(d.lowMark) - y(d.highMark))
    )

    // ---- the only two gridlines that mean anything clinically ----
    val labelGap = 6.dp.toPx()
    listOf(d.lowMark, d.highMark).forEach { v ->
        drawLine(colors.inRange.copy(alpha = 0.22f), Offset(plotLeft, y(v)), Offset(plotRight, y(v)), 1f)
        drawAxisValue(measurer, fmt(v, d.decimals), plotLeft - labelGap, y(v), styles.axis, alignEnd = true)
    }
    // Do not print the scale maximum over the high-threshold label when they are close.
    val axisLabelHeight = measurer.measure(fmt(gHi, d.decimals), styles.axis).size.height
    if (y(d.highMark) - y(gHi) > axisLabelHeight * 1.5f)
        drawAxisValue(measurer, fmt(gHi, d.decimals), plotLeft - labelGap, y(gHi), styles.axis, alignEnd = true)

    // Insulin scale: the top value and its unit stacked in the gutter, not a label inside the panel.
    if (showBasal) {
        val rateLabel = measurer.measure(formatBasalRate(insulinMax), styles.axis).size.height
        drawAxisValue(measurer, formatBasalRate(insulinMax), plotLeft - labelGap, iTop + rateLabel / 2f, styles.axis, alignEnd = true)
        drawAxisValue(measurer, basalUnit, plotLeft - labelGap, iTop + rateLabel * 1.5f, styles.axis, alignEnd = true)
        drawLine(colors.divider.copy(alpha = colors.divider.alpha * 0.6f), Offset(plotLeft, iTop), Offset(plotRight, iTop), 1f)
    }
    if (showTreatments) drawLine(colors.divider, Offset(plotLeft, railY), Offset(plotRight, railY), 1f)

    // Everything with a timestamp is clipped to the plot, so a panned window never paints into the gutters.
    clipRect(plotLeft, 0f, plotRight, bodyBottom) {

        // Keep the full path origin: viewport slicing resets dash phase during pan.
        val targets = d.targets
        if (targets.isNotEmpty()) {
            val path = Path()
            targets.forEachIndexed { index, point ->
                // Missing profile history leaves a gap rather than connecting invented target values.
                if (index == 0 || point.time - targets[index - 1].time > TARGET_SAMPLE_INTERVAL_MS)
                    path.moveTo(x(point.time), y(point.value))
                else {
                    path.lineTo(x(point.time), y(targets[index - 1].value))
                    path.lineTo(x(point.time), y(point.value))
                }
            }
            drawPath(path, colors.accent, style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 3.dp.toPx()))))
        }

        // Raw dots remain visible beneath the loop-bucketed trace.
        if (settings.visible(GlucoseOverlay.RAW_READINGS) && d.hasDenseScatter) {
            val r = 1.dp.toPx()
            d.readings.visibleSlice(vp.start, vp.end) { it.time }.forEach { p ->
                drawCircle(colors.textOnSurfaceStrong.copy(alpha = 0.28f), radius = r, center = Offset(x(p.time), y(p.value)))
            }
        }

        // ---- area under the trace, per contiguous run: a sensor gap stays empty ----
        val pts = d.trace.visibleSlice(vp.start, vp.end) { it.time }
        val areaBrush = Brush.verticalGradient(
            0f to colors.textOnSurfaceStrong.copy(alpha = 0.14f),
            1f to Color.Transparent,
            startY = gTop, endY = gBottom
        )
        pts.segments(TRACE_GAP_MS) { it.time }.forEach { run ->
            if (run.size > 1) {
                val area = Path().apply {
                    moveTo(x(run.first().time), gBottom)
                    run.forEach { lineTo(x(it.time), y(it.value)) }
                    lineTo(x(run.last().time), gBottom)
                    close()
                }
                drawPath(area, areaBrush)
            } else {
                // An isolated reading is still a reading: a dot, not nothing.
                val p = run.single()
                drawCircle(stateColor(p.value, d.lowMark, d.highMark, colors), 1.8.dp.toPx(), Offset(x(p.time), y(p.value)))
            }
        }

        // ---- trace, segment-tinted; a long gap is a sensor dropout, not a line ----
        val strokeW = 2.dp.toPx()
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            if (b.time - a.time > TRACE_GAP_MS) continue
            drawLine(
                color = stateColor((a.value + b.value) / 2, d.lowMark, d.highMark, colors),
                start = Offset(x(a.time), y(a.value)),
                end = Offset(x(b.time), y(b.value)),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
        }

        // Dashed forecasts have no fill, measured-value marker or connection to the historical trace.
        d.predictions.forEach { series ->
            // Keep forecast dash origins stable when their first samples leave the viewport.
            val path = Path()
            series.points.forEachIndexed { index, point ->
                val previous = series.points.getOrNull(index - 1)
                if (previous == null || point.time - previous.time != 5 * 60_000L) path.moveTo(x(point.time), y(point.value))
                else path.lineTo(x(point.time), y(point.value))
            }
            drawPath(path, series.kind.color(), style = Stroke(1.8.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))))
            // A lone remaining prediction is still an estimate, rendered as an unfilled point.
            if (series.points.size == 1) {
                val point = series.points.single()
                drawCircle(series.kind.color(), 2.dp.toPx(), Offset(x(point.time), y(point.value)), style = Stroke(1.dp.toPx()))
            }
        }

        // ---- treatment rail ----
        val margin = 15 * 60_000L // a mark partly visible at the edge is still drawn
        d.treatments.filter { it.time in (vp.start - margin)..(vp.end + margin) }.forEach { t ->
            when (t.kind) {
                TreatmentKind.CARBS -> drawCircle(
                    colors.high.copy(alpha = 0.9f),
                    radius = (sqrt(t.amount).toFloat() * 0.6f).coerceIn(2.5f, 6f).dp.toPx(),
                    center = Offset(x(t.time), railY)
                )

                TreatmentKind.BOLUS,
                TreatmentKind.SMB   -> {
                    val smb = t.kind == TreatmentKind.SMB
                    val h = (t.amount.toFloat() * 1.1f).coerceIn(4f, 13f).dp.toPx()
                    drawLine(
                        colors.accent.copy(alpha = if (smb) 0.65f else 1f),
                        Offset(x(t.time), railY - h / 2), Offset(x(t.time), railY + h / 2),
                        strokeWidth = (if (smb) 1.5f else 2.6f).dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // ---- delivered insulin, with the historical scheduled rate as a dashed reference ----
        if (showBasal) {
            val iBottom = iTop + iH
            fun iy(r: Double): Float = iBottom - (r.coerceIn(0.0, insulinMax) / insulinMax).toFloat() * iH
            fun stepPath(run: List<BasalStep>, rate: (BasalStep) -> Double) = Path().apply {
                moveTo(x(run.first().time), iy(rate(run.first())))
                for (i in 1 until run.size) {
                    lineTo(x(run[i].time), iy(rate(run[i - 1])))
                    lineTo(x(run[i].time), iy(rate(run[i])))
                }
            }
            val fill = Brush.verticalGradient(
                0f to colors.accent.copy(alpha = 0.38f),
                1f to colors.accent.copy(alpha = 0.06f),
                startY = iTop, endY = iBottom
            )
            val scheduledEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            // Clip full basal runs to preserve their dash origins while panning.
            d.basal.segments(BASAL_GAP_MS) { it.time }.forEach { run ->
                if (run.size < 2) return@forEach
                val line = stepPath(run) { it.rate }
                val area = stepPath(run) { it.rate }.apply {
                    lineTo(x(run.last().time), iBottom)
                    lineTo(x(run.first().time), iBottom)
                    close()
                }
                drawPath(area, fill)
                drawPath(line, colors.accent, style = Stroke(width = 1.4.dp.toPx()))
                if (run.any { it.scheduled > 0.0 })
                    drawPath(stepPath(run) { it.scheduled }, colors.textTertiary, style = Stroke(width = 1.dp.toPx(), pathEffect = scheduledEffect))
            }
        }

        // The future area is kept even without forecasts; say so quietly instead of resizing.
        if (noForecast != null && vp.end > d.now) {
            val left = x(maxOf(d.now, vp.start))
            val laid = measurer.measure(noForecast, styles.axis)
            if (plotRight - left > laid.size.width + 12.dp.toPx())
                drawText(laid, topLeft = Offset((left + plotRight - laid.size.width) / 2f, gTop + (gH - laid.size.height) / 2f))
        }
    }

    // ---- time axis ----
    drawTickLabels(ticks, ::x, size.height - 2.dp.toPx(), measurer, styles.axis, styles.day)

    // ---- now: the clock, independent of when the last reading arrived ----
    drawNowLine(vp, d.now, ::x, gTop, bodyBottom, colors.textSecondary.copy(alpha = 0.35f))

    // ---- latest reading, at its own timestamp (a stale value is NOT moved up to "now") ----
    val last = d.latest ?: return
    if (last.time !in vp) return
    val markX = x(last.time)
    val markY = y(last.value)
    val stateC = stateColor(last.value, d.lowMark, d.highMark, colors)
    drawCircle(stateC.copy(alpha = 0.16f), radius = 7.dp.toPx(), center = Offset(markX, markY))
    drawCircle(stateC, radius = 3.4.dp.toPx(), center = Offset(markX, markY))
    drawCircle(colors.surface, radius = 3.4.dp.toPx(), center = Offset(markX, markY), style = Stroke(1.4.dp.toPx()))

    // The trace runs into the endpoint, so the current value gets its own ground rather than being
    // printed over the line.
    val laid = measurer.measure(fmt(last.value, d.decimals), styles.value.copy(color = stateC))
    val chipW = laid.size.width + 8.dp.toPx()
    val chipH = laid.size.height + 3.dp.toPx()
    val chipX = (markX - 10.dp.toPx() - chipW).coerceIn(plotLeft, (plotRight - chipW).coerceAtLeast(plotLeft))
    val chipY = (markY - 10.dp.toPx() - chipH).coerceAtLeast(gTop)
    drawRoundRect(
        colors.surface.copy(alpha = 0.92f), topLeft = Offset(chipX, chipY), size = Size(chipW, chipH),
        cornerRadius = CornerRadius(4.dp.toPx())
    )
    drawText(laid, topLeft = Offset(chipX + 4.dp.toPx(), chipY + 1.5.dp.toPx()))
}

private fun stateColor(v: Double, low: Double, high: Double, colors: AapsColors): Color = when {
    v < low  -> colors.low
    v > high -> colors.high
    else     -> colors.textOnSurfaceStrong
}

private fun fmt(v: Double, decimals: Int): String =
    if (decimals <= 0) String.format(Locale.getDefault(), "%.0f", v)
    else String.format(Locale.getDefault(), "%.${decimals}f", v)

/** Insulin scale top: "1", "1.5", "0.75" — no trailing zeros on a tiny axis. */
internal fun formatBasalRate(v: Double): String = when {
    kotlin.math.abs(v - Math.round(v)) < 1e-6           -> String.format(Locale.getDefault(), "%.0f", v)
    kotlin.math.abs(v * 2 - Math.round(v * 2)) < 1e-6   -> String.format(Locale.getDefault(), "%.1f", v)
    else                                                -> String.format(Locale.getDefault(), "%.2f", v)
}

// AAPS prediction colours, kept independent from the measured-glucose range colours.
private fun PredictionKind.color(): Color = when (this) {
    PredictionKind.IOB   -> Color(0xFF6495ED)
    PredictionKind.COB   -> Color(0xFFFFA500)
    PredictionKind.ZT    -> Color(0xFF00BFFF)
    PredictionKind.UAM   -> Color(0xFFBDB76B)
    PredictionKind.A_COB -> Color(0xFFD98C40)
}

internal fun PredictionKind.labelResource(): Int = when (this) {
    PredictionKind.IOB   -> R.string.overview_prediction_iob
    PredictionKind.COB   -> R.string.overview_prediction_cob
    PredictionKind.ZT    -> R.string.overview_prediction_zt
    PredictionKind.UAM   -> R.string.overview_prediction_uam
    PredictionKind.A_COB -> R.string.overview_prediction_acob
}
