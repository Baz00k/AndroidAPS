package app.aaps.plugins.main.general.overview.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.theme.AapsColors
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.plugins.main.R
import java.util.Locale
import kotlin.math.abs

/**
 * Optional panels under the glucose graph. They share its time window, future area, "now" line and
 * horizontal position ([panState]), so a moment lines up vertically across every card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeAdditionalGraphs(data: HomeChartData, settings: AdditionalGraphSettings, window: ChartWindow, panState: ChartPanState, insets: ChartInsets = rememberChartInsets(data, settings)) {
    val colors = AapsTheme.colors
    val labels = AdditionalSeries.entries.associateWith { stringResource(it.legendResource()) }
    val units = mapOf(
        AdditionalSeries.IOB to stringResource(R.string.overview_graph_units_insulin),
        AdditionalSeries.COB to stringResource(R.string.overview_graph_units_carbs),
        AdditionalSeries.SENSITIVITY to "%",
        AdditionalSeries.DEVIATIONS to stringResource(R.string.overview_graph_units_impact, data.glucoseUnits),
        AdditionalSeries.BGI to stringResource(R.string.overview_graph_units_impact, data.glucoseUnits)
    )
    val tints = mapOf(
        AdditionalSeries.IOB to colors.iob,
        AdditionalSeries.COB to colors.high,
        AdditionalSeries.SENSITIVITY to colors.accent,
        AdditionalSeries.DEVIATIONS to colors.inRange,
        AdditionalSeries.BGI to colors.textPrimary
    )
    // Conventions stay available to screen readers instead of as permanent paragraphs on screen.
    val notes = mapOf(
        AdditionalSeries.BGI to stringResource(R.string.overview_graph_bgi_sign),
        AdditionalSeries.SENSITIVITY to stringResource(R.string.overview_graph_sensitivity_zero)
    )
    val noData = stringResource(R.string.overview_graph_no_data)
    for (graph in 1..4) {
        val selected = AdditionalSeries.entries.filter { settings.graph(it) == graph }
        if (selected.isEmpty()) continue
        AapsCard(contentPadding = CHART_CARD_PADDING) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // At most two unit scales in one panel. More units get another aligned panel,
                // never an unlabeled multiplier or a shared axis for incompatible quantities.
                selected.groupBy { it.axisGroup() }.entries.toList().chunked(2).forEach { axes ->
                    val series = axes.map { it.value }
                    val description = buildString {
                        append(series.flatten().joinToString { "${labels.getValue(it)} (${units.getValue(it)})" })
                        series.flatten().mapNotNull { notes[it] }.forEach { append(". ").append(it) }
                    }
                    AdditionalGraphPanel(data, series, series.map { units.getValue(it.first()) }, tints, description, window, panState, insets)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        series.flatten().forEach { kind ->
                            val empty = data.additional.points[kind].isNullOrEmpty()
                            LegendItem(
                                if (empty) "${labels.getValue(kind)} · $noData" else labels.getValue(kind),
                                tints.getValue(kind),
                                if (kind == AdditionalSeries.DEVIATIONS) LegendMark.BAR else LegendMark.LINE,
                                muted = empty
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun AdditionalSeries.labelResource(): Int = when (this) {
    AdditionalSeries.IOB         -> R.string.overview_show_iob
    AdditionalSeries.COB         -> R.string.overview_show_cob
    AdditionalSeries.SENSITIVITY -> R.string.overview_show_sensitivity
    AdditionalSeries.DEVIATIONS -> R.string.overview_show_deviations
    AdditionalSeries.BGI         -> R.string.overview_show_bgi
}

/** Enough decimals to tell the axis extremes apart, no more: "45" g, "1.8" U, "0.35" mg/dl/5 min. */
internal fun axisDecimals(span: Double): Int = when {
    span >= 20.0 -> 0
    span >= 2.0  -> 1
    else         -> 2
}

/** Legend names: BGI is shown as "−BGI" because the plotted sign is the AAPS graph convention. */
private fun AdditionalSeries.legendResource(): Int =
    if (this == AdditionalSeries.BGI) R.string.bgi_shortname else labelResource()

@Composable
private fun AdditionalGraphPanel(
    data: HomeChartData,
    axes: List<List<AdditionalSeries>>,
    axisUnits: List<String>,
    tints: Map<AdditionalSeries, Color>,
    description: String,
    window: ChartWindow,
    panState: ChartPanState,
    insets: ChartInsets
) {
    val colors = AapsTheme.colors
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val axisStyle = AapsTheme.type.caption.copy(fontSize = 9.sp, color = colors.textTertiary)
    val dayStyle = axisStyle.copy(color = colors.textSecondary)
    val clock = rememberChartClock(data.now)
    val pan = rememberChartPan(panState, window, data)
    // Per-snapshot scales: fixed while panning, like the glucose axis.
    val scales = remember(data, axes) {
        axes.map { series ->
            additionalGraphBounds(series.flatMap { data.additional.points[it].orEmpty() }, if (AdditionalSeries.SENSITIVITY in series) 10.0 else 0.2)
        }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .chartPan(pan, data.hasData, insets)
            .semantics { contentDescription = description }
    ) {
        if (!data.hasData) return@Canvas
        val viewport = panState.viewport(window, data.from, data.now)
        drawAdditionalPanel(data, viewport, axes, axisUnits, scales, tints, colors, measurer, axisStyle, dayStyle, clock, insets)
    }
}

private fun DrawScope.drawAdditionalPanel(
    data: HomeChartData,
    vp: ChartViewport,
    axes: List<List<AdditionalSeries>>,
    axisUnits: List<String>,
    scales: List<Pair<Double, Double>>,
    tints: Map<AdditionalSeries, Color>,
    colors: AapsColors,
    measurer: TextMeasurer,
    axisStyle: TextStyle,
    dayStyle: TextStyle,
    clock: ChartClock,
    insets: ChartInsets
) {
    val left = insets.start.toPx()
    val right = size.width - insets.end.toPx()
    val top = 14.dp.toPx()
    val bottom = size.height - 16.dp.toPx()
    if (right <= left || bottom <= top) return
    val span = vp.span.toFloat()
    fun x(time: Long): Float = left + (time - vp.start) / span * (right - left)

    val ticks = timeTicks(vp, right - left, clock, measurer, axisStyle)
    drawFutureShade(vp, data.now, ::x, right, top, bottom, colors.textOnSurfaceStrong.copy(alpha = 0.04f))
    drawTickGrid(ticks, ::x, top, bottom, colors.divider)

    val gap = 6.dp.toPx()
    axes.forEachIndexed { index, series ->
        val (low, high) = scales[index]
        fun y(value: Double): Float = bottom - ((value - low) / (high - low)).toFloat() * (bottom - top)
        val onLeft = index == 0
        // A single series owns its axis, so its labels carry its colour instead of a "left/right axis" note.
        val style = if (series.size == 1) axisStyle.copy(color = tints.getValue(series.single())) else axisStyle
        val decimals = axisDecimals(high - low)
        val labelX = if (onLeft) left - gap else right + gap
        val labelHeight = measurer.measure("0", style).size.height
        // Zero is useful only if it does not collide with an extreme (e.g. COB near zero).
        val roomForZero = minOf(abs(y(0.0) - y(low)), abs(y(high) - y(0.0))) > labelHeight + 2.dp.toPx()
        listOf(low, 0.0, high).forEach { value ->
            if (value != 0.0 || roomForZero)
                drawAxisValue(measurer, String.format(Locale.getDefault(), "%.${decimals}f", value), labelX, y(value), style, alignEnd = onLeft)
        }
        // Unit caption above its axis, inside the top margin: never over the data.
        drawLabel(measurer, axisUnits[index], if (onLeft) left else right, top - 2.dp.toPx(), style, alignEnd = !onLeft)
        drawLine(
            colors.divider, Offset(left, y(0.0)), Offset(right, y(0.0)),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        )

        clipRect(left, 0f, right, bottom) {
            series.forEach { kind ->
                val points = data.additional.points[kind].orEmpty().visibleSlice(vp.start, vp.end) { it.time }
                if (kind == AdditionalSeries.DEVIATIONS) {
                    // Bars from zero keep the sign readable at a glance. A true zero still shows as a
                    // hairline; a missing sample shows nothing at all.
                    val barWidth = maxOf(1.5f, 5 * 60_000L / span * (right - left) * 0.6f)
                    val zeroY = y(0.0)
                    points.forEach { p ->
                        val valueY = y(p.value)
                        val barTop = minOf(valueY, zeroY)
                        drawRect(
                            if (p.value >= 0.0) tints.getValue(kind) else colors.low,
                            Offset(x(p.time) - barWidth / 2, barTop),
                            Size(barWidth, maxOf(abs(valueY - zeroY), 1f))
                        )
                    }
                } else {
                    // Gaps in CGM/calculation history remain gaps; an isolated sample is a dot.
                    points.segments(ADDITIONAL_GRAPH_GAP_MS) { it.time }.forEach { run ->
                        if (run.size == 1) drawCircle(tints.getValue(kind), 1.8.dp.toPx(), Offset(x(run[0].time), y(run[0].value)))
                        else drawPath(
                            Path().apply {
                                moveTo(x(run[0].time), y(run[0].value))
                                for (i in 1 until run.size) lineTo(x(run[i].time), y(run[i].value))
                            },
                            tints.getValue(kind),
                            style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                }
            }
        }
    }

    drawTickLabels(ticks, ::x, size.height - 2.dp.toPx(), measurer, axisStyle, dayStyle)
    drawNowLine(vp, data.now, ::x, top, bottom, colors.textSecondary.copy(alpha = 0.35f))
}
