package app.aaps.ui.activities.stats

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.GhostButton
import app.aaps.core.compose.components.SectionLabel
import app.aaps.core.compose.components.StepperRow
import app.aaps.core.compose.components.TonalButton
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.configuration.Constants
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Rows shown before "Show all", so a 90-day range does not bury the sections below it. */
private const val COLLAPSED_ROWS = 7

/** Time in range for each day, and the glucose pentagon. */
@Composable
internal fun CompletedDayReports(state: StatsUiState) {
    val completed = state.completed ?: return
    if (completed.readings == 0) return
    DailyTimeInRange(completed)
    PentagonCard(state)
}

@Composable
private fun DailyTimeInRange(completed: CompletedGlucose) {
    var all by rememberSaveable { mutableStateOf(false) }
    AapsCard(Modifier.fillMaxWidth().padding(top = AapsSpacing.sectionGap)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Time in range by day")
            TableRow(listOf("Day", "Low", "In range", "Tight", "High"), header = true)
            val days = if (all) completed.days else completed.days.take(COLLAPSED_ROWS)
            days.forEach { day ->
                if (day.readings == 0) TableRow(listOf(dayLabel(day.date), "No data"), note = true)
                else TableRow(listOf(dayLabel(day.date), pct(day.low), pct(day.inRange), pct(day.tight), pct(day.high)),
                              flag = if (day.coverage < MIN_COVERAGE) "Only ${day.coverage}% of readings" else null)
            }
            if (completed.days.size > COLLAPSED_ROWS)
                GhostButton(if (all) "Show fewer days" else "Show all ${completed.days.size} days", { all = !all })
        }
    }
}

@Composable
private fun PentagonCard(state: StatsUiState) {
    val colors = AapsTheme.colors
    val pentagon = state.pentagon ?: return
    val measurer = rememberTextMeasurer()
    val labelStyle = AapsTheme.type.caption.copy(fontSize = 10.sp, color = colors.textSecondary, textAlign = TextAlign.Center)
    val labels = listOf(
        "Out of range\n${pentagon.outOfRange.roundToInt()}%",
        "Variability\n${pentagon.cv.roundToInt()}%",
        "Low\n${pentagon.hypo.roundToInt()}%",
        "High\n${pentagon.hyper.roundToInt()}%",
        "Average\n${fmt(if (state.decimals == 0) pentagon.mean else pentagon.mean / Constants.MMOLL_TO_MGDL, state.decimals)} ${state.avgGlucoseUnit}"
    )
    AapsCard(Modifier.fillMaxWidth().padding(top = AapsSpacing.sectionGap)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Glucose pentagon")
            Text("Five measures in one shape. The smaller your shape, the better. The green shape is typical for someone " +
                     "without diabetes. Low and high use ${glucoseRange(Constants.STATS_RANGE_LOW_MMOL, Constants.STATS_RANGE_HIGH_MMOL, state)}.",
                 style = AapsTheme.type.caption, color = colors.textSecondary)
            val description = "Glucose pentagon: " + labels.joinToString(", ") { it.replace('\n', ' ') } +
                ". Your shape is ${fmt1(pentagon.ratio)} times the size of the green one."
            Canvas(Modifier.fillMaxWidth().aspectRatio(1.3f).semantics { contentDescription = description }) {
                val radius = minOf(size.width, size.height) / 2f * 0.74f
                // The labels reach 1.2 radii above the centre but only 0.97 below it; centre them as a whole.
                val centre = Offset(size.width / 2f, size.height / 2f + radius * 0.115f)
                fun point(axis: Int, r: Double): Offset {
                    val angle = (-90.0 + axis * 72.0) * PI / 180.0
                    return Offset(centre.x + (radius * r * cos(angle)).toFloat(), centre.y + (radius * r * sin(angle)).toFloat())
                }
                fun shape(radii: List<Double>) = Path().apply {
                    radii.forEachIndexed { i, r -> point(i, r).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                    close()
                }
                for (level in 1..4) drawPath(shape(List(5) { level / 4.0 }), colors.hairline, style = Stroke(1f))
                for (i in 0 until 5) drawLine(colors.hairline, centre, point(i, 1.0), 1f)
                drawPath(shape(pentagon.reference), colors.inRange.copy(alpha = 0.2f))
                drawPath(shape(pentagon.reference), colors.inRange, style = Stroke(2f))
                drawPath(shape(pentagon.radii), colors.accent.copy(alpha = 0.25f))
                drawPath(shape(pentagon.radii), colors.accent, style = Stroke(2.5f))
                labels.forEachIndexed { i, label ->
                    val text = measurer.measure(label, labelStyle)
                    // Side labels start just outside their corner so a long value never runs into the shape.
                    // At large font sizes they are kept inside the card instead.
                    val x = when (i) {
                        1    -> point(i, 1.08).x
                        4    -> point(i, 1.08).x - text.size.width
                        else -> point(i, 1.2).x - text.size.width / 2f
                    }.coerceIn(0f, (size.width - text.size.width).coerceAtLeast(0f))
                    drawText(text, topLeft = Offset(x, point(i, 1.2).y - text.size.height / 2f))
                }
            }
            ChartLegend("Without diabetes" to colors.inRange, "You" to colors.accent)
            ValueRow("Size of your shape", "${fmt1(pentagon.ratio)}× the green one")
        }
    }
}

/** Each completed day of the range, today so far and the average, with the confirmed deletion of saved totals. */
@Composable
internal fun DailyInsulin(state: StatsUiState, recalculation: Recalculation, onRecalculate: () -> Unit, onRetry: () -> Unit) {
    val colors = AapsTheme.colors
    var all by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    AapsCard(Modifier.fillMaxWidth().padding(top = AapsSpacing.sectionGap)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Daily insulin and carbs")
            when {
                state.insulinLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = colors.accent)
                    Text("Loading…", style = AapsTheme.type.caption, color = colors.textSecondary)
                }
                state.insulinFailed -> {
                    Text("Could not load insulin history", style = AapsTheme.type.body, color = colors.textSecondary)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                else -> {
                    TableRow(listOf("Day", "Basal U", "Bolus U", "Total U", "Carbs g"), header = true)
                    state.insulinToday?.let { InsulinRow("Today so far", it, strong = true) }
                        ?: TableRow(listOf("Today so far", "No data yet"), note = true)
                    val rows = if (all) state.insulinRows else state.insulinRows.take(COLLAPSED_ROWS)
                    rows.forEach { day ->
                        if (day.recorded) InsulinRow(dayLabel(day.date), day)
                        else TableRow(listOf(dayLabel(day.date), "No data"), note = true)
                    }
                    if (state.insulinRows.size > COLLAPSED_ROWS)
                        GhostButton(if (all) "Show fewer days" else "Show all ${state.insulinRows.size} days", { all = !all })
                    state.insulinAverage?.let { InsulinRow("Average", it, strong = true) }
                }
            }
            when (recalculation) {
                Recalculation.RUNNING -> Text("Deleting saved totals…", style = AapsTheme.type.caption, color = colors.textSecondary)
                Recalculation.DONE -> Text("Saved totals deleted. The loop will rebuild them from your history.",
                                           style = AapsTheme.type.caption, color = colors.textSecondary)
                Recalculation.FAILED -> Text("Could not delete saved totals. Try again.", style = AapsTheme.type.caption, color = colors.high)
                Recalculation.IDLE -> Unit
            }
            GhostButton("Rebuild saved totals", { confirm = true }, enabled = recalculation != Recalculation.RUNNING)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        containerColor = colors.surface2,
        title = { Text("Rebuild saved totals?", color = colors.textPrimary) },
        text = {
            Text("The loop saves a total for each day and uses it in its calculations. This deletes those totals, and the loop " +
                     "works them out again from your treatment history. Your treatments are not changed.", color = colors.textSecondary)
        },
        confirmButton = { TextButton(onClick = { confirm = false; onRecalculate() }) { Text("Rebuild", color = colors.accent) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = colors.textSecondary) } }
    )
}

@Composable
private fun InsulinRow(label: String, day: InsulinDay, strong: Boolean = false) =
    TableRow(listOf(label, fmt1(day.basal), fmt1(day.bolus), fmt1(day.total), String.format(Locale.getDefault(), "%.0f", day.carbs)), strong = strong)

/** Totals of up to six 28-day cycles, loaded only when asked for because it reads half a year of history. */
@Composable
internal fun CyclePatternCard(cycle: CycleUiState, onShow: () -> Unit, onOffset: (Int) -> Unit) {
    val colors = AapsTheme.colors
    AapsCard(Modifier.fillMaxWidth().padding(top = AapsSpacing.sectionGap)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("28-day insulin pattern")
            Text("Compare your daily insulin across 28-day cycles, for example a menstrual cycle.",
                 style = AapsTheme.type.caption, color = colors.textSecondary)
            when {
                !cycle.requested -> TonalButton("Show pattern", onShow, Modifier.fillMaxWidth())
                cycle.failed -> {
                    Text("Could not load insulin history", style = AapsTheme.type.body, color = colors.textSecondary)
                    TextButton(onClick = onShow) { Text("Retry") }
                }
                cycle.pattern == null && cycle.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = colors.accent)
                    Text("Loading… ${cycle.loadedDays} of $CYCLE_HISTORY_DAYS days", style = AapsTheme.type.caption, color = colors.textSecondary)
                }
                else -> {
                    val pattern = cycle.pattern
                    if (pattern == null) Text(
                        "Not enough data. This needs at least two 28-day cycles with 21 days of insulin data each. " +
                            "There are ${cycle.availableDays} days of data.",
                        style = AapsTheme.type.body, color = colors.textSecondary)
                    else {
                        Text("${pattern.cycles.size} cycles" + if (cycle.loading) ", updating…" else "",
                             style = AapsTheme.type.caption, color = colors.textTertiary)
                        CycleChart(pattern)
                        ChartLegend("Each cycle, newest darkest" to colors.accent, "Average" to colors.textPrimary)
                    }
                    // Kept without a pattern too, so a shift that leaves too few totals can be undone.
                    cycle.firstDay?.let { firstDay ->
                        StepperRow(
                            decreaseLabel = "Day 1 one day earlier", onDecrease = { onOffset(cycle.offset + 1) },
                            increaseLabel = "Day 1 one day later", onIncrease = { onOffset(cycle.offset - 1) },
                            decreaseEnabled = cycle.offset < CYCLE_DAYS - 1, increaseEnabled = cycle.offset > 0
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Day 1: ${dayLabel(firstDay)}", style = AapsTheme.type.listTitle, color = colors.textPrimary)
                                Text(when (cycle.offset) {
                                         0 -> "Move to match your cycle"
                                         CYCLE_DAYS - 1 -> "Earliest possible"
                                         else -> "Moved ${cycle.offset} ${if (cycle.offset == 1) "day" else "days"} earlier"
                                     }, style = AapsTheme.type.caption, color = colors.textTertiary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CycleChart(pattern: CyclePattern) {
    val colors = AapsTheme.colors
    val measurer = rememberTextMeasurer()
    val axis = AapsTheme.type.caption.copy(fontSize = 9.sp, color = colors.textTertiary)
    val values = pattern.cycles.flatten().filterNotNull()
    val top = (values.maxOrNull() ?: 1.0) * 1.1
    val bottom = (values.minOrNull() ?: 0.0) * 0.9
    val description = cycleDescription(pattern)
    Text("U/day", style = AapsTheme.type.caption, color = colors.textSecondary)
    Canvas(Modifier.fillMaxWidth().height(170.dp).semantics { contentDescription = description }) {
        val left = 30.dp.toPx()
        val h = size.height - 14.dp.toPx()
        val w = size.width - left - 6.dp.toPx()
        if (w <= 0f || h <= 0f || top <= bottom) return@Canvas
        fun x(day: Int) = left + day / (CYCLE_DAYS - 1f) * w
        fun y(v: Double) = ((top - v) / (top - bottom)).toFloat() * h
        fun line(series: List<Double?>, color: Color, width: Float) {
            for (day in 1 until series.size) {
                val a = series[day - 1] ?: continue
                val b = series[day] ?: continue
                drawLine(color, Offset(x(day - 1), y(a)), Offset(x(day), y(b)), width, cap = StrokeCap.Round)
            }
        }
        pattern.cycles.forEachIndexed { i, series ->
            line(series, colors.accent.copy(alpha = (0.6f - i * 0.4f / pattern.cycles.size).coerceAtLeast(0.15f)), 1.dp.toPx())
        }
        line(pattern.average, colors.textPrimary, 2.5.dp.toPx())
        listOf(top, bottom).forEach { v ->
            val text = measurer.measure(fmt1(v), axis)
            drawText(text, topLeft = Offset(left - 4.dp.toPx() - text.size.width, (y(v) - text.size.height / 2f).coerceIn(0f, h - text.size.height)))
        }
        listOf(1, 7, 14, 21, 28).forEach { day ->
            val text = measurer.measure("$day", axis)
            drawText(text, topLeft = Offset(x(day - 1) - text.size.width / 2f, h + 2.dp.toPx()))
        }
    }
}

/**
 * Five-column table row; the first column is wider for the day. A [note] spans the value columns, and a
 * [flag] under the day marks a row that has too little data to trust.
 */
@Composable
private fun TableRow(
    cells: List<String>, header: Boolean = false, strong: Boolean = false, note: Boolean = false,
    flag: String? = null, firstWide: Boolean = true
) {
    val colors = AapsTheme.colors
    val style = if (header) AapsTheme.type.label.copy(fontSize = 10.sp) else AapsTheme.type.caption
    val color = when {
        header -> colors.textSecondary
        note -> colors.textTertiary
        else -> colors.textPrimary
    }
    val weight = if (strong) FontWeight.SemiBold else null
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { i, cell ->
            val span = if (note && i == 1) 4f else 1f
            val modifier = Modifier.weight(if (i == 0 && firstWide) 1.6f else span)
            if (i == 0 && flag != null) Column(modifier) {
                Text(cell, style = style, color = color, fontWeight = weight)
                Text(flag, style = AapsTheme.type.caption.copy(fontSize = 10.sp), color = colors.high)
            } else Text(cell, style = style, color = color, fontWeight = weight, modifier = modifier,
                        textAlign = if (i == 0 && firstWide) TextAlign.Start else TextAlign.End)
        }
    }
}

@Composable
internal fun ValueRow(label: String, value: String) {
    val colors = AapsTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AapsTheme.type.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = AapsTheme.type.listTitle, color = colors.textPrimary)
    }
}

/** Chart keys; they wrap onto more lines at large font sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartLegend(vararg items: Pair<String, Color>, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 12.dp, height = 4.dp).clip(AapsTheme.shape.pill).background(color))
                Text(label, style = AapsTheme.type.caption, color = AapsTheme.colors.textTertiary, modifier = Modifier.padding(start = 5.dp))
            }
        }
    }
}

/** What the chart shows, for TalkBack: the average of each cycle day, and the days no cycle has a total for. */
internal fun cycleDescription(pattern: CyclePattern): String =
    "Average daily insulin by cycle day, ${pattern.cycles.size} cycles: " +
        pattern.average.mapIndexed { i, v -> "day ${i + 1} " + (v?.let { "${fmt1(it)} U" } ?: "no data") }.joinToString(", ")

private fun dayLabel(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM")))

private fun pct(value: Double) = "${value.roundToInt()}%"

private fun fmt1(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

/** A fixed report threshold, given in mmol/L, in the user's units. */
private fun limit(mmol: Double, state: StatsUiState) = fmt(if (state.decimals == 0) mmol * Constants.MMOLL_TO_MGDL else mmol, state.decimals)

/** The fixed 3.9–7.8 mmol/L tight range, in the user's units. */
internal fun tightRange(state: StatsUiState) = glucoseRange(Constants.STATS_TARGET_LOW_MMOL, Constants.STATS_TARGET_HIGH_MMOL, state)

private fun glucoseRange(lowMmol: Double, highMmol: Double, state: StatsUiState) =
    "${limit(lowMmol, state)}–${limit(highMmol, state)} ${state.avgGlucoseUnit}"
