package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.compose.components.AmountStepper
import app.aaps.core.compose.components.Choice
import app.aaps.core.compose.components.ChoiceRow
import app.aaps.core.compose.components.DangerButton
import app.aaps.core.compose.components.EntryCard
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.formatNumeric
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * Redesigned Temp target sheet (handoff Section 3): "pick an intent" preset choices + Target/Duration
 * entries. [onStart] runs the SAME confirmation + persistence path as the legacy dialog.
 */
@Composable
fun TempTargetSheet(
    state: TempTargetSheetState,
    onStart: (TempTargetRequest) -> Unit,
    onCancelActive: () -> Unit,
    onClose: () -> Unit
) {
    val colors = AapsTheme.colors
    var target by rememberSaveable { mutableStateOf(state.initialTarget) }
    var duration by rememberSaveable { mutableIntStateOf(state.initialDuration) }
    var reason by rememberSaveable { mutableStateOf(TtReason.CUSTOM) }
    var targetValid by remember { mutableStateOf(false) }
    var durationValid by remember { mutableStateOf(false) }

    val request = state.request(target, duration, reason)
    val canStart = targetValid && durationValid && request != null
    val selectedPreset = state.presets.firstOrNull { it.reason == reason }

    SheetSurface(
        title = "Temporary target",
        onClose = onClose,
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall)) {
                PrimaryButton(
                    label = "Start temporary target",
                    enabled = canStart,
                    onClick = {
                        if (targetValid && durationValid) state.request(target, duration, reason)?.let(onStart)
                    }
                )
                // Independent of the editor: an active target outside today's range can still be stopped.
                if (state.hasActive)
                    DangerButton(label = "Cancel current temporary target", onClick = onCancelActive)
            }
        }
    ) {
        EntryCard("Intent") {
            ChoiceRow {
                state.presets.forEach { preset ->
                    Choice(
                        label = preset.label,
                        selected = reason == preset.reason,
                        onClick = {
                            target = preset.target
                            duration = preset.durationMin
                            reason = preset.reason
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Choice(
                    label = "Manual",
                    selected = reason == TtReason.CUSTOM,
                    onClick = { reason = TtReason.CUSTOM },
                    modifier = Modifier.weight(1f)
                )
            }
            selectedPreset?.let { preset ->
                Text(
                    "${formatNumeric(preset.target, state.decimals)} ${state.unitLabel} · ${preset.durationMin} min",
                    style = AapsTheme.type.caption,
                    color = colors.textSecondary
                )
            }
        }
        EntryCard("Target") {
            AmountStepper(
                value = target,
                onValue = {
                    target = it
                    reason = TtReason.CUSTOM
                },
                step = state.unitStep,
                min = state.targetMin,
                max = state.targetMax,
                decimals = state.decimals,
                unit = state.unitLabel,
                name = "of target",
                onValidityChange = { targetValid = it }
            )
        }
        EntryCard("Duration") {
            AmountStepper(
                value = duration.toDouble(),
                onValue = {
                    duration = it.toInt()
                    reason = TtReason.CUSTOM
                },
                step = state.durationStep.toDouble(),
                min = 0.0,
                max = state.durationMax.toDouble(),
                decimals = 0,
                unit = "min",
                name = "of duration",
                integerOnly = true,
                onValidityChange = { durationValid = it }
            )
        }
    }
}
