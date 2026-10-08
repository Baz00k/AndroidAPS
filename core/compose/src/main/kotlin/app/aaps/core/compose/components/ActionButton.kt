package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * A tonal action-bar button (icon + label). Tint conveys role: accent tint for ordinary actions,
 * filled accent ([emphasized]) for the primary one. A disabled button keeps its place, label and
 * colour so the bar does not reflow, at the shared disabled opacity.
 *
 * The label shrinks before it clips (large font scales, narrow windows); the bar grows taller rather
 * than cutting the icon off.
 */
@Composable
fun ActionBarButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = AapsTheme.colors.accentTint,
    content: Color = AapsTheme.colors.accentOnLight,
    emphasized: Boolean = false,
    enabled: Boolean = true
) {
    val colors = AapsTheme.colors
    val bg = if (emphasized) colors.accent else container
    val fg = if (emphasized) colors.onAccent else content
    Column(
        modifier = modifier
            .heightIn(min = 58.dp)
            .disabledAlpha(enabled)
            .clip(AapsTheme.shape.button)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // The label below names the action; a description here would be read twice.
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        FittedText(label, AapsTheme.type.label, fg)
    }
}

/** How strongly a [RoundIconButton] reads: neutral fill, accent tint, or a red icon for removing something. */
enum class IconButtonTone { Neutral, Tonal, Danger }

/**
 * A round button showing only an icon, so [contentDescription] is required: it is the button's
 * name for screen readers. At least a touch target in size; the Home action bar passes its own
 * height to line up with [ActionBarButton].
 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: IconButtonTone = IconButtonTone.Neutral,
    enabled: Boolean = true,
    size: Dp = AapsSpacing.minTap
) {
    val colors = AapsTheme.colors
    val (container, ink) = when (tone) {
        IconButtonTone.Neutral -> colors.controlFill to colors.textPrimary
        IconButtonTone.Tonal   -> colors.accentTint to colors.accentOnLight
        IconButtonTone.Danger  -> colors.controlFill to colors.low
    }
    Box(
        modifier = modifier
            .size(size.coerceAtLeast(AapsSpacing.minTap))
            .disabledAlpha(enabled)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = ink, modifier = Modifier.size(if (size > 48.dp) 24.dp else 20.dp))
    }
}

@ComponentPreviews
@Composable
private fun ActionButtonsPreview() = PreviewSurface {
    Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall)) {
        ActionBarButton("Insulin", Icons.Rounded.Add, {}, Modifier.weight(1f), emphasized = true)
        ActionBarButton("Carbs", Icons.Rounded.Add, {}, Modifier.weight(1f))
        ActionBarButton("Calculator", Icons.Rounded.Add, {}, Modifier.weight(1f), enabled = false)
        RoundIconButton(Icons.Rounded.Add, "More actions", {}, size = 58.dp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall)) {
        RoundIconButton(Icons.Rounded.Add, "Add rule", {}, tone = IconButtonTone.Tonal)
        RoundIconButton(Icons.Rounded.Close, "Remove block", {}, tone = IconButtonTone.Danger)
        RoundIconButton(Icons.Rounded.Add, "Add", {}, enabled = false)
    }
}
