package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.compose.components.AmountStepper
import app.aaps.core.compose.components.Choice
import app.aaps.core.compose.components.ChoiceRow
import app.aaps.core.compose.components.EntryCard
import app.aaps.core.compose.components.NotesField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.StepperRow
import app.aaps.core.compose.components.StepperValue
import app.aaps.core.compose.components.TimeStepper
import app.aaps.core.compose.components.ToggleRow
import app.aaps.core.compose.theme.AapsSpacing

data class CarbsSheetState(
    val maxCarbs: Double,
    val quickIncrements: List<Int>,
    val maxDurationHours: Int,
    val targets: List<TargetPresetOption>,
    /** Preselected when glucose is low and no longer high target is already running. */
    val initialTarget: TargetPreset,
    val showBolusReminder: Boolean,
    val showNotes: Boolean
)

data class CarbsInputs(
    val carbs: Int,
    /** When the carbs are eaten, or null for now. */
    val eatenAt: Long?,
    val durationHours: Int,
    val target: TargetPreset,
    val useAlarm: Boolean,
    val remindBolus: Boolean,
    val notes: String
)

/** Earliest and latest carb time, minutes from now: a week back for a forgotten entry, 12 h ahead for a planned one. */
private const val CARBS_EARLIEST_MIN = -7 * 24 * 60
private const val CARBS_LATEST_MIN = 12 * 60

/**
 * Carbs, laid out like the Calculator: amount, when, absorption, then a target. A negative amount
 * is a correction of carbs already logged. [onSubmit] runs the same constraint + confirmation +
 * temp-target / carbs + reminder path.
 */
@Composable
fun CarbsSheet(state: CarbsSheetState, onSubmit: (CarbsInputs) -> Unit, onClose: () -> Unit) {
    var carbs by rememberSaveable { mutableStateOf(0.0) }
    // The picked time is a moment, not a distance from whenever the form is finally submitted.
    var timeOffset by rememberSaveable { mutableIntStateOf(0) }
    var pickedAt by rememberSaveable { mutableLongStateOf(0L) }
    var duration by rememberSaveable { mutableIntStateOf(0) }
    var target by rememberSaveable { mutableStateOf(state.initialTarget) }
    var alarm by rememberSaveable { mutableStateOf(false) }
    var remindBolus by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }

    // Only a future meal can be reminded; the toggle is hidden (and its value ignored) otherwise.
    val eatReminderAvailable = timeOffset > 0 && carbs > 0

    SheetSurface(title = "Carbs", onClose = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) {
            EntryCard("Carbs") {
                AmountStepper(carbs, { carbs = it }, step = 1.0, min = -state.maxCarbs, max = state.maxCarbs, decimals = 0, unit = "g", name = "of carbs")
                ChoiceRow {
                    state.quickIncrements.forEach { inc ->
                        Choice(if (inc > 0) "+$inc g" else "$inc g", selected = false) { carbs = (carbs + inc).coerceIn(-state.maxCarbs, state.maxCarbs) }
                    }
                }
            }
            EntryCard("When") {
                TimeStepper(timeOffset, { timeOffset = it; pickedAt = System.currentTimeMillis() }, CARBS_EARLIEST_MIN, CARBS_LATEST_MIN, presets = listOf(-30, -15, 0, 15))
                if (eatReminderAvailable) ToggleRow("Remind me to eat", alarm, { alarm = it })
            }
            EntryCard("Absorption") {
                StepperRow(
                    decreaseLabel = "Shorten carb absorption by 1 hour", onDecrease = { duration = (duration - 1).coerceAtLeast(0) },
                    increaseLabel = "Lengthen carb absorption by 1 hour", onIncrease = { duration = (duration + 1).coerceAtMost(state.maxDurationHours) }
                ) { StepperValue(if (duration == 0) "fast" else "$duration", if (duration == 0) "" else "h") }
                ChoiceRow {
                    listOf(0, 2, 3, 4).forEach { h -> Choice(if (h == 0) "Fast" else "$h h", selected = duration == h) { duration = h } }
                }
            }
            TargetPresetCard(state.targets, target) { target = it }
            if (state.showBolusReminder) ToggleRow("Remind me to bolus", remindBolus, { remindBolus = it })
            if (state.showNotes) NotesField(notes, { notes = it })

            val grams = carbs.toInt()
            PrimaryButton(
                label = when {
                    grams != 0                  -> "Log $grams g"
                    target != TargetPreset.NONE -> "Set target"
                    else                        -> "Log"
                },
                enabled = grams != 0 || target != TargetPreset.NONE,
                onClick = {
                    onSubmit(
                        CarbsInputs(
                            carbs = grams,
                            eatenAt = if (timeOffset == 0) null else pickedAt + timeOffset * 60_000L,
                            durationHours = duration,
                            target = target,
                            useAlarm = alarm && eatReminderAvailable,
                            remindBolus = remindBolus && state.showBolusReminder,
                            notes = if (state.showNotes) notes else ""
                        )
                    )
                }
            )
        }
    }
}
