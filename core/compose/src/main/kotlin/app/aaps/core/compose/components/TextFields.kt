package app.aaps.core.compose.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.R
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import java.util.Locale

/**
 * A single-line text input with its [label] above it and, when the value is not acceptable, an
 * [error] below it. Label, placeholder and error are drawn inside the field's own node, so TalkBack
 * reads them with the field and announces the error as one.
 */
@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) = AapsTextField(label, value, onValueChange, modifier, placeholder, error, enabled, singleLine = true, keyboardOptions, keyboardActions)

/** Free-text notes on a record. Wraps onto as many lines as the note needs. */
@Composable
fun NotesField(
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.compose_notes_label)
) = AapsTextField(
    label, value, onValue, modifier,
    placeholder = stringResource(R.string.compose_notes_placeholder),
    error = null, enabled = true, singleLine = false,
    keyboardOptions = KeyboardOptions.Default, keyboardActions = KeyboardActions.Default
)

@Composable
private fun AapsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    placeholder: String?,
    error: String?,
    enabled: Boolean,
    singleLine: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions
) {
    val colors = AapsTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = AapsTheme.type.body.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
            .fillMaxWidth()
            .disabledAlpha(enabled)
            .then(if (error != null) Modifier.semantics { error(error) } else Modifier),
        decorationBox = { field ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label.uppercase(Locale.getDefault()), style = AapsTheme.type.label, color = colors.textSecondary)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = AapsSpacing.minTap)
                        .clip(AapsTheme.shape.cardSmall)
                        .background(colors.surface2)
                        .then(if (error != null) Modifier.border(BorderStroke(AapsSpacing.hairlineWidth, colors.low), AapsTheme.shape.cardSmall) else Modifier)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = AapsTheme.type.body, color = colors.textTertiary)
                    field()
                }
                if (error != null) Text(error, style = AapsTheme.type.caption, color = colors.low)
            }
        }
    )
}

@ComponentPreviews
@Composable
private fun TextFieldsPreview() = PreviewSurface {
    var name by remember { mutableStateOf("LocalProfile1") }
    LabeledTextField("Name", name, { name = it })
    LabeledTextField("Name", "", {}, placeholder = "Profile name", error = "Enter a name")
    LabeledTextField("Name", "Read only", {}, enabled = false)
    NotesField("", {})
}
