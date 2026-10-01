package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.icons.AapsIcons
import app.aaps.core.compose.theme.AapsTheme

/**
 * How every disabled control looks: the whole control, container and ink together, at Material's
 * disabled opacity. A greyer label alone is too subtle to read as "not available".
 */
const val DISABLED_ALPHA = 0.38f

fun Modifier.disabledAlpha(enabled: Boolean): Modifier = if (enabled) this else alpha(DISABLED_ALPHA)

/** The − or + of every stepper. Both share one tonal style; at a limit the button is disabled. */
@Composable
fun StepButton(
    plus: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Dp = 48.dp
) {
    val colors = AapsTheme.colors
    Box(
        Modifier
            .size(size)
            .disabledAlpha(enabled)
            .clip(CircleShape)
            .background(colors.accentTint)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (plus) Icons.Rounded.Add else AapsIcons.Remove,
            contentDescription = contentDescription,
            tint = colors.accentOnLight,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Switch colours on the app palette, including the disabled state the Material defaults would pick. */
@Composable
fun aapsSwitchColors(): SwitchColors {
    val colors = AapsTheme.colors
    return SwitchDefaults.colors(
        checkedThumbColor = colors.onAccent,
        checkedTrackColor = colors.accent,
        checkedBorderColor = colors.accent,
        uncheckedTrackColor = colors.controlFill,
        uncheckedThumbColor = colors.textSecondary,
        uncheckedBorderColor = colors.hairline,
        disabledCheckedThumbColor = colors.onAccent.copy(alpha = DISABLED_ALPHA),
        disabledCheckedTrackColor = colors.accent.copy(alpha = DISABLED_ALPHA),
        disabledCheckedBorderColor = colors.accent.copy(alpha = 0f),
        disabledUncheckedTrackColor = colors.controlFill,
        disabledUncheckedThumbColor = colors.textSecondary.copy(alpha = DISABLED_ALPHA),
        disabledUncheckedBorderColor = colors.hairline
    )
}
