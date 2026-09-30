package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AmountStepper
import app.aaps.core.compose.components.Choice
import app.aaps.core.compose.components.ChoiceRow
import app.aaps.core.compose.components.EntryCard
import app.aaps.core.compose.components.NotesField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.TimeStepper
import app.aaps.core.compose.theme.AapsSpacing
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
    var intent by rememberSaveable { mutableStateOf(if (state.deliveryUnavailable != null) InsulinIntent.LOG else InsulinIntent.DELIVER) }
    // The picked time is a moment, not a distance from whenever the form is finally submitted.
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var pickedAt by rememberSaveable { mutableLongStateOf(0L) }
    var target by rememberSaveable { mutableStateOf(TargetPreset.NONE) }
    var notes by rememberSaveable { mutableStateOf("") }

    fun fmt(v: Double) = String.format(java.util.Locale.getDefault(), "%.${state.decimals}f", v)

    SheetSurface(title = "Insulin", onClose = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) {
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
                    Text(it.label, style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(start = 12.dp))
                }
            }
            EntryCard("Insulin") {
                AmountStepper(amount, { amount = it }, step = state.bolusStep, min = 0.0, max = state.maxInsulin, decimals = state.decimals, unit = "U", name = "of insulin")
                ChoiceRow {
                    state.quickIncrements.forEach { inc ->
                        Choice((if (inc > 0) "+" else "") + fmt(inc), selected = false) { amount = (amount + inc).coerceIn(0.0, state.maxInsulin) }
                    }
                }
            }
            if (intent == InsulinIntent.LOG)
                EntryCard("When") {
                    TimeStepper(offset, { offset = it; pickedAt = System.currentTimeMillis() }, -InsulinEntryPolicy.MAX_LOG_AGE_MIN, 0, presets = listOf(-60, -30, -15, 0))
                }
            TargetPresetCard(state.targets, target) { target = it }
            if (state.showNotes) NotesField(notes, { notes = it })
            val verb = if (intent == InsulinIntent.LOG) "Log" else "Deliver"
            PrimaryButton(
                label = when {
                    amount > 0.0                -> "$verb ${fmt(amount)} U"
                    target != TargetPreset.NONE -> "Set target"
                    else                        -> verb
                },
                enabled = amount > 0.0 || target != TargetPreset.NONE,
                onClick = { onSubmit(InsulinInputs(amount, intent, if (offset == 0) null else pickedAt + offset * 60_000L, target, if (state.showNotes) notes else "")) }
            )
        }
    }
}
