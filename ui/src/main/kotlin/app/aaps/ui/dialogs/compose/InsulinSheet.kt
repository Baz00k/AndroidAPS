package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.ActionChip
import app.aaps.core.compose.components.AmountStepper
import app.aaps.core.compose.components.ChoiceRow
import app.aaps.core.compose.components.EntryCard
import app.aaps.core.compose.components.NotesField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.TimeStepper
import app.aaps.core.compose.components.rememberNow
import app.aaps.core.compose.components.formatNumeric
import app.aaps.core.compose.components.NumericSpec
import kotlin.math.roundToInt
import app.aaps.core.compose.theme.AapsTheme

data class InsulinSheetState(
    val maxInsulin: Double,
    val bolusStep: Double,
    val decimals: Int,
    val quickIncrements: List<Double>,
    /** Set when the pump cannot be asked for a bolus; the screen then only logs. */
    val deliveryUnavailable: DeliveryUnavailable?,
    val targets: List<TargetPresetOption>,
    val showNotes: Boolean
)

data class InsulinInputs(
    val amount: Double,
    val intent: InsulinIntent,
    /** When a logged dose was given, or null for now. Ignored for delivery, which is always now. */
    val givenAt: Long?,
    val target: TargetPreset,
    val notes: String
)

/**
 * The single manual insulin screen, laid out like Carbs and the Calculator. Deliver asks the pump for
 * a bolus now; Log records a dose given another way (pen, a missed pump record) at the time it was
 * given, without touching the pump.
 */
@Composable
fun InsulinSheet(state: InsulinSheetState, onSubmit: (InsulinInputs) -> Unit, onClose: () -> Unit) {
    val colors = AapsTheme.colors
    var amount by rememberSaveable { mutableStateOf(0.0) }
    var amountValid by remember { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var intent by rememberSaveable { mutableStateOf(if (state.deliveryUnavailable != null) InsulinIntent.LOG else InsulinIntent.DELIVER) }
    // The picked time is a moment, not a distance from whenever the form is finally submitted; the
    // distance shown is derived from it.
    var at by rememberSaveable { mutableStateOf<Long?>(null) }
    val now = rememberNow()
    val offset = at?.let { ((it - now) / 60_000.0).roundToInt() } ?: 0
    var target by rememberSaveable { mutableStateOf(TargetPreset.NONE) }
    var notes by rememberSaveable { mutableStateOf("") }

    fun fmt(v: Double) = formatNumeric(v, state.decimals)

    val verb = if (intent == InsulinIntent.LOG) "Log" else "Deliver"
    SheetSurface(
        title = "Insulin",
        onClose = onClose,
        footer = {
            PrimaryButton(
                label = when {
                    amount > 0.0                -> "$verb ${fmt(amount)} U"
                    target != TargetPreset.NONE -> "Set target"
                    else                        -> verb
                },
                enabled = amountValid && !submitted && (amount > 0.0 || target != TargetPreset.NONE),
                onClick = {
                    if (amountValid && !submitted) {
                        submitted = true
                        onSubmit(InsulinInputs(amount, intent, at, target, if (state.showNotes) notes else ""))
                    }
                }
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SegmentedControl(
                options = listOf("Deliver", "Log"),
                selectedIndex = intent.ordinal,
                onSelect = { intent = InsulinIntent.entries[it] },
                modifier = Modifier.fillMaxWidth(),
                fillWidth = true,
                disabled = if (state.deliveryUnavailable != null) setOf(InsulinIntent.DELIVER.ordinal) else emptySet(),
                disabledReason = state.deliveryUnavailable?.label
            )
            state.deliveryUnavailable?.let {
                Text(it.label, style = AapsTheme.type.caption, color = colors.textSecondary, modifier = Modifier.padding(start = 12.dp))
            }
        }
        EntryCard("Insulin") {
            AmountStepper(amount, { amount = it }, step = state.bolusStep, min = 0.0, max = state.maxInsulin, decimals = state.decimals, unit = "U", name = "of insulin", onValidityChange = { amountValid = it })
            ChoiceRow {
                state.quickIncrements.forEach { inc ->
                    ActionChip((if (inc > 0) "+" else "") + fmt(inc) + " U", modifier = Modifier.weight(1f), enabled = amountValid && (if (inc > 0) amount < state.maxInsulin else amount > 0.0), onClick = {
                        if (amountValid) NumericSpec(0.0, state.maxInsulin, kotlin.math.abs(inc), state.decimals).increment(amount, inc > 0)?.let { amount = it }
                    })
                }
            }
        }
        if (intent == InsulinIntent.LOG)
            EntryCard("When") {
                TimeStepper(offset, { at = if (it == 0) null else System.currentTimeMillis() + it * 60_000L }, -InsulinEntryPolicy.MAX_LOG_AGE_MIN, 0, atMs = at)
            }
        TargetPresetCard(state.targets, target) { target = it }
        if (state.showNotes) NotesField(notes, { notes = it })
    }
}
