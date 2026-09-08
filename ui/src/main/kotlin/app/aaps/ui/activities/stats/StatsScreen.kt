package app.aaps.ui.activities.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import kotlin.math.roundToInt

/**
 * Redesigned Statistics screen (handoff Section 4): range selector, a TIR card (big in-range % + a
 * 5-band stacked bar), and 2×2 stat tiles. Read-only; [onRange] recomputes for the chosen window.
 */
@Composable
fun StatsScreen(state: StatsUiState, onRange: (Int) -> Unit, onBack: () -> Unit) {
    val colors = AapsTheme.colors
    val ranges = listOf(7, 30, 90)
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AapsSpacing.screenH)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onBack).padding(8.dp)
            ) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = colors.textSecondary) }
            Text("Statistics", style = AapsTheme.type.title, color = colors.textPrimary, modifier = Modifier.weight(1f).padding(start = 4.dp))
            SegmentedControl(ranges.map { "${it}d" }, ranges.indexOf(state.rangeDays).coerceAtLeast(0), { onRange(ranges[it]) })
        }

        // TIR card
        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("TIME IN RANGE", style = AapsTheme.type.label, color = colors.textSecondary)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (state.loading) "--" else "${state.inRange.roundToInt()}%", style = AapsTheme.type.hero.copy(fontSize = 56.sp, lineHeight = 56.sp), color = colors.inRange)
                    Text("in range", style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                }
                // stacked bar
                Row(
                    Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(colors.controlFill)
                ) {
                    Seg(state.veryLow, colors.veryLow)
                    Seg(state.low, colors.low)
                    Seg(state.inRange, colors.inRange)
                    Seg(state.high, colors.high)
                    Seg(state.veryHigh, colors.veryHigh)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BarLabel("Low", state.veryLow + state.low, colors.low, colors.textTertiary)
                    BarLabel("In range", state.inRange, colors.inRange, colors.textTertiary)
                    BarLabel("High", state.high + state.veryHigh, colors.high, colors.textTertiary)
                }
            }
        }

        // 2×2 tiles
        Row(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.rowGap), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
            StatTile("GMI / eA1c", state.gmi, Modifier.weight(1f))
            StatTile("AVG GLUCOSE", if (state.avgGlucose == "--") "--" else "${state.avgGlucose} ${state.avgGlucoseUnit}", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
            StatTile("CV", state.cv, Modifier.weight(1f), valueColor = if (state.cvGood) colors.inRange else colors.textPrimary)
            StatTile("AVG TDD", state.avgTdd, Modifier.weight(1f))
        }

        if (!state.loading) {
            PatternChart("BY HOUR OF DAY", state.byHour, labelEvery = 6)
            PatternChart("BY DAY OF WEEK", state.byWeekday, labelEvery = 1)
        }

        // extra
        AapsCard(Modifier.fillMaxWidth().padding(top = AapsSpacing.sectionGap, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Carbs / day", style = AapsTheme.type.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
                Text(state.carbsPerDay, style = AapsTheme.type.listTitle, color = colors.textPrimary)
            }
        }
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

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = AapsTheme.colors.textPrimary) {
    val colors = AapsTheme.colors
    AapsCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = AapsTheme.type.label.copy(fontSize = 9.sp), color = colors.textSecondary)
            Text(value, style = AapsTheme.type.cardValue, color = valueColor, maxLines = 1)
        }
    }
}

/**
 * A stacked bar per time bucket — the view a clinic summary leads with, because a number that is fine
 * on average can still hide a bad hour every night.
 *
 * Each bar is normalised to its own bucket so the shape is comparable across buckets regardless of how
 * many readings each holds; buckets with too few readings are dimmed rather than dropped, so a gap in
 * the data reads as a gap and not as a good result.
 */
@Composable
private fun PatternChart(title: String, buckets: List<RangeBucket>, labelEvery: Int) {
    val colors = AapsTheme.colors
    if (buckets.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = AapsSpacing.sectionGap)) {
        Text(title, style = AapsTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
        AapsCard(Modifier.fillMaxWidth()) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(112.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    buckets.forEach { b ->
                        val dim = if (b.sparse) 0.28f else 1f
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(AapsTheme.shape.extraSmall),
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            if (b.readings == 0) {
                                Box(Modifier.fillMaxWidth().weight(1f).background(colors.controlFill))
                            } else {
                                // Top-down: worst-high first, so the in-range block sits on the baseline
                                // and the eye can run along its top edge across the day.
                                Seg(b.veryHigh, colors.veryHigh.copy(alpha = dim))
                                Seg(b.high, colors.high.copy(alpha = dim))
                                Seg(b.inRange, colors.inRange.copy(alpha = dim))
                                Seg(b.low, colors.low.copy(alpha = dim))
                                Seg(b.veryLow, colors.veryLow.copy(alpha = dim))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    buckets.forEachIndexed { i, b ->
                        // The slot is one bar wide, which is narrower than a two-digit label, so let
                        // the text overflow its box instead of being clipped to "0".
                        Box(Modifier.weight(1f)) {
                            if (i % labelEvery == 0)
                                Text(
                                    b.label,
                                    style = AapsTheme.type.caption,
                                    color = colors.textTertiary,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.wrapContentWidth(unbounded = true)
                                )
                        }
                    }
                }
            }
        }
    }
}

/** One vertical slice of a stacked bar; zero-height segments are skipped so they cannot show as hairlines. */
@Composable
private fun ColumnScope.Seg(pct: Double, color: Color) {
    if (pct <= 0.0) return
    Box(Modifier.fillMaxWidth().weight(pct.toFloat()).background(color))
}
