package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * True while the surrounding host really lets the sheet be dragged away. The grabber is drawn only
 * then: a handle on something that cannot be dragged is a false affordance.
 */
val LocalSheetDraggable = staticCompositionLocalOf { false }

/**
 * Bottom-sheet surface: rounded-top panel with a grabber and a title row. Hosted by a native modal
 * bottom sheet ([LocalSheetDraggable] = true) or, for blocking content, a plain dialog.
 *
 * The title row is the same on every sheet: [onBack] (a step back within the sheet) leads, [onClose]
 * trails. With a [footer] the content scrolls on its own and the footer, the sheet's action, stays
 * on screen; without one the caller lays out (and scrolls) everything itself.
 */
@Composable
fun SheetSurface(
    title: String,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val colors = AapsTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(AapsTheme.shape.sheet)
            .background(colors.surface3)
            .padding(bottom = 12.dp)
    ) {
        if (LocalSheetDraggable.current)
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        // Text ink, not a fixed white: the grabber has to show on a light sheet too.
                        .background(colors.textTertiary.copy(alpha = 0.5f))
                )
            }
        else Box(Modifier.padding(top = 8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = if (onBack != null) 4.dp else AapsSpacing.screenH, end = 4.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) SheetIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Text(title, style = AapsTheme.type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
            if (onClose != null) SheetIconButton(Icons.Rounded.Close, "Close", onClose)
        }
        if (footer == null)
            Column(Modifier.padding(horizontal = AapsSpacing.screenH), verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) {
                content()
            }
        else {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AapsSpacing.screenH)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)
            ) { content() }
            Box(Modifier.padding(horizontal = AapsSpacing.screenH)) { footer() }
        }
    }
}

@Composable
private fun SheetIconButton(icon: ImageVector, description: String, onClick: () -> Unit) =
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = description, tint = AapsTheme.colors.textSecondary) }

/** A rounded chip. [selected] fills accent-tint; otherwise control-fill. */
@Composable
fun Chip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true
) {
    val colors = AapsTheme.colors
    val bg = if (selected) colors.accentTintStrong else colors.controlFill
    val fg = if (selected) colors.accentOnLight else colors.textPrimary
    Text(
        label,
        style = AapsTheme.type.listTitle,
        color = fg,
        modifier = modifier
            .disabledAlpha(enabled)
            .clip(AapsTheme.shape.pill)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}
