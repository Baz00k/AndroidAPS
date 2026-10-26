package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.compose.components.AmountStepper
import app.aaps.core.compose.components.Choice
import app.aaps.core.compose.components.ChoiceRow
import app.aaps.core.compose.components.EntryCard
import app.aaps.core.compose.components.NotesField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SectionLabel
import app.aaps.core.compose.components.SheetSurface

enum class CareMeter { METER, SENSOR, MANUAL }

data class CareSheetState(
    val title: String,
    val submitLabel: String,
    val showBg: Boolean,
    val bgInitial: Double,
    val bgMin: Double,
    val bgMax: Double,
    val bgStep: Double,
    val bgDecimals: Int,
    val bgUnit: String,
    val showDuration: Boolean,
    val durationMax: Double,
    val durationStep: Double,
    val showNotes: Boolean
)

data class CareInputs(
    val bg: Double,
    val meter: CareMeter,
    val durationMin: Int,
    val notes: String
)

@Composable
fun CareSheet(state: CareSheetState, onSubmit: (CareInputs) -> Unit, onClose: () -> Unit) {
    var bg by rememberSaveable { mutableStateOf(state.bgInitial) }
    var meter by rememberSaveable { mutableStateOf(CareMeter.SENSOR) }
    var duration by rememberSaveable { mutableStateOf(0.0) }
    var notes by rememberSaveable { mutableStateOf("") }
    var bgValid by remember { mutableStateOf(false) }
    var durationValid by remember { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }

    fun canSubmit() = (!state.showBg || bgValid) && (!state.showDuration || durationValid) && !submitted

    fun submit() {
        if (!canSubmit()) return
        submitted = true
        onSubmit(CareInputs(bg = bg, meter = meter, durationMin = duration.toInt(), notes = notes))
    }

    SheetSurface(
        title = state.title,
        onClose = onClose,
        footer = { PrimaryButton(label = state.submitLabel, onClick = ::submit, enabled = canSubmit()) }
    ) {
        if (state.showBg) {
            EntryCard("Glucose") {
                AmountStepper(
                    value = bg, onValue = { bg = it },
                    step = state.bgStep, min = state.bgMin, max = state.bgMax,
                    decimals = state.bgDecimals, unit = state.bgUnit, name = "of glucose",
                    onValidityChange = { bgValid = it }
                )
                SectionLabel("Source")
                ChoiceRow {
                    Choice("Meter", selected = meter == CareMeter.METER, onClick = { meter = CareMeter.METER }, modifier = Modifier.weight(1f))
                    Choice("Sensor", selected = meter == CareMeter.SENSOR, onClick = { meter = CareMeter.SENSOR }, modifier = Modifier.weight(1f))
                    Choice("Other", selected = meter == CareMeter.MANUAL, onClick = { meter = CareMeter.MANUAL }, modifier = Modifier.weight(1f))
                }
            }
        }

        // Zero duration is a note or exercise of unknown length.
        if (state.showDuration)
            EntryCard("Duration") {
                AmountStepper(
                    value = duration, onValue = { duration = it },
                    step = state.durationStep, min = 0.0, max = state.durationMax,
                    decimals = 0, unit = "min", name = "of duration", integerOnly = true,
                    onValidityChange = { durationValid = it }
                )
            }

        if (state.showNotes) NotesField(notes, { notes = it })
    }
}
