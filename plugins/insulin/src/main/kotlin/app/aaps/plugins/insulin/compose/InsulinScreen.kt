package app.aaps.plugins.insulin.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.plugins.insulin.R
import java.util.Locale

/** Read-only details and activity-curve preview for the insulin selected in Config Builder. */
@Composable
fun InsulinScreen(state: InsulinUiState) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Text("Insulin", style = AapsTheme.type.title, color = colors.textPrimary, modifier = Modifier.padding(vertical = 14.dp))

        Text(state.activeName, style = AapsTheme.type.cardValue, color = colors.textPrimary, modifier = Modifier.padding(top = 2.dp))
        if (state.comment.isNotBlank()) {
            Text(state.comment, style = AapsTheme.type.caption, color = colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
        }
        Text(
            stringResource(R.string.insulin_config_builder_hint),
            style = AapsTheme.type.caption, color = colors.textSecondary, modifier = Modifier.padding(top = 6.dp, bottom = AapsSpacing.sectionGap)
        )

        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
            Column {
                Text(stringResource(R.string.insulin_activity_axis_title), style = AapsTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 12.dp))
                val curve = state.activityCurve
                if (curve != null) {
                    InsulinCurve(curve, Modifier.fillMaxWidth().height(180.dp))
                } else {
                    Text(stringResource(R.string.insulin_activity_unavailable), style = AapsTheme.type.caption, color = colors.textSecondary)
                }
                Text(
                    stringResource(R.string.insulin_activity_description),
                    style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(top = 12.dp)
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
            Tile("DURATION (DIA)", fmtHours(state.diaHours), Modifier.weight(1f))
            Tile("PEAK TIME", "${state.peakMinutes} min", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    val colors = AapsTheme.colors
    AapsCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = AapsTheme.type.label, color = colors.textSecondary)
            Text(value, style = AapsTheme.type.cardValue, color = colors.textPrimary)
        }
    }
}

@Composable
private fun InsulinCurve(curve: InsulinActivityCurve, modifier: Modifier) {
    val colors = AapsTheme.colors
    val measurer = rememberTextMeasurer()
    val axisStyle = AapsTheme.type.caption.copy(color = colors.textTertiary)
    val peakStyle = axisStyle.copy(color = colors.accentOnLight)
    val peakLabel = stringResource(R.string.insulin_activity_peak, curve.peak.minutes.toInt())
    val description = stringResource(
        R.string.insulin_activity_accessibility,
        curve.peak.minutes.toInt(), fmtHours(curve.durationMinutes / 60.0),
        fmtActivity(curve.peak.percentPerHour), fmtActivity(curve.axisMaxPercentPerHour)
    )
    Canvas(modifier.semantics { contentDescription = description }) {
        val ticks = listOf(curve.axisMaxPercentPerHour, curve.axisMaxPercentPerHour / 2.0, 0.0)
        val tickLabels = ticks.map { measurer.measure(fmtActivity(it), axisStyle) }
        val timeStart = measurer.measure("0", axisStyle)
        val timeEnd = measurer.measure(fmtHours(curve.durationMinutes / 60.0), axisStyle)
        val peakText = measurer.measure(peakLabel, peakStyle)
        val gap = 6.dp.toPx()
        val left = tickLabels.maxOf { it.size.width }.toFloat() + gap
        val right = size.width - 1.dp.toPx()
        val top = peakText.size.height + gap
        val bottom = size.height - maxOf(timeStart.size.height, timeEnd.size.height) - gap
        if (right <= left || bottom <= top) return@Canvas
        fun x(minutes: Double) = left + (minutes / curve.durationMinutes).toFloat() * (right - left)
        fun y(percentPerHour: Double) = bottom - (percentPerHour / curve.axisMaxPercentPerHour).toFloat() * (bottom - top)

        ticks.zip(tickLabels).forEach { (value, label) ->
            val tickY = y(value)
            drawLine(colors.divider, Offset(left, tickY), Offset(right, tickY), strokeWidth = 1.dp.toPx())
            drawText(label, topLeft = Offset(left - gap - label.size.width, tickY - label.size.height / 2f))
        }
        drawText(timeStart, topLeft = Offset(left, bottom + gap))
        drawText(timeEnd, topLeft = Offset(right - timeEnd.size.width, bottom + gap))

        val path = Path()
        curve.points.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(x(point.minutes), y(point.percentPerHour))
            else path.lineTo(x(point.minutes), y(point.percentPerHour))
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(right, bottom)
            lineTo(left, bottom)
            close()
        }
        drawPath(fill, colors.accent.copy(alpha = 0.12f))
        drawPath(path, colors.accent, style = Stroke(width = 1.5.dp.toPx()))

        val peakX = x(curve.peak.minutes)
        drawLine(
            colors.accent.copy(alpha = 0.5f), Offset(peakX, top), Offset(peakX, bottom),
            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        )
        drawCircle(colors.accent, radius = 3.dp.toPx(), center = Offset(peakX, y(curve.peak.percentPerHour)))
        val labelX = (peakX - peakText.size.width / 2f).coerceIn(left, maxOf(left, right - peakText.size.width))
        drawText(peakText, topLeft = Offset(labelX, 0f))
    }
}

private fun fmtHours(h: Double) = String.format(Locale.getDefault(), "%.1f h", h)

private fun fmtActivity(value: Double) = String.format(Locale.getDefault(), "%.0f", value)
