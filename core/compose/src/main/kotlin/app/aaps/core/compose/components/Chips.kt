package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/*
 * Three kinds of pill, told apart by what they do rather than how they look:
 *
 * - Choice: one option of a set. It has a selected state and is announced as a radio button.
 * - ActionChip: does something at once (+5 g, suspend for 1 h). It is never "selected".
 * - Tag: says something about an item. It cannot be pressed, so it is not announced as a disabled
 *   action either.
 *
 * Choice and ActionChip are sized to their label; give them a weight to share a [ChoiceRow].
 */

/** A row of quick options with the standard gap; give each one `Modifier.weight(1f)` to make them equally wide. */
@Composable
fun ChoiceRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** One option of a set, such as a profile, a duration or a filter. Selecting it again keeps it selected. */
@Composable
fun Choice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) = PillControl(
    label = label,
    selected = selected,
    enabled = enabled,
    icon = icon,
    interaction = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    modifier = modifier
)

/**
 * A quick action, such as adding a preset amount. [clickLabel] says what pressing it does when the
 * label alone does not ("Add 5 grams of carbs" for "+5 g").
 */
@Composable
fun ActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    clickLabel: String? = null
) = PillControl(
    label = label,
    selected = false,
    enabled = enabled,
    icon = icon,
    interaction = Modifier.clickable(enabled = enabled, role = Role.Button, onClickLabel = clickLabel, onClick = onClick),
    modifier = modifier
)

/** A short passive status word on a pill tinted with [tint], such as "Stale" or "Max bolus". */
@Composable
fun Tag(label: String, modifier: Modifier = Modifier, tint: Color = AapsTheme.colors.textSecondary) =
    Text(
        label,
        style = AapsTheme.type.label,
        color = tint,
        modifier = modifier
            .clip(AapsTheme.shape.pill)
            .background(tint.copy(alpha = TAG_TINT_ALPHA))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )

private const val TAG_TINT_ALPHA = 0.14f

@Composable
private fun PillControl(label: String, selected: Boolean, enabled: Boolean, icon: ImageVector?, interaction: Modifier, modifier: Modifier) {
    val colors = AapsTheme.colors
    val ink = if (selected) colors.accentOnLight else colors.textPrimary
    Row(
        modifier
            .heightIn(min = AapsSpacing.minTap)
            .widthIn(min = AapsSpacing.minTap)
            .disabledAlpha(enabled)
            .clip(AapsTheme.shape.pill)
            .background(if (selected) colors.accentTintStrong else colors.controlFill)
            .then(interaction)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        FittedText(
            label,
            AapsTheme.type.body.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
            ink,
            maxLines = 2
        )
    }
}

@ComponentPreviews
@Composable
private fun ChipsPreview() = PreviewSurface {
    ChoiceRow {
        Choice("None", selected = false, onClick = {}, modifier = Modifier.weight(1f))
        Choice("Eating soon", selected = true, onClick = {}, modifier = Modifier.weight(1f))
        Choice("Activity", selected = false, onClick = {}, modifier = Modifier.weight(1f), enabled = false)
    }
    ChoiceRow {
        ActionChip("+5 g", {}, Modifier.weight(1f), icon = Icons.Rounded.Add)
        ActionChip("+10 g", {}, Modifier.weight(1f))
        ActionChip("+20 g", {}, Modifier.weight(1f), enabled = false)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall), verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall)) {
        Choice("LocalProfile1", selected = true, onClick = {})
        Choice("Weekend", selected = false, onClick = {})
    }
    Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall)) {
        Tag("Stale", tint = AapsTheme.colors.high)
        Tag("Max bolus", tint = AapsTheme.colors.low)
        Tag("Glucose > 180")
    }
}
