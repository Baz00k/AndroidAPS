package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/*
 * One button, five emphases. Pick by what the action is, not how it should look:
 *
 * - Primary: the one affirmative action of a sheet or dialog. At most one per surface.
 * - Secondary: a neutral alternative (cancel, refresh, a second option).
 * - Tonal: an ordinary action that should still read as an action, next to content.
 * - Danger: stops or removes something (stop a bolus, delete).
 * - Ghost: a low-emphasis inline action ("Remove", "Show all"); the text button.
 *
 * Primary, Secondary, Tonal and Danger are block buttons: they fill the width they are given, since
 * consequential actions here are full-width targets. Pass a weight to share a row. Ghost wraps its
 * label. The label names the action, so an [icon] is decorative and not announced.
 */

private enum class ButtonEmphasis { Primary, Secondary, Tonal, Danger, Ghost }

/** The sheet's or dialog's affirmative action, in solid accent. */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) =
    AapsButton(ButtonEmphasis.Primary, label, onClick, modifier, enabled, icon)

/** A neutral alternative to the primary action. */
@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) =
    AapsButton(ButtonEmphasis.Secondary, label, onClick, modifier, enabled, icon)

/** An ordinary action on an accent tint. */
@Composable
fun TonalButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) =
    AapsButton(ButtonEmphasis.Tonal, label, onClick, modifier, enabled, icon)

/** Stops or removes something, in the "low" red. */
@Composable
fun DangerButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) =
    AapsButton(ButtonEmphasis.Danger, label, onClick, modifier, enabled, icon)

/** A text-only button for low-emphasis inline actions. Sized to its label. */
@Composable
fun GhostButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) =
    AapsButton(ButtonEmphasis.Ghost, label, onClick, modifier, enabled, icon)

@Composable
private fun AapsButton(
    emphasis: ButtonEmphasis,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    icon: ImageVector?
) {
    val colors = AapsTheme.colors
    val (container, ink) = when (emphasis) {
        ButtonEmphasis.Primary   -> colors.accent to colors.onAccent
        ButtonEmphasis.Secondary -> colors.controlFill to colors.textPrimary
        ButtonEmphasis.Tonal     -> colors.accentTint to colors.accentOnLight
        ButtonEmphasis.Danger    -> colors.low.copy(alpha = DANGER_TINT_ALPHA) to colors.low
        ButtonEmphasis.Ghost     -> Color.Transparent to colors.accent
    }
    val block = emphasis != ButtonEmphasis.Ghost
    Row(
        modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = AapsSpacing.minTap)
            .widthIn(min = AapsSpacing.minTap)
            .disabledAlpha(enabled)
            .clip(if (block) AapsTheme.shape.button else AapsTheme.shape.pill)
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (block) 16.dp else 12.dp, vertical = if (block) 12.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        val style = if (block) AapsTheme.type.title else AapsTheme.type.listTitle
        FittedText(label, style.copy(textAlign = TextAlign.Center), ink, maxLines = 2)
    }
}

/** The red tint behind a danger button's label: the accent tint's strength, light enough to keep the red label at 4.5:1. */
private const val DANGER_TINT_ALPHA = 0.12f

@ComponentPreviews
@Composable
private fun ButtonsPreview() = PreviewSurface {
    PrimaryButton("Deliver 2.50 U", {})
    PrimaryButton("Nothing to confirm", {}, enabled = false)
    SecondaryButton("Refresh", {}, icon = Icons.Rounded.Add)
    TonalButton("Add a profile", {})
    DangerButton("Stop", {})
    Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
        SecondaryButton("Reset alarms", {}, Modifier.weight(1f))
        SecondaryButton("Refresh", {}, Modifier.weight(1f), enabled = false)
    }
    GhostButton("Remove", {})
}
