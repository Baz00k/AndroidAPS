package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme

/**
 * A tonal action-bar button (icon + label). Tint conveys role: accent tint for ordinary actions,
 * filled accent ([emphasized]) for the primary one. A disabled button keeps its place and label so
 * the bar does not reflow, but drops to control-fill.
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
    val bg = when {
        !enabled   -> colors.controlFill
        emphasized -> colors.accent
        else       -> container
    }
    val fg = when {
        !enabled   -> colors.textTertiary
        emphasized -> colors.onAccent
        else       -> content
    }
    Column(
        modifier = modifier
            .heightIn(min = 58.dp)
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

/** Round control-fill icon button (the "+" overflow in the action bar). */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .size(58.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(AapsTheme.colors.controlFill)
            .clickable(onClick = onClick, role = Role.Button),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = AapsTheme.colors.textPrimary, modifier = Modifier.size(24.dp))
    }
}
