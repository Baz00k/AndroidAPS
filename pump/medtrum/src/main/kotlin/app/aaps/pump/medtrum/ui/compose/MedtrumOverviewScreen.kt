package app.aaps.pump.medtrum.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.StatusPill
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsTone
import app.aaps.core.compose.theme.color

/** A label/value line. [warn] marks a value that needs attention, e.g. an active alarm. */
@Immutable
data class MedtrumRow(val label: String, val value: String, val warn: Boolean = false)

@Immutable
data class MedtrumOverviewState(
    /** The pump's own name (PumpType.description) — "Medtrum Nano", not a generic category. */
    val title: String = "",
    /**
     * Tone of the pump state, decided by the caller. NOT "is the enum >= ACTIVE": OCCLUSION,
     * PATCH_FAULT and STOPPED all sort above ACTIVE, and painting a fault green is the one thing
     * this pill must never do.
     */
    val tone: AapsTone = AapsTone.Neutral,
    val bleStatus: String = "",
    val pumpState: String = "",
    val lastConnection: String = "",
    val reservoir: Double = 0.0,
    /** Real capacity from PumpType.maxReservoirReading() — 200 U or 300 U depending on model. */
    val reservoirMax: Double = 200.0,
    val reservoirText: String = "",
    val batteryText: String = "",
    val activeAlarms: String = "",
    val status: List<MedtrumRow> = emptyList(),
    val patch: List<MedtrumRow> = emptyList(),
    val canRefresh: Boolean = false,
    val canResetAlarms: Boolean = false,
    val changePatchLabel: String = "",
    val refreshLabel: String = "",
    val resetAlarmsLabel: String = ""
)

/**
 * Medtrum patch pump status.
 *
 * Replaces a 745-line data-binding layout. Deliberately the same shape as the YpsoPump status screen —
 * connection pill, reservoir gauge, status rows, actions — because a pump tab should not look like a
 * different application depending on which driver is loaded.
 *
 * Reservoir gets a gauge because it has a known full scale. Battery does NOT: the pump reports a
 * VOLTAGE, and inventing a percentage from it would be a guess presented as a measurement.
 */
@Composable
fun MedtrumOverviewScreen(
    state: MedtrumOverviewState,
    onRefresh: () -> Unit,
    onResetAlarms: () -> Unit,
    onChangePatch: () -> Unit
) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.title.ifBlank { "Patch pump" }, style = AapsTheme.type.title, color = colors.textPrimary)
                if (state.lastConnection.isNotBlank())
                    Text(state.lastConnection, style = AapsTheme.type.caption, color = colors.textTertiary)
            }
            StatusPill(
                label = state.pumpState.ifBlank { state.bleStatus.ifBlank { "Unknown" } },
                dotColor = state.tone.color()
            )
        }

        // An active alarm is the reason you opened this screen; it goes above everything else.
        if (state.activeAlarms.isNotBlank())
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(end = 10.dp).size(width = 3.dp, height = 30.dp).clip(AapsTheme.shape.pill).background(colors.low))
                    Column(Modifier.weight(1f)) {
                        Text("ALARM", style = AapsTheme.type.label, color = colors.low)
                        Text(state.activeAlarms, style = AapsTheme.type.body, color = colors.textPrimary)
                    }
                }
            }

        Row(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
            GaugeTile("RESERVOIR", state.reservoirText, (state.reservoir / state.reservoirMax.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f),
                      if (state.reservoir < 20) colors.high else colors.inRange, Modifier.weight(1f))
            ValueTile("BATTERY", state.batteryText, Modifier.weight(1f))
        }

        RowsCard(state.status)
        if (state.patch.isNotEmpty()) {
            Text("PATCH", style = AapsTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            RowsCard(state.patch)
        }

        PrimaryButton(
            label = state.changePatchLabel,
            onClick = onChangePatch,
            modifier = Modifier.fillMaxWidth().padding(top = AapsSpacing.rowGapSmall)
        )
        Row(Modifier.fillMaxWidth().padding(top = AapsSpacing.rowGap, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
            SecondaryButton(state.refreshLabel, state.canRefresh, onRefresh, Modifier.weight(1f))
            if (state.canResetAlarms) SecondaryButton(state.resetAlarmsLabel, true, onResetAlarms, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RowsCard(rows: List<MedtrumRow>) {
    if (rows.isEmpty()) return
    val colors = AapsTheme.colors
    AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
        Column {
            rows.forEachIndexed { i, r ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.label, style = AapsTheme.type.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    Text(
                        r.value.ifBlank { "—" },
                        style = AapsTheme.type.listTitle,
                        color = if (r.warn) colors.low else colors.textPrimary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GaugeTile(label: String, value: String, fraction: Float, tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val colors = AapsTheme.colors
    AapsCard(modifier) {
        Column {
            Text(label, style = AapsTheme.type.label, color = colors.textSecondary)
            Text(value, style = AapsTheme.type.cardValue, color = colors.textPrimary, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(AapsTheme.shape.pill).background(colors.controlFill)) {
                Box(Modifier.fillMaxWidth(fraction).height(5.dp).clip(AapsTheme.shape.pill).background(tint))
            }
        }
    }
}

@Composable
private fun ValueTile(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = AapsTheme.colors
    AapsCard(modifier) {
        Column {
            Text(label, style = AapsTheme.type.label, color = colors.textSecondary)
            Text(value.ifBlank { "—" }, style = AapsTheme.type.cardValue, color = colors.textPrimary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun SecondaryButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AapsTheme.colors
    Text(
        label,
        style = AapsTheme.type.label,
        color = if (enabled) colors.textOnSurfaceStrong else colors.textTertiary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(AapsTheme.shape.button)
            .background(colors.controlFill)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 13.dp)
    )
}
