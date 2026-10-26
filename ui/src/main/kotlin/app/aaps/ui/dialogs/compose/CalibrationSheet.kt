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

data class CalibrationSheetState(
    val initial: Double,
    val min: Double,
    val max: Double,
    val step: Double,
    val decimals: Int,
    val unitLabel: String
)

/** A missing or out-of-range reading stays invalid; never substitute a glucose value. */
@Composable
fun CalibrationSheet(state: CalibrationSheetState, onSend: (Double) -> Unit, onClose: () -> Unit) {
    var bg by rememberSaveable { mutableStateOf(state.initial) }
    var valid by remember { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }

    fun canSubmit() = valid && bg > 0.0 && !submitted

    fun send() {
        if (!canSubmit()) return
        submitted = true
        onSend(bg)
    }

    SheetSurface(
        title = "Calibration",
        onClose = onClose,
        footer = { PrimaryButton("Send calibration", onClick = ::send, enabled = canSubmit()) }
    ) {
        EntryCard("Blood glucose") {
            AmountStepper(
                value = bg, onValue = { bg = it },
                step = state.step, min = state.min, max = state.max,
                decimals = state.decimals, unit = state.unitLabel, name = "of blood glucose",
                onValidityChange = { valid = it }
            )
        }
    }
}
