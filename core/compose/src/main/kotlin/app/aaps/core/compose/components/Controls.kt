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

/*
 * The contract every control in this library keeps, so a screen built from it is accessible without
 * extra work at the call site:
 *
 * - Colours, type and shapes come from AapsTheme only; a control never defines its own palette.
 * - Anything that can be pressed is at least AapsSpacing.minTap tall, and as wide unless it is a
 *   full-width row or button.
 * - Disabled is the whole control at DISABLED_ALPHA, and it is announced as disabled.
 * - Each control declares its Role and is one node for TalkBack: a row with a checkbox, radio or
 *   switch is one toggle with its title as the label, not a label next to a separate control.
 *   Something that only displays information, such as a Tag, has no click action at all.
 * - An input's label, unit and error belong to the input's own node, so they are read with it.
 * - Text is laid out for a 200% font scale: it wraps or shrinks (FittedText) rather than clipping.
 * - Text the library shows on its own comes from resources; everything else is passed in by the
 *   caller, which owns its wording.
 *
 * Each component keeps representative previews (light, dark, 200% font, key states) next to it.
 */

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
