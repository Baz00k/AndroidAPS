package app.aaps.core.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/*
 * A title (and optional [sub]) with a checkbox, radio button or switch. The whole row is the control:
 * it is one touch target and one TalkBack node, announced with its title, state and role. The inner
 * control only draws the state; it is never a second, separate target.
 */

/** One independent yes/no option in a list, with a leading checkbox. */
@Composable
fun CheckboxRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    enabled: Boolean = true
) {
    val colors = AapsTheme.colors
    SelectionRow(
        title, sub, enabled,
        modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        leading = {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = colors.accent, checkmarkColor = colors.onAccent, uncheckedColor = colors.textTertiary)
            )
        }
    )
}

/** One option of a set listed one per row, with a leading radio button. Put the rows in a [Modifier.selectableGroup]. */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    enabled: Boolean = true
) {
    val colors = AapsTheme.colors
    SelectionRow(
        title, sub, enabled,
        modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        leading = {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.textTertiary)
            )
        }
    )
}

/** A setting that takes effect when switched, with a trailing switch. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    enabled: Boolean = true
) = SelectionRow(
    title, sub, enabled,
    modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    trailing = { Switch(checked = checked, onCheckedChange = null, colors = aapsSwitchColors()) }
)

@Composable
private fun SelectionRow(
    title: String,
    sub: String?,
    enabled: Boolean,
    modifier: Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = AapsTheme.colors
    Row(
        // The disabled look goes on the row, so the inner control is drawn in its normal colours
        // rather than dimmed twice.
        modifier
            .fillMaxWidth()
            .heightIn(min = AapsSpacing.minTap)
            .disabledAlpha(enabled)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AapsTheme.type.listTitle, color = colors.textOnSurfaceStrong)
            if (sub != null) Text(sub, style = AapsTheme.type.caption, color = colors.textTertiary)
        }
        trailing?.invoke()
    }
}

@ComponentPreviews
@Composable
private fun SelectionRowsPreview() = PreviewSurface {
    var site by remember { mutableStateOf(true) }
    var cartridge by remember { mutableStateOf(false) }
    var mode by remember { mutableIntStateOf(0) }
    ToggleRow("Pump site change", site, { site = it }, sub = "Record cannula change")
    ToggleRow("Remind me to eat", false, {}, enabled = false)
    CheckboxRow("Insulin cartridge change", cartridge, { cartridge = it })
    CheckboxRow("Fixed plugin", true, {}, sub = "Always on", enabled = false)
    Column(Modifier.selectableGroup()) {
        listOf("Closed loop", "Open loop").forEachIndexed { i, label ->
            RadioRow(label, mode == i, { mode = i }, sub = if (i == 0) "Doses automatically" else null)
        }
        RadioRow("Low glucose suspend", false, {}, enabled = false)
    }
}
