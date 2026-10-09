package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import app.aaps.core.compose.theme.AapsTheme

/**
 * Status pill: a colored dot (glucose/status color) + label + optional bold value, on a flat
 * control-fill background. This is a *readout*, so it stays flat (no accent). With [onClick] it is
 * also a button with a full touch target (the Home loop pill). Used for the supplies strip too.
 */
@Composable
fun StatusPill(
    label: String,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    value: String? = null,
    labelColor: Color = AapsTheme.colors.textSecondary,
    glow: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val colors = AapsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            // A tappable pill keeps a full touch target around it but is drawn no larger than a
            // read-only one, so a status reads as a status rather than as a big button.
            .then(
                if (onClick != null) Modifier
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                    .minimumInteractiveComponentSize()
                else Modifier
            )
            .clip(AapsTheme.shape.pill)
            .then(if (onClick != null) Modifier.indication(interaction, ripple()) else Modifier)
            .background(colors.controlFill)
            .heightIn(min = 32.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (dotColor != null) Dot(dotColor, glow)
        Text(label, style = AapsTheme.type.caption, color = labelColor)
        if (value != null) Text(value, style = AapsTheme.type.listTitle, color = colors.textPrimary)
    }
}

/** A colored status dot, optionally with a soft glow (for the "looping" indicator). */
@Composable
fun Dot(color: Color, glow: Boolean = false, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(contentAlignment = Alignment.Center) {
        if (glow) Box(
            Modifier
                .size(size + 8.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.22f))
        )
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@ComponentPreviews
@Composable
private fun PillsPreview() = PreviewSurface {
    StatusPill("Closed loop  · 4m ago", dotColor = AapsTheme.colors.inRange, glow = true, labelColor = AapsTheme.colors.textPrimary, onClick = {})
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusPill("Sensor", dotColor = AapsTheme.colors.high, value = "6d")
        StatusPill("Reservoir", dotColor = AapsTheme.colors.inRange, value = "88 U")
    }
}
