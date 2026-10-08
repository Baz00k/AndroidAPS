package app.aaps.core.compose.components

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/*
 * The building blocks every treatment entry screen (Calculator, Carbs, Insulin) is made of, so the
 * same kind of value always looks and behaves the same: a labelled card and a value between − and +
 * that can be tapped to set exactly. Sized to fit a whole entry on one screen without giving up
 * 44 dp touch targets.
 */

/** The size of an entry's main value: big enough to check at a glance, small enough to leave room. */
@Composable
private fun entryValueStyle(): TextStyle = AapsTheme.type.bigValue.let { it.copy(fontSize = it.fontSize * 0.7f, lineHeight = it.lineHeight * 0.7f) }

/** A labelled card holding one entry value. */
@Composable
fun EntryCard(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AapsCard(modifier, contentPadding = PaddingValues(horizontal = AapsSpacing.cardPad, vertical = AapsSpacing.cardPadSmall)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(label)
            content()
        }
    }
}

/** − and + around a value. The buttons say what they change for screen readers, and disable at a limit. */
@Composable
fun StepperRow(
    decreaseLabel: String,
    onDecrease: () -> Unit,
    increaseLabel: String,
    onIncrease: () -> Unit,
    decreaseEnabled: Boolean = true,
    increaseEnabled: Boolean = true,
    center: @Composable RowScope.() -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(plus = false, contentDescription = decreaseLabel, onClick = onDecrease, enabled = decreaseEnabled, size = AapsSpacing.minTap)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom, content = center)
        StepButton(plus = true, contentDescription = increaseLabel, onClick = onIncrease, enabled = increaseEnabled, size = AapsSpacing.minTap)
    }
}

/** A read-only value with its unit, for the centre of a [StepperRow]. */
@Composable
fun StepperValue(value: String, unit: String = "") {
    val colors = AapsTheme.colors
    Text(value, style = entryValueStyle(), color = colors.textPrimary)
    if (unit.isNotEmpty()) Text(" $unit", style = AapsTheme.type.listTitle, color = colors.textTertiary, modifier = Modifier.padding(bottom = 3.dp))
}

/**
 * An amount that can be stepped with − / + or typed. The typed text is kept while it is being edited;
 * the value itself is always clamped to [min]..[max]. At a limit the button towards it disables and
 * the limit is named, so a number that stopped growing never looks like a missed tap. With a
 * [zeroLabel], zero is shown as that word ("Normal") rather than as a number.
 */
@Composable
fun AmountStepper(
    value: Double,
    onValue: (Double) -> Unit,
    step: Double,
    min: Double,
    max: Double,
    decimals: Int,
    unit: String,
    name: String,
    zeroLabel: String? = null
) {
    val colors = AapsTheme.colors
    fun fmt(v: Double) = String.format(Locale.getDefault(), "%.${decimals}f", v)
    fun shown(v: Double) = if (zeroLabel != null && v == 0.0) "" else fmt(v)
    fun parse(s: String) = s.replace(',', '.').toDoubleOrNull() ?: if (zeroLabel != null && s.isEmpty()) 0.0 else null
    var text by remember { mutableStateOf(shown(value)) }
    LaunchedEffect(value) { if (parse(text) != value) text = shown(value) }
    val showsZeroLabel = zeroLabel != null && text.isEmpty()
    val stepText = fmt(step)
    // A text field fills whatever width it is given; size it to its text so the unit sits right after the number.
    val style = entryValueStyle().copy(color = colors.textPrimary)
    val measurer = rememberTextMeasurer()
    val fieldWidth = with(LocalDensity.current) { measurer.measure(text.ifEmpty { zeroLabel ?: "0" }, style).size.width.toDp() + 2.dp }
    val atMax = value >= max
    val atMin = value <= min
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StepperRow(
            decreaseLabel = "Subtract $stepText $unit $name", onDecrease = { onValue((value - step).coerceIn(min, max)) },
            increaseLabel = "Add $stepText $unit $name", onIncrease = { onValue((value + step).coerceIn(min, max)) },
            decreaseEnabled = !atMin, increaseEnabled = !atMax
        ) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    onValue((parse(it) ?: 0.0).coerceIn(min, max))
                },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(keyboardType = if (decimals > 0) KeyboardType.Decimal else KeyboardType.Number),
                decorationBox = { field ->
                    Box {
                        if (showsZeroLabel) Text(zeroLabel.orEmpty(), style = style)
                        field()
                    }
                },
                modifier = Modifier
                    .width(fieldWidth)
                    .semantics { if (showsZeroLabel) stateDescription = zeroLabel.orEmpty() }
            )
            if (!showsZeroLabel) Text(" $unit", style = AapsTheme.type.listTitle, color = colors.textTertiary, modifier = Modifier.padding(bottom = 3.dp))
        }
        // Zero is an obvious floor; a negative floor (a carb correction) and any ceiling are not.
        val limit = when {
            atMax            -> "Max ${fmt(max)} $unit"
            atMin && min < 0 -> "Min ${fmt(min)} $unit"
            else             -> null
        }
        if (limit != null) Text(limit, style = AapsTheme.type.caption, color = colors.textSecondary)
    }
}

/** The current time, refreshed every [periodMs], so a relative time on screen does not go stale. */
@Composable
fun rememberNow(periodMs: Long = 15_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * When something happened or will happen, as minutes from now, with the clock time under it. − / +
 * move it by [step] minutes; tapping the value opens a clock for anything further away.
 *
 * [atMs] is the moment itself, for an entry that records a fixed time: the clock shown (and the
 * picker's start) is then exactly what will be saved, however long the screen has been open.
 * Without it the time is relative, e.g. a meal 15 min after a bolus that has not been given yet.
 */
@Composable
fun TimeStepper(
    offsetMin: Int,
    onOffset: (Int) -> Unit,
    minOffsetMin: Int,
    maxOffsetMin: Int,
    step: Int = 5,
    atMs: Long? = null
) {
    val colors = AapsTheme.colors
    val context = LocalContext.current
    fun moment() = atMs ?: (System.currentTimeMillis() + offsetMin * 60_000L)
    var picking by remember { mutableStateOf(false) }
    fun set(minutes: Int) = onOffset(minutes.coerceIn(minOffsetMin, maxOffsetMin))
    StepperRow(
        decreaseLabel = "$step minutes earlier", onDecrease = { set(offsetMin - step) },
        increaseLabel = "$step minutes later", onIncrease = { set(offsetMin + step) },
        decreaseEnabled = offsetMin > minOffsetMin, increaseEnabled = offsetMin < maxOffsetMin
    ) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .clip(AapsTheme.shape.cardSmall)
                .clickable(role = Role.Button, onClickLabel = "Pick a time") { picking = true }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FittedText(EntryTime.relative(offsetMin), entryValueStyle(), colors.textPrimary, minScale = 0.5f)
            // Always shown, "Now" included, so stepping away from now does not grow the card under the finger.
            Text(
                DateFormat.getTimeFormat(context).format(Date(moment())),
                style = AapsTheme.type.caption, color = colors.textTertiary
            )
        }
    }
    if (picking) ClockPicker(
        initial = Instant.ofEpochMilli(moment()).atZone(ZoneId.systemDefault()).toLocalTime(),
        is24Hour = DateFormat.is24HourFormat(context),
        onPick = { time ->
            picking = false
            set(EntryTime.offsetFor(java.time.ZonedDateTime.now(), time, minOffsetMin, maxOffsetMin))
        },
        onDismiss = { picking = false }
    )
}

/**
 * Extended carbs: a slow meal spread over [hours], which the loop's single absorption constant cannot
 * describe. Zero, the usual meal, reads "Normal".
 */
@Composable
fun AbsorptionCard(hours: Int, onHours: (Int) -> Unit, maxHours: Int) {
    EntryCard("Absorption") {
        AmountStepper(
            value = hours.toDouble(), onValue = { onHours(it.toInt()) },
            step = 1.0, min = 0.0, max = maxHours.toDouble(), decimals = 0,
            unit = "h", name = "of carb absorption", zeroLabel = "Normal"
        )
    }
}

/** A clock on the app's own palette, rather than the platform dialog's. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockPicker(initial: LocalTime, is24Hour: Boolean, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
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
