package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.aaps.core.compose.components.AbsorptionCard
import app.aaps.core.compose.components.AmountStepper
import app.aaps.core.compose.components.Choice
import app.aaps.core.compose.components.ChoiceRow
import app.aaps.core.compose.components.EntryCard
import app.aaps.core.compose.components.NotesField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.TimeStepper
import app.aaps.core.compose.components.rememberNow
import kotlin.math.roundToInt
import app.aaps.core.compose.components.ToggleRow

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
    // The picked time is a moment, not a distance from whenever the form is finally submitted; the
    // distance shown is derived from it.
    var at by rememberSaveable { mutableStateOf<Long?>(null) }
    val now = rememberNow()
    val timeOffset = at?.let { ((it - now) / 60_000.0).roundToInt() } ?: 0
    var duration by rememberSaveable { mutableIntStateOf(0) }
    var target by rememberSaveable { mutableStateOf(state.initialTarget) }
    var alarm by rememberSaveable { mutableStateOf(false) }
    var remindBolus by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }

    // Only a future meal can be reminded; the toggle is hidden (and its value ignored) otherwise.
    val eatReminderAvailable = timeOffset > 0 && carbs > 0

    val grams = carbs.toInt()
    SheetSurface(
        title = "Carbs",
        onClose = onClose,
        footer = {
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
                            eatenAt = at,
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
    ) {
        EntryCard("Carbs") {
            AmountStepper(carbs, { carbs = it }, step = 1.0, min = -state.maxCarbs, max = state.maxCarbs, decimals = 0, unit = "g", name = "of carbs")
            ChoiceRow {
                state.quickIncrements.forEach { inc ->
                    Choice(if (inc > 0) "+$inc g" else "$inc g", selected = false, enabled = if (inc > 0) carbs < state.maxCarbs else carbs > -state.maxCarbs) {
                        carbs = (carbs + inc).coerceIn(-state.maxCarbs, state.maxCarbs)
                    }
                }
            }
        }
        EntryCard("When") {
            TimeStepper(timeOffset, { at = if (it == 0) null else System.currentTimeMillis() + it * 60_000L }, CARBS_EARLIEST_MIN, CARBS_LATEST_MIN, atMs = at)
            if (eatReminderAvailable) ToggleRow("Remind me to eat", alarm, { alarm = it })
        }
        AbsorptionCard(duration, { duration = it }, state.maxDurationHours)
        TargetPresetCard(state.targets, target) { target = it }
        if (state.showBolusReminder) ToggleRow("Remind me to bolus", remindBolus, { remindBolus = it }, sub = "When glucose is rising again")
        if (state.showNotes) NotesField(notes, { notes = it })
    }
}
