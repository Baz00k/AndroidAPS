package app.aaps.ui.activities.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import java.util.Locale
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.compose.components.SectionLabel
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import kotlin.math.roundToInt

/** Screen callbacks. [onRecalculate] is called only after the user confirms it. */
@Immutable
class StatsActions(
    val onRange: (Int) -> Unit,
    val onBack: () -> Unit,
    val onRecalculate: () -> Unit = {},
    val onShowCycle: () -> Unit = {},
    val onCycleOffset: (Int) -> Unit = {}
)

/**
 * Statistics with independently loading glucose and insulin summaries. The only write it can start is
 * the confirmed rebuild of cached daily insulin totals.
 */
@Composable
fun StatsScreen(state: StatsUiState, actions: StatsActions, cycle: CycleUiState = CycleUiState(), recalculation: Recalculation = Recalculation.IDLE) {
    val colors = AapsTheme.colors
    val onRange = actions.onRange
    val onBack = actions.onBack
    val ranges = listOf(7, 30, 90)
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AapsSpacing.screenH)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.textSecondary) }
            Text("Statistics", style = AapsTheme.type.title, color = colors.textPrimary, modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        SegmentedControl(ranges.map { "${it}d" }, ranges.indexOf(state.rangeDays).coerceAtLeast(0), { onRange(ranges[it]) }, fillWidth = true)
        if (state.loading || state.failed) {
            AapsCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent)
                        Text("Loading statistics…", color = colors.textSecondary)
                    } else {
                        Text("Could not load statistics", color = colors.textPrimary)
                        TextButton(onClick = { onRange(state.rangeDays) }) { Text("Retry") }
                    }
                }
            }
            return@Column
        }
        DataCoverage(state)
        if (state.refreshFailed) {
            AapsCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Could not update. These figures may be out of date.", color = colors.high)
                    TextButton(onClick = { onRange(state.rangeDays) }) { Text("Retry") }
                }
            }
        }

        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("Time in range")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (state.tirReadings == 0) "--" else "${state.inRange.roundToInt()}%", style = AapsTheme.type.hero.copy(fontSize = 56.sp, lineHeight = 56.sp), color = colors.inRange)
                    Text("in range", style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                }
                Row(
                    Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(colors.controlFill)
                ) {
                    Seg(state.veryLow, colors.veryLow)
                    Seg(state.low, colors.low)
                    Seg(state.inRange, colors.inRange)
                    Seg(state.high, colors.high)
                    Seg(state.veryHigh, colors.veryHigh)
                }
                if (state.tirReadings > 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        BarLabel("Low", state.veryLow + state.low, colors.low, colors.textTertiary)
                        BarLabel("In range", state.inRange, colors.inRange, colors.textTertiary)
                        BarLabel("High", state.high + state.veryHigh, colors.high, colors.textTertiary)
                    }
                    ValueRow("Your range", "${fmt(state.lowMark, state.decimals)}–${fmt(state.highMark, state.decimals)} ${state.avgGlucoseUnit}")
                    state.completed?.let { ValueRow("In tight range (${tightRange(state)})", "${it.tight.roundToInt()}%") }
                }
            }
        }

        val tile = Modifier.fillMaxHeight()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = AapsSpacing.rowGap), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
            StatTile("AVERAGE GLUCOSE", if (state.avgGlucose == "--") "--" else noBreak("${state.avgGlucose} ${state.avgGlucoseUnit}"), tile.weight(1f))
            StatTile("ESTIMATED A1C", state.gmi, tile.weight(1f))
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
            StatTile("VARIABILITY", state.cv, tile.weight(1f))
            StatTile("DAILY INSULIN", if (state.insulinLoading) "…" else state.avgTdd, tile.weight(1f))
        }

        GlucoseProfile(state)

        CompletedDayReports(state)
        DailyInsulin(state, recalculation, actions.onRecalculate) { onRange(state.rangeDays) }
        CyclePatternCard(cycle, actions.onShowCycle, actions.onCycleOffset)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Seg(pct: Double, color: Color) {
    if (pct > 0.0) Box(Modifier.weight(pct.toFloat()).fillMaxHeight().background(color))
}

@Composable
private fun BarLabel(name: String, pct: Double, dot: Color, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        Text("  $name ${pct.roundToInt()}%", style = AapsTheme.type.caption, color = textColor)
    }
}

/** Keeps units such as mg/dL in one piece; a line may still break between the number and its unit. */
internal fun noBreak(text: String) = text.replace("/", "/\u2060")

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = AapsTheme.colors
    AapsCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = AapsTheme.type.label.copy(fontSize = 9.sp), color = colors.textSecondary)
            Text(value, style = AapsTheme.type.cardValue, color = colors.textPrimary)
        }
    }
}

/**
 * How much glucose data the figures stand on, said once for the whole screen. Below the consensus share of
 * readings the figures are still shown, but the line says they may not be reliable.
 */
@Composable
private fun DataCoverage(state: StatsUiState) {
    val colors = AapsTheme.colors
    val completed = state.completed
    val sparse = completed == null || completed.coverage < MIN_COVERAGE
    val text = when {
        completed == null || completed.readings == 0 -> "No glucose data on these days"
        sparse -> "Glucose data on ${completed.daysWithData} of ${state.rangeDays} days, only ${completed.coverage}% of readings. Figures may not be reliable."
        else -> "Glucose data on ${completed.daysWithData} of ${state.rangeDays} days, ${completed.coverage}% of readings"
    }
    Text(text, style = AapsTheme.type.body, color = if (sparse) colors.high else colors.textSecondary,
         modifier = Modifier.padding(top = 10.dp, bottom = 12.dp))
}

/**
 * Ambulatory glucose profile: what a typical day looks like, hour by hour.
 *
 * A percentage-in-range bar per hour came first, and could not answer the question that matters — an
 * hour can be entirely "in range" while sitting at 4.5 or at 9.8, which call for opposite corrections.
 * Here the median line says where the hour typically sits, and the bands say how reproducible it is:
 * a narrow band is a habit, a wide one is a coin toss.
 */
@Composable
private fun GlucoseProfile(state: StatsUiState) {
    val colors = AapsTheme.colors
    val measurer = rememberTextMeasurer()
    val axis = AapsTheme.type.caption.copy(fontSize = 9.sp, color = colors.textTertiary)

    // One day is a day, not a pattern; the coverage line above says how many days there are.
    val hours = state.hourly.filter { it.readings > 0 }.takeIf { profile -> profile.maxOfOrNull { it.days } ?: 0 > 1 }.orEmpty()

    AapsCard(Modifier.fillMaxWidth().padding(top = AapsSpacing.sectionGap)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("A typical day")
            Text(state.avgGlucoseUnit, style = AapsTheme.type.caption, color = colors.textSecondary)
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                if (hours.size < 2) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Not enough data", style = AapsTheme.type.body, color = colors.textTertiary)
                    }
                } else {
                    Canvas(Modifier.fillMaxSize()) {
                        drawProfile(state, hours, colors, measurer, axis)
                    }
                }
            }
            if (hours.size >= 2) ChartLegend("Median" to colors.textPrimary, "Middle 50%" to colors.accent.copy(alpha = 0.55f),
                                             "Middle 80%" to colors.accent.copy(alpha = 0.22f), modifier = Modifier.padding(top = 10.dp))
        }
    }
}

private fun DrawScope.drawProfile(
    state: StatsUiState,
    hours: List<HourProfile>,
    colors: app.aaps.core.compose.theme.AapsColors,
    measurer: TextMeasurer,
    axis: TextStyle
) {
    val left = (if (state.decimals == 0) 34 else 30).dp.toPx()
    val bottom = 14.dp.toPx()
    val w = size.width - left - 6.dp.toPx()
    val h = size.height - bottom
    if (w <= 0f || h <= 0f) return

    // A little headroom so the top of the 10-90 band is not drawn flush against the card edge.
    val hi = maxOf(state.highMark + 2.0, hours.maxOf { it.p90 } + 1.0)
    val lo = minOf(state.lowMark - 1.0, hours.minOf { it.p10 } - 0.5).coerceAtLeast(0.0)
    fun y(v: Double) = (((hi - v.coerceIn(lo, hi)) / (hi - lo)).toFloat() * h)
    // Anchor on hour centres so the line spans the full day rather than stopping at 23:00.
    fun x(hour: Int) = left + (hour + 0.5f) / 24f * w

    // target band + the two gridlines that mean something
    drawRect(colors.inRange.copy(alpha = 0.08f), Offset(left, y(state.highMark)), Size(w, y(state.lowMark) - y(state.highMark)))
    listOf(state.lowMark, state.highMark).forEach { v ->
        drawLine(colors.inRange.copy(alpha = 0.22f), Offset(left, y(v)), Offset(left + w, y(v)), 1f)
        val txt = measurer.measure(fmt(v, state.decimals), axis)
        drawText(txt, topLeft = Offset(left - 4.dp.toPx() - txt.size.width, y(v) - txt.size.height / 2f))
    }
    // Top of scale. Without it the highs are unreadable — the point of this chart is the LEVEL, and
    // an unlabelled ceiling cannot distinguish a 14 from a 20.
    measurer.measure(fmt(hi, state.decimals), axis).let { txt ->
        drawText(txt, topLeft = Offset(left - 4.dp.toPx() - txt.size.width, y(hi)))
    }

    fun band(loSel: (HourProfile) -> Double, hiSel: (HourProfile) -> Double, alpha: Float) {
        val path = Path()
        hours.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.hour), y(hiSel(p))) else path.lineTo(x(p.hour), y(hiSel(p))) }
        hours.reversed().forEach { p -> path.lineTo(x(p.hour), y(loSel(p))) }
        path.close()
        drawPath(path, colors.accent.copy(alpha = alpha))
    }
    band({ it.p10 }, { it.p90 }, 0.22f)
    band({ it.p25 }, { it.p75 }, 0.55f)

    // median
    for (i in 1 until hours.size) {
        val a = hours[i - 1]; val b = hours[i]
        drawLine(colors.textPrimary, Offset(x(a.hour), y(a.median)), Offset(x(b.hour), y(b.median)), 2.dp.toPx(), cap = StrokeCap.Round)
    }

    // hour axis
    listOf(0, 6, 12, 18).forEach { hr ->
        val txt = measurer.measure(String.format(Locale.getDefault(), "%02d", hr), axis)
        drawText(txt, topLeft = Offset(x(hr) - txt.size.width / 2f, h + 2.dp.toPx()))
    }
}

internal fun fmt(v: Double, decimals: Int): String =
    if (decimals <= 0) String.format(Locale.getDefault(), "%.0f", v)
    else String.format(Locale.getDefault(), "%.1f", v)
