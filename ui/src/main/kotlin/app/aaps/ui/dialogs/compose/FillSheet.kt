package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.compose.components.ActionChip
import app.aaps.core.compose.components.AmountStepper
import app.aaps.core.compose.components.ChoiceRow
import app.aaps.core.compose.components.EntryCard
import app.aaps.core.compose.components.NotesField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.TimeStepper
import app.aaps.core.compose.components.ToggleRow
import app.aaps.core.compose.components.formatNumeric
import app.aaps.core.compose.components.rememberNow
import kotlin.math.roundToInt

data class FillSheetState(
    val maxInsulin: Double,
    val insulinStep: Double,
    val insulinDecimals: Int,
    val presets: List<Double>,
    val showNotes: Boolean
)

data class FillInputs(
    val insulin: Double,
    val siteChange: Boolean,
    val insulinChange: Boolean,
    val notes: String,
    /**
     * When the site/cartridge change happened; null means now at submission. Only the records use
     * it: the prime is delivered now. Kept as a moment so waiting does not move the chosen time.
     */
    val changedAt: Long?
)

private const val MAX_CHANGE_AGE_MIN = 24 * 60

// Treatment timestamps are whole seconds; a clock rollback must not future-date a record.
internal fun fillRecordTime(now: Long, changedAt: Long?): Long =
    (changedAt ?: now).coerceAtMost(now).let { it - it % 1000 }

@Composable
fun FillSheet(state: FillSheetState, onSubmit: (FillInputs) -> Unit, onClose: () -> Unit) {
    var insulin by rememberSaveable { mutableStateOf(0.0) }
    var siteChange by rememberSaveable { mutableStateOf(false) }
    var insulinChange by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }
    var changedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var insulinValid by remember { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val now = rememberNow()
    val changeOffset = changedAt?.let { ((it - now) / 60_000.0).roundToInt() } ?: 0
    val records = siteChange || insulinChange

    // Read state when invoked: a same-frame second tap must still see the latch.
    fun canSubmit() = insulinValid && !submitted && (insulin > 0.0 || records)

    fun submit() {
        if (!canSubmit()) return
        submitted = true
        onSubmit(
            FillInputs(
                insulin = insulin,
                siteChange = siteChange,
                insulinChange = insulinChange,
                notes = notes,
                changedAt = if (records) changedAt else null
            )
        )
    }

    SheetSurface(
        title = "Prime / Fill",
        onClose = onClose,
        footer = { PrimaryButton("Confirm", onClick = ::submit, enabled = canSubmit()) }
    ) {
        EntryCard("Insulin") {
            AmountStepper(
                value = insulin, onValue = { insulin = it },
                step = state.insulinStep, min = 0.0, max = state.maxInsulin,
                decimals = state.insulinDecimals, unit = "U", name = "of insulin",
                onValidityChange = { insulinValid = it }
            )
            if (state.presets.isNotEmpty())
                ChoiceRow {
                    state.presets.forEach { preset ->
                        ActionChip(
                            label = formatNumeric(preset, state.insulinDecimals),
                            modifier = Modifier.weight(1f),
                            onClick = { insulin = preset.coerceIn(0.0, state.maxInsulin) }
                        )
                    }
                }
        }

        ToggleRow("Pump site change", siteChange, { siteChange = it }, sub = "Record cannula change")
        ToggleRow("Insulin cartridge change", insulinChange, { insulinChange = it }, sub = "Record reservoir change")

        if (records) {
            EntryCard("Changed") {
                TimeStepper(
                    offsetMin = changeOffset,
                    onOffset = { changedAt = if (it == 0) null else System.currentTimeMillis() + it * 60_000L },
                    minOffsetMin = -MAX_CHANGE_AGE_MIN,
                    maxOffsetMin = 0,
                    atMs = changedAt
                )
            }
        }

        if (state.showNotes) NotesField(notes, { notes = it })
    }
}
