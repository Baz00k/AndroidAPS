package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AmountStepper
import app.aaps.core.compose.components.Choice
import app.aaps.core.compose.components.ChoiceRow
import app.aaps.core.compose.components.DangerButton
import app.aaps.core.compose.components.EntryCard
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SheetSurface

/** [onStart] gets only a validated request; the host confirms it before anything is recorded. */
@Composable
fun TempTargetSheet(
    state: TempTargetSheetState,
    onStart: (TempTargetRequest) -> Unit,
    onCancelActive: () -> Unit,
    onClose: () -> Unit
) {
    var target by rememberSaveable { mutableStateOf(state.initial.target) }
    var duration by rememberSaveable { mutableStateOf(state.initial.durationMin.toDouble()) }
    var reason by rememberSaveable { mutableStateOf(state.initial.reason) }
    var targetValid by remember { mutableStateOf(false) }
    var durationValid by remember { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }

    val request = TempTargetRequest(target, duration.toInt(), reason, state.units)

    // Read state when invoked: a same-frame second tap must still see the latch.
    fun canStart() = targetValid && durationValid && request.isValid && !submitted

    fun start() {
        if (!canStart()) return
        submitted = true
        onStart(request)
    }

    fun cancelActive() {
        if (submitted) return
        submitted = true
        onCancelActive()
    }

    SheetSurface(
        title = "Temporary target",
        onClose = onClose,
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.hasActive) DangerButton("Cancel temporary target", ::cancelActive, enabled = !submitted)
                PrimaryButton("Start temporary target", ::start, enabled = canStart())
            }
        }
    ) {
        ChoiceRow {
            state.presets.forEach { preset ->
                Choice(preset.reason.label, selected = reason == preset.reason, modifier = Modifier.weight(1f), onClick = {
                    target = preset.target
                    duration = preset.durationMin.toDouble()
                    reason = preset.reason
                })
            }
            Choice(TtReason.CUSTOM.label, selected = reason == TtReason.CUSTOM, modifier = Modifier.weight(1f), onClick = { reason = TtReason.CUSTOM })
        }
        EntryCard("Target") {
            AmountStepper(
                value = target, onValue = { if (it != target) reason = TtReason.CUSTOM; target = it },
                step = state.targetStep, min = state.targetMin, max = state.targetMax,
                decimals = state.decimals, unit = state.unitLabel, name = "of target",
                onValidityChange = { targetValid = it }
            )
        }
        EntryCard("Duration") {
            AmountStepper(
                value = duration, onValue = { duration = it },
                step = TempTargetLimits.DURATION_STEP.toDouble(), min = TempTargetLimits.MIN_DURATION.toDouble(), max = TempTargetLimits.MAX_DURATION.toDouble(),
                decimals = 0, unit = "min", name = "of duration", integerOnly = true,
                onValidityChange = { durationValid = it }
            )
        }
    }
}
