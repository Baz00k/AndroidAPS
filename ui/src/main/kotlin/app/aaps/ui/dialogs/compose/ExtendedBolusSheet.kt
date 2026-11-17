package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.aaps.core.compose.components.AmountStepper
import app.aaps.core.compose.components.EntryCard
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SheetSurface

data class ExtendedBolusSheetState(
    val maxInsulin: Double,
    val insulinStep: Double,
    val insulinDecimals: Int,
    val durationStep: Double,
    val maxDuration: Double
)

data class ExtendedBolusInputs(
    val insulin: Double,
    val durationMin: Int
)

@Composable
fun ExtendedBolusSheet(state: ExtendedBolusSheetState, onSubmit: (ExtendedBolusInputs) -> Unit, onClose: () -> Unit) {
    var insulin by rememberSaveable { mutableStateOf(state.insulinStep) }
    var duration by rememberSaveable { mutableStateOf(state.durationStep) }
    var insulinValid by remember { mutableStateOf(false) }
    var durationValid by remember { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }

    // Read state when invoked: a same-frame second tap must still see the latch.
    fun canSubmit() = insulinValid && durationValid && insulin > 0.0 && duration > 0.0 && !submitted

    fun submit() {
        if (!canSubmit()) return
        submitted = true
        onSubmit(ExtendedBolusInputs(insulin = insulin, durationMin = duration.toInt()))
    }

    SheetSurface(
        title = "Extended bolus",
        onClose = onClose,
        footer = { PrimaryButton("Set extended bolus", onClick = ::submit, enabled = canSubmit()) }
    ) {
        EntryCard("Insulin") {
            AmountStepper(
                value = insulin, onValue = { insulin = it },
                step = state.insulinStep, min = 0.0, max = state.maxInsulin,
                decimals = state.insulinDecimals, unit = "U", name = "of insulin",
                onValidityChange = { insulinValid = it }
            )
        }
        EntryCard("Duration") {
            AmountStepper(
                value = duration, onValue = { duration = it },
                step = state.durationStep, min = 0.0, max = state.maxDuration,
                decimals = 0, unit = "min", name = "of duration", integerOnly = true,
                onValidityChange = { durationValid = it }
            )
        }
    }
}
