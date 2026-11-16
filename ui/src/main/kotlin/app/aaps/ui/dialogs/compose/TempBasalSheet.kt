package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.compose.components.AmountStepper
import app.aaps.core.compose.components.EntryCard
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.components.SheetSurface

/**
 * The pump decides which modes are allowed (percent / absolute); the mode switch is shown only when
 * both are. Each mode keeps its own value, limits and step.
 */
data class TempBasalSheetState(
    val percentAllowed: Boolean,
    val absoluteAllowed: Boolean,
    val defaultIsPercent: Boolean,
    val percentDefault: Double,
    val percentMax: Double,
    val percentStep: Double,
    val absoluteDefault: Double,
    val absoluteMax: Double,
    val absoluteStep: Double,
    val absoluteDecimals: Int,
    val durationDefault: Double,
    val durationMax: Double,
    val durationStep: Double
)

data class TempBasalInputs(
    val isPercent: Boolean,
    val value: Double,
    val durationMin: Int
)

@Composable
fun TempBasalSheet(state: TempBasalSheetState, onSubmit: (TempBasalInputs) -> Unit, onClose: () -> Unit) {
    var isPercent by rememberSaveable { mutableStateOf(state.defaultIsPercent) }
    var percent by rememberSaveable { mutableStateOf(state.percentDefault) }
    var absolute by rememberSaveable { mutableStateOf(state.absoluteDefault) }
    var duration by rememberSaveable { mutableStateOf(state.durationDefault) }
    // Per mode, so a switch never enables submission on the other mode's validity.
    var percentValid by remember { mutableStateOf(false) }
    var absoluteValid by remember { mutableStateOf(false) }
    var durationValid by remember { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }

    // Read state when invoked: a same-frame second tap must still see the latch.
    fun canSubmit() = (if (isPercent) percentValid else absoluteValid) && durationValid && !submitted

    fun submit() {
        if (!canSubmit()) return
        submitted = true
        onSubmit(TempBasalInputs(isPercent, if (isPercent) percent else absolute, duration.toInt()))
    }

    SheetSurface(
        title = "Temp basal",
        onClose = onClose,
        footer = { PrimaryButton("Set temp basal", onClick = ::submit, enabled = canSubmit()) }
    ) {
        if (state.percentAllowed && state.absoluteAllowed)
            SegmentedControl(
                options = listOf("Percent", "Absolute"),
                selectedIndex = if (isPercent) 0 else 1,
                onSelect = { isPercent = it == 0 },
                modifier = Modifier.fillMaxWidth(),
                fillWidth = true
            )
        EntryCard("Rate") {
            if (isPercent)
                AmountStepper(
                    value = percent, onValue = { percent = it },
                    step = state.percentStep, min = 0.0, max = state.percentMax,
                    decimals = 0, unit = "%", name = "of basal rate", integerOnly = true,
                    onValidityChange = { percentValid = it }
                )
            else
                AmountStepper(
                    value = absolute, onValue = { absolute = it },
                    step = state.absoluteStep, min = 0.0, max = state.absoluteMax,
                    decimals = state.absoluteDecimals, unit = "U/h", name = "of basal rate",
                    onValidityChange = { absoluteValid = it }
                )
        }
        EntryCard("Duration") {
            AmountStepper(
                value = duration, onValue = { duration = it },
                step = state.durationStep, min = state.durationStep, max = state.durationMax,
                decimals = 0, unit = "min", name = "of duration", integerOnly = true,
                onValidityChange = { durationValid = it }
            )
        }
    }
}
