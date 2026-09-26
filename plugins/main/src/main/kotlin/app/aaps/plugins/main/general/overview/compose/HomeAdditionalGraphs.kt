package app.aaps.plugins.main.general.overview.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.plugins.main.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeAdditionalGraphs(data: HomeChartData, settings: AdditionalGraphSettings, onSettings: (AdditionalGraphSettings) -> Unit) {
    var configure by remember { mutableStateOf(false) }
    val title = stringResource(R.string.overview_additional_graphs)
    val colors = AapsTheme.colors
    val labels = AdditionalSeries.entries.associateWith { stringResource(it.labelResource()) }
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
    TextButton(onClick = { configure = true }) { Text(title) }
    for (graph in 1..4) {
        val selected = AdditionalSeries.entries.filter { settings.graph(it) == graph }
        if (selected.isEmpty()) continue
        AapsCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.overview_graph_number, graph), style = AapsTheme.type.label, color = colors.textSecondary)
                // At most two unit scales in one panel. More units get another aligned panel,
                // never an unlabeled multiplier or a shared axis for incompatible quantities.
                selected.groupBy { units.getValue(it) }.values.toList().chunked(2).forEach { axes ->
                    axes.forEachIndexed { index, series ->
                        series.forEach { kind ->
                            val side = stringResource(if (index == 0) R.string.overview_graph_left else R.string.overview_graph_right)
                            val label = "${labels.getValue(kind)} (${units.getValue(kind)}, $side)"
                            val points = data.additional.points[kind].orEmpty()
                            Text(
                                if (points.isEmpty()) "$label — ${stringResource(R.string.overview_graph_no_data)}" else label,
                                style = AapsTheme.type.caption, color = tints.getValue(kind)
                            )
                        }
                    }
                    AdditionalGraphPanel(data, axes, tints, axes.flatten().joinToString { labels.getValue(it) })
                }
                if (AdditionalSeries.BGI in selected)
                    Text(stringResource(R.string.overview_graph_bgi_sign), style = AapsTheme.type.caption, color = colors.textSecondary)
                if (AdditionalSeries.SENSITIVITY in selected)
                    Text(stringResource(R.string.overview_graph_sensitivity_zero), style = AapsTheme.type.caption, color = colors.textSecondary)
            }
        }
    }
    if (configure) {
        AlertDialog(
            onDismissRequest = { configure = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.overview_graph_choose))
                    AdditionalSeries.entries.forEach { kind ->
                        Text(labels.getValue(kind))
                        SegmentedControl(
                            options = listOf(stringResource(R.string.overview_graph_hidden), "1", "2", "3", "4"),
                            selectedIndex = settings.graph(kind),
                            onSelect = { onSettings(settings.withGraph(kind, it)) }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { configure = false }) { Text(stringResource(android.R.string.ok)) } }
        )
    }
}

private fun AdditionalSeries.labelResource(): Int = when (this) {
    AdditionalSeries.IOB         -> R.string.overview_show_iob
    AdditionalSeries.COB         -> R.string.overview_show_cob
    AdditionalSeries.SENSITIVITY -> R.string.overview_show_sensitivity
    AdditionalSeries.DEVIATIONS -> R.string.overview_show_deviations
    AdditionalSeries.BGI         -> R.string.overview_show_bgi
}

@Composable
private fun AdditionalGraphPanel(data: HomeChartData, axes: List<List<AdditionalSeries>>, tints: Map<AdditionalSeries, Color>, description: String) {
    val colors = AapsTheme.colors
    val measurer = rememberTextMeasurer()
    val textStyle = AapsTheme.type.caption.copy(fontSize = 10.sp, color = colors.textSecondary)
    val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    Canvas(Modifier.fillMaxWidth().height(145.dp).semantics { contentDescription = description }) {
        if (data.to <= data.from) return@Canvas
        val left = 48.dp.toPx()
        val right = size.width - 48.dp.toPx()
        val top = 10.dp.toPx()
        val bottom = size.height - 24.dp.toPx()
        if (right <= left || bottom <= top) return@Canvas
        fun x(time: Long) = left + ((time - data.from).toDouble() / (data.to - data.from)).toFloat() * (right - left)
        axes.forEachIndexed { index, series ->
            val points = series.flatMap { data.additional.points[it].orEmpty() }
            if (points.isEmpty()) return@forEachIndexed
            val (low, high) = additionalGraphBounds(points, if (AdditionalSeries.SENSITIVITY in series) 10.0 else 0.2)
            fun y(value: Double) = bottom - ((value - low) / (high - low)).toFloat() * (bottom - top)
            listOf(low, 0.0, high).forEach { value ->
                val label = measurer.measure(String.format(Locale.getDefault(), "%.1f", value), textStyle)
                val labelX = if (index == 0) left - label.size.width - 4.dp.toPx() else right + 4.dp.toPx()
                drawText(label, topLeft = Offset(labelX, (y(value) - label.size.height / 2).coerceAtLeast(0f)))
            }
            drawLine(colors.divider, Offset(left, y(0.0)), Offset(right, y(0.0)), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            series.forEach { kind ->
                var previous: GlucosePoint? = null
                data.additional.points[kind].orEmpty().forEach { point ->
                    val before = previous
                    // Gaps in CGM/calculation history remain gaps, even when only one point exists.
                    if (before != null && point.time - before.time in 1..ADDITIONAL_GRAPH_GAP_MS)
                        drawLine(tints.getValue(kind), Offset(x(before.time), y(before.value)), Offset(x(point.time), y(point.value)), 1.5.dp.toPx())
                    drawCircle(tints.getValue(kind), 1.5.dp.toPx(), Offset(x(point.time), y(point.value)))
                    previous = point
                }
            }
        }
        if (data.now in data.from..data.to)
            drawLine(colors.textTertiary, Offset(x(data.now), top), Offset(x(data.now), bottom), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
        listOf(data.from, data.from + (data.to - data.from) / 2, data.to).forEach { time ->
            val label = measurer.measure(timeFormat.format(Date(time)), textStyle)
            drawText(label, topLeft = Offset(x(time) - label.size.width / 2, bottom + 6.dp.toPx()))
        }
    }
}
