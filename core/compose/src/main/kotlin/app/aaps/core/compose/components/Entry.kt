package app.aaps.core.compose.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.icons.AapsIcons
import app.aaps.core.compose.theme.AapsTheme
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/*
 * The building blocks every treatment entry screen (Calculator, Carbs, Insulin) is made of, so the
 * same kind of value always looks and behaves the same: a labelled card, a big value between − and +,
 * and a row of quick choices under it.
 */

/** A labelled card holding one entry value. */
@Composable
fun EntryCard(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AapsCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label.uppercase(Locale.getDefault()), style = AapsTheme.type.label, color = AapsTheme.colors.textSecondary)
            content()
        }
    }
}

/** − and + around a value. The buttons say what they change for screen readers. */
@Composable
fun StepperRow(
    decreaseLabel: String,
    onDecrease: () -> Unit,
    increaseLabel: String,
    onIncrease: () -> Unit,
    center: @Composable RowScope.() -> Unit
) {
    val colors = AapsTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepperButton(AapsIcons.Remove, decreaseLabel, colors.controlFill, colors.textPrimary, onDecrease)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom, content = center)
        StepperButton(Icons.Rounded.Add, increaseLabel, colors.accentTint, colors.accentOnLight, onIncrease)
    }
}

/** A read-only value with its unit, for the centre of a [StepperRow]. */
@Composable
fun StepperValue(value: String, unit: String = "") {
    val colors = AapsTheme.colors
    Text(value, style = AapsTheme.type.bigValue, color = colors.textPrimary)
    if (unit.isNotEmpty()) Text(" $unit", style = AapsTheme.type.listTitle, color = colors.textTertiary, modifier = Modifier.padding(bottom = 4.dp))
}

/**
 * An amount that can be stepped with − / + or typed. The typed text is kept while it is being edited;
 * the value itself is always clamped to [min]..[max].
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
    name: String
) {
    val colors = AapsTheme.colors
    fun fmt(v: Double) = String.format(Locale.getDefault(), "%.${decimals}f", v)
    fun parse(s: String) = s.replace(',', '.').toDoubleOrNull()
    var text by remember { mutableStateOf(fmt(value)) }
    LaunchedEffect(value) { if (parse(text) != value) text = fmt(value) }
    val stepText = fmt(step)
    StepperRow(
        decreaseLabel = "Subtract $stepText $unit $name", onDecrease = { onValue((value - step).coerceIn(min, max)) },
        increaseLabel = "Add $stepText $unit $name", onIncrease = { onValue((value + step).coerceIn(min, max)) }
    ) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                onValue((parse(it) ?: 0.0).coerceIn(min, max))
            },
            singleLine = true,
            textStyle = AapsTheme.type.bigValue.copy(color = colors.textPrimary, textAlign = TextAlign.End),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = if (decimals > 0) KeyboardType.Decimal else KeyboardType.Number),
            modifier = Modifier
                .weight(1f, fill = false)
                .width(androidx.compose.foundation.layout.IntrinsicSize.Min)
        )
        Text(" $unit", style = AapsTheme.type.listTitle, color = colors.textTertiary, modifier = Modifier.padding(bottom = 4.dp))
    }
}

/**
 * When something happened or will happen, as minutes from now. − / + move it by [step] minutes; tapping
 * the value opens a clock for anything further away. Quick [presets] are minute offsets; [extra] adds
 * screen-specific choices to the same row.
 */
@Composable
fun TimeStepper(
    offsetMin: Int,
    onOffset: (Int) -> Unit,
    minOffsetMin: Int,
    maxOffsetMin: Int,
    presets: List<Int>,
    step: Int = 5,
    selected: Boolean = true,
    extra: @Composable RowScope.() -> Unit = {}
) {
    val colors = AapsTheme.colors
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    fun set(minutes: Int) = onOffset(minutes.coerceIn(minOffsetMin, maxOffsetMin))
    StepperRow(
        decreaseLabel = "$step minutes earlier", onDecrease = { set(offsetMin - step) },
        increaseLabel = "$step minutes later", onIncrease = { set(offsetMin + step) }
    ) {
        Column(
            Modifier
                .clip(AapsTheme.shape.cardSmall)
                .clickable(role = Role.Button, onClickLabel = "Pick a time") { picking = true }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(if (selected) EntryTime.relative(offsetMin) else "—", style = AapsTheme.type.bigValue, color = colors.textPrimary, textAlign = TextAlign.Center)
            if (selected && offsetMin != 0)
                Text(
                    DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + offsetMin * 60_000L)),
                    style = AapsTheme.type.caption, color = colors.textTertiary
                )
        }
    }
    ChoiceRow {
        presets.forEach { m -> Choice(EntryTime.signed(m), selected = selected && offsetMin == m) { set(m) } }
        extra()
    }
    if (picking) ClockPicker(
        initial = Instant.ofEpochMilli(System.currentTimeMillis() + offsetMin * 60_000L).atZone(ZoneId.systemDefault()).toLocalTime(),
        is24Hour = DateFormat.is24HourFormat(context),
        onPick = { time ->
            picking = false
            set(EntryTime.offsetFor(java.time.ZonedDateTime.now(), time, minOffsetMin, maxOffsetMin))
        },
        onDismiss = { picking = false }
    )
}

/** A row of equally wide quick choices. */
@Composable
fun ChoiceRow(content: @Composable RowScope.() -> Unit) =
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), content = content)

@Composable
fun RowScope.Choice(
    label: String,
    selected: Boolean,
    clickLabel: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val colors = AapsTheme.colors
    val fg = if (selected) colors.accentOnLight else colors.textSecondary
    Row(
        Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .clip(AapsTheme.shape.pill)
            .background(if (selected) colors.accentTintStrong else colors.controlFill)
            .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier
            .size(16.dp)
            .padding(end = 2.dp))
        FittedText(label, AapsTheme.type.listTitle.copy(textAlign = TextAlign.Center), fg)
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

@Composable
private fun StepperButton(icon: ImageVector, cd: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = cd, tint = fg, modifier = Modifier.size(24.dp)) }
}
