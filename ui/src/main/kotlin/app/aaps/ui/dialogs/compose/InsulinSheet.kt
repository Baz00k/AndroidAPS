package app.aaps.ui.dialogs.compose

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.Chip
import app.aaps.core.compose.components.NotesField
import app.aaps.core.compose.components.NumberField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.ToggleRow
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class InsulinSheetState(
    val maxInsulin: Double,
    val bolusStep: Double,
    val decimals: Int,
    val quickIncrements: List<Double>,
    /** Set when the pump cannot be asked for a bolus; the screen then only logs. */
    val deliveryUnavailable: DeliveryUnavailable?,
    /** The configured eating-soon target, e.g. "5.0 mmol/L · 45 min". */
    val eatingSoonSummary: String,
    val showNotes: Boolean,
    val now: () -> Long,
    /** Clock and date text for a logged dose's time. */
    val formatTime: (Long) -> String
)

data class InsulinInputs(
    val amount: Double,
    val intent: InsulinIntent,
    /** When a logged dose was given. Ignored for delivery, which always happens now. */
    val loggedAt: Long,
    val eatingSoon: Boolean,
    val notes: String
)

/**
 * The single manual insulin screen. Deliver asks the pump for a bolus now; Log records a dose given
 * another way (pen, a missed pump record) at the time it was given, without touching the pump.
 */
@Composable
fun InsulinSheet(state: InsulinSheetState, onSubmit: (InsulinInputs) -> Unit, onClose: () -> Unit) {
    val colors = AapsTheme.colors
    val context = LocalContext.current
    var amount by remember { mutableStateOf(0.0) }
    var intent by remember { mutableStateOf(if (state.deliveryUnavailable != null) InsulinIntent.LOG else InsulinIntent.DELIVER) }
    var loggedAt by remember { mutableLongStateOf(state.now()) }
    var eatingSoon by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }

    fun fmt(v: Double) = String.format(java.util.Locale.getDefault(), "%.${state.decimals}f", v)
    fun fmtInc(v: Double) = (if (v > 0) "+" else "") + fmt(v)

    var pickingTime by remember { mutableStateOf(false) }
    if (pickingTime) LogTimePicker(
        initial = Instant.ofEpochMilli(loggedAt).atZone(ZoneId.systemDefault()).toLocalTime(),
        is24Hour = DateFormat.is24HourFormat(context),
        onPick = { time ->
            pickingTime = false
            loggedAt = InsulinEntryPolicy.logTime(state.now(), time, ZoneId.systemDefault())
        },
        onDismiss = { pickingTime = false }
    )

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
            NumberField("Insulin", amount, { amount = it }, step = state.bolusStep, min = 0.0, max = state.maxInsulin, decimals = state.decimals, unit = "U", modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.quickIncrements.forEach { inc -> Chip(fmtInc(inc), onClick = { amount = (amount + inc).coerceIn(0.0, state.maxInsulin) }) }
            }
            if (intent == InsulinIntent.LOG)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(AapsTheme.shape.cardSmall)
                        .clickable(role = Role.Button, onClickLabel = "Change time") { pickingTime = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Given at", style = AapsTheme.type.listTitle, color = colors.textOnSurfaceStrong, modifier = Modifier.weight(1f))
                    Text(
                        state.formatTime(loggedAt),
                        style = AapsTheme.type.listTitle,
                        color = colors.accentOnLight,
                        modifier = Modifier
                            .clip(AapsTheme.shape.pill)
                            .background(colors.accentTint)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            ToggleRow("Eating soon target", eatingSoon, { eatingSoon = it }, sub = state.eatingSoonSummary)
            if (state.showNotes) NotesField(notes, { notes = it })
            val verb = if (intent == InsulinIntent.LOG) "Log" else "Deliver"
            val label = when {
                amount > 0.0 -> "$verb ${fmt(amount)} U"
                eatingSoon   -> "Set target"
                else         -> verb
            }
            PrimaryButton(
                label = label,
                enabled = amount > 0.0 || eatingSoon,
                onClick = { onSubmit(InsulinInputs(amount, intent, loggedAt, eatingSoon, if (state.showNotes) notes else "")) }
            )
        }
    }
}

/** The time a logged dose was given, picked on the app's own palette rather than the platform dialog's. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogTimePicker(initial: LocalTime, is24Hour: Boolean, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val colors = AapsTheme.colors
    val picker = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24Hour)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(picker.hour, picker.minute)) }) { Text("OK", color = colors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) } },
        text = {
            TimePicker(
                state = picker,
                colors = TimePickerDefaults.colors(
                    clockDialColor = colors.controlFill,
                    clockDialSelectedContentColor = colors.onAccent,
                    clockDialUnselectedContentColor = colors.textPrimary,
                    selectorColor = colors.accent,
                    containerColor = colors.surface,
                    periodSelectorBorderColor = colors.hairline,
                    periodSelectorSelectedContainerColor = colors.accentTintStrong,
                    periodSelectorUnselectedContainerColor = colors.surface,
                    periodSelectorSelectedContentColor = colors.accentOnLight,
                    periodSelectorUnselectedContentColor = colors.textSecondary,
                    timeSelectorSelectedContainerColor = colors.accentTintStrong,
                    timeSelectorUnselectedContainerColor = colors.controlFill,
                    timeSelectorSelectedContentColor = colors.accentOnLight,
                    timeSelectorUnselectedContentColor = colors.textPrimary
                )
            )
        }
    )
}
