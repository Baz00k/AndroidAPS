package app.aaps.core.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.R

/**
 * Valid edits commit exactly the displayed number; invalid/incomplete edits remain drafts and publish
 * no number. Callers must gate consequential actions with [onValidityChange]. A changed external value
 * replaces the draft. Changed bounds revalidate the existing text, without clamping it; a newly valid
 * draft commits before enabling submission. Restored drafts are revalidated before any commit.
 * [spec.decimals] never limits typed precision or quantizes a dose.
 */
@Composable
fun NumericInput(
    value: Double,
    onValue: (Double) -> Unit,
    spec: NumericSpec,
    unit: String,
    name: String,
    modifier: Modifier = Modifier,
    zeroLabel: String? = null,
    onValidityChange: (Boolean) -> Unit = {},
    onPrecisionInsufficient: (Boolean) -> Unit = {}
) {
    val colors = AapsTheme.colors
    val publish by rememberUpdatedState(onValue)
    val validityChanged by rememberUpdatedState(onValidityChange)
    var text by rememberSaveable { mutableStateOf(formatNumeric(value, spec.decimals)) }
    var observedValue by rememberSaveable { mutableStateOf(value) }
    var observedDecimals by rememberSaveable { mutableStateOf(spec.decimals) }
    // Synchronous reconciliation: no frame where changed bounds/external data use old validity.
    if (value != observedValue && !(value.isNaN() && observedValue.isNaN())) {
        text = formatNumeric(value, spec.decimals)
        observedValue = value
    }
    if (observedDecimals != spec.decimals) {
        // Reformat a committed value, but never erase an incomplete draft on a configuration change.
        if (spec.validate(text).value == value) text = formatNumeric(value, spec.decimals)
        observedDecimals = spec.decimals
    }
    val validation = spec.validate(text)
    val current = validation.value
    SideEffect {
        // A formerly invalid draft can become valid when bounds widen. Publish that exact draft
        // before allowing submission; never mark a draft valid while the caller still has an old value.
        onValidityChange(current != null && current == value)
        if (current != null && current != value) {
            observedValue = current
            publish(current)
        }
        onPrecisionInsufficient(spec.precisionInsufficient)
    }
    fun edit(draft: NumericDraft) {
        text = draft.text
        val valid = draft.validated(spec)
        validityChanged(valid != null)
        if (valid != null) {
            // Track our own commit so the parent's echo does not replace incremental typing.
            observedValue = valid
            draft.commit(spec, publish)
        }
    }
    val issue = when (validation.error) {
        NumericError.INCOMPLETE -> stringResource(R.string.compose_numeric_incomplete)
        NumericError.NUMBER -> stringResource(R.string.compose_numeric_invalid)
        NumericError.MINIMUM -> stringResource(R.string.compose_numeric_min, formatNumeric(spec.min, spec.decimals), unit)
        NumericError.MAXIMUM -> stringResource(R.string.compose_numeric_max, formatNumeric(spec.max, spec.decimals), unit)
        NumericError.INTEGER -> stringResource(R.string.compose_numeric_integer)
        NumericError.CONFIGURATION -> stringResource(R.string.compose_numeric_configuration)
        null -> null
    }
    val limit = when (current) {
        spec.min -> stringResource(R.string.compose_numeric_min, formatNumeric(spec.min, spec.decimals), unit)
        spec.max -> stringResource(R.string.compose_numeric_max, formatNumeric(spec.max, spec.decimals), unit)
        else -> null
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StepperRow(
            decreaseLabel = stringResource(R.string.compose_numeric_subtract, formatNumeric(spec.step, spec.decimals), unit, name),
            increaseLabel = stringResource(R.string.compose_numeric_add, formatNumeric(spec.step, spec.decimals), unit, name),
            onDecrease = { NumericDraft(text).stepped(spec, false)?.let(::edit) },
            onIncrease = { NumericDraft(text).stepped(spec, true)?.let(::edit) },
            decreaseEnabled = current != null && current > spec.min,
            increaseEnabled = current != null && current < spec.max
        ) {
            BasicTextField(
                value = text,
                onValueChange = { edit(NumericDraft(it)) },
                singleLine = true,
                textStyle = AapsTheme.type.cardValue.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                // Compose's numeric keyboard types do not request TYPE_NUMBER_FLAG_SIGNED. A text
                // keyboard is intentional for signed entries: both minus and decimal separators work.
                keyboardOptions = KeyboardOptions(keyboardType = if (spec.min < 0) KeyboardType.Text else KeyboardType.Decimal),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics {
                    contentDescription = name
                    issue?.let { error(it) }
                    stateDescription = listOfNotNull(unit.takeIf { it.isNotBlank() }, zeroLabel.takeIf { current == 0.0 }, limit).joinToString(", ")
                }
            )
            // The field scrolls internally rather than displacing the separately measured unit.
            if (unit.isNotBlank()) Text(unit, style = AapsTheme.type.body, color = colors.textTertiary, modifier = Modifier.padding(bottom = 3.dp))
        }
        if (current == 0.0 && zeroLabel != null) Text(zeroLabel, style = AapsTheme.type.caption, color = colors.textSecondary)
        (issue ?: limit)?.let { Text(it, style = AapsTheme.type.caption, color = if (issue != null) colors.high else colors.textSecondary) }
        if (spec.precisionInsufficient)
            Text(stringResource(R.string.compose_numeric_precision, spec.decimals), style = AapsTheme.type.caption, color = colors.textSecondary)
    }
}
