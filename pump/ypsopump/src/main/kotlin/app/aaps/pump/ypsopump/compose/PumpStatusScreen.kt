package app.aaps.pump.ypsopump.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.StatusPill
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * Pump status screen: connection pill with at most one instruction, Reservoir + Battery gauge
 * tiles, confirmed status rows, and the command queue while it is busy. Read-only view.
 */
@Composable
fun PumpStatusScreen(state: PumpStatusState) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.title, style = AapsTheme.type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
            StatusPill(
                label = state.connectionSummary,
                dotColor = if (state.connectionHealthy) colors.inRange else colors.low
            )
        }

        state.connectionAction?.let { action ->
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Text(action, style = AapsTheme.type.body, color = colors.textPrimary)
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
            GaugeTile(
                "RESERVOIR", state.reservoir?.let { String.format(java.util.Locale.getDefault(), "%.0f U", it) } ?: state.unavailableLabel,
                state.reservoir?.let { (it / state.reservoirMax).toFloat() },
                if (state.reservoir != null && state.reservoir < 20) colors.high else colors.inRange, Modifier.weight(1f)
            )
            GaugeTile(
                "BATTERY", state.battery?.let { "$it%" } ?: state.unavailableLabel, state.battery?.div(100f),
                if (state.battery != null && state.battery < 25) colors.low else colors.inRange, Modifier.weight(1f)
            )
        }

        if (state.rows.isNotEmpty()) {
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Column {
                    state.rows.forEachIndexed { i, r ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label, style = AapsTheme.type.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
                            Text(r.value, style = AapsTheme.type.listTitle, color = colors.textPrimary)
                        }
                    }
                }
            }
        }

        if (state.queue.isNotEmpty()) {
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Column {
                    state.queue.forEachIndexed { i, q ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (q.running) colors.inRange else colors.textTertiary))
                            Text(q.text, style = AapsTheme.type.body, color = colors.textOnSurfaceStrong, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        }
                    }
                }
            }
        }

        if (state.note.isNotBlank()) Text(state.note, style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(bottom = 24.dp))
    }
}


@Composable
private fun GaugeTile(label: String, value: String, fraction: Float?, color: Color, modifier: Modifier) {
    val colors = AapsTheme.colors
    AapsCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = AapsTheme.type.label.copy(fontSize = 9.sp), color = colors.textSecondary)
            Text(value, style = AapsTheme.type.cardValue, color = colors.textPrimary)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.controlFill)) {
                if (fraction != null) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
                }
            }
        }
    }
}
