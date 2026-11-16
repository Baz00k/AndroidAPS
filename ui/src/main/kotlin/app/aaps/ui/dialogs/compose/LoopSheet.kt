package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.aaps.core.compose.components.ActionChip
import app.aaps.core.compose.components.ChoiceRow
import app.aaps.core.compose.components.Dot
import app.aaps.core.compose.components.EntryCard
import app.aaps.core.compose.components.ListCard
import app.aaps.core.compose.components.ListRow
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.RadioRow
import app.aaps.core.compose.components.SectionLabel
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.color

/**
 * Loop control sheet: current status, the mode list, and suspend / disconnect durations. Every choice
 * calls back through [onAction], which confirms and runs `loop.handleRunningModeChange`.
 */
@Composable
fun LoopSheet(
    state: LoopSheetState,
    onAction: (LoopActionId) -> Unit,
    onClose: () -> Unit
) {
    val footerAction = when {
        state.resumeVisible    -> "Resume loop" to LoopActionId.RESUME
        state.reconnectVisible -> "Reconnect pump" to LoopActionId.RECONNECT
        else                   -> null
    }
    SheetSurface(
        title = "Loop control",
        onClose = onClose,
        scrollContent = true,
        footer = footerAction?.let { (label, id) -> { PrimaryButton(label, onClick = { onAction(id) }, modifier = Modifier.fillMaxWidth()) } }
    ) {
        ListCard(Modifier.fillMaxWidth()) {
            ListRow(
                title = state.statusLabel,
                sub = listOf(state.algoLine, state.reasons).filter { it.isNotBlank() }.joinToString("\n"),
                leading = { Dot(state.statusTone?.color() ?: AapsTheme.colors.inRange, glow = state.looping) }
            )
        }

        if (state.modes.isNotEmpty())
            Column {
                SectionLabel("Mode")
                ListCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.selectableGroup()) {
                        state.modes.forEach { m ->
                            // The current mode stays at full strength but does nothing when pressed again.
                            RadioRow(
                                title = m.title,
                                selected = m.selected,
                                onClick = { if (m.actionable) onAction(m.id) },
                                enabled = m.enabled || m.selected
                            )
                        }
                    }
                }
            }

        if (state.suspendVisible)
            EntryCard("Suspend loop") {
                ChoiceRow {
                    ActionChip("1h", { onAction(LoopActionId.SUSPEND_1H) }, Modifier.weight(1f))
                    ActionChip("2h", { onAction(LoopActionId.SUSPEND_2H) }, Modifier.weight(1f))
                    ActionChip("3h", { onAction(LoopActionId.SUSPEND_3H) }, Modifier.weight(1f))
                    ActionChip("10h", { onAction(LoopActionId.SUSPEND_10H) }, Modifier.weight(1f))
                }
            }

        if (state.disconnectVisible)
            EntryCard("Disconnect pump") {
                ChoiceRow {
                    if (state.disconnect15m) ActionChip("15m", { onAction(LoopActionId.DISCONNECT_15M) }, Modifier.weight(1f))
                    if (state.disconnect30m) ActionChip("30m", { onAction(LoopActionId.DISCONNECT_30M) }, Modifier.weight(1f))
                    ActionChip("1h", { onAction(LoopActionId.DISCONNECT_1H) }, Modifier.weight(1f))
                    ActionChip("2h", { onAction(LoopActionId.DISCONNECT_2H) }, Modifier.weight(1f))
                    ActionChip("3h", { onAction(LoopActionId.DISCONNECT_3H) }, Modifier.weight(1f))
                }
            }
    }
}
