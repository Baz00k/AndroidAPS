package app.aaps.core.compose.components

import app.aaps.core.compose.icons.AapsIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * Base surface card: a Material filled card, no elevation. Its fill is what separates it from the
 * screen, so it draws a hairline only where the fill cannot — a card the colour of the screen
 * behind it, as on a true-black ground, would otherwise vanish.
 * Pure readout by default; pass [onClick] to make it interactive.
 */
@Composable
fun AapsCard(
    modifier: Modifier = Modifier,
    shape: Shape = AapsTheme.shape.card,
    color: Color = AapsTheme.colors.surface,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(AapsSpacing.cardPad),
    content: @Composable () -> Unit
) {
    val colors = AapsTheme.colors
    Card(
        modifier = if (onClick != null) modifier.clip(shape).clickable(role = Role.Button, onClick = onClick) else modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = color),
        border = if (color == colors.background) BorderStroke(1.dp, colors.hairline) else null
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/** Where a row inside a [ListCard] keeps its content, so the row itself can reach the card's edges. */
@Immutable
internal data class ListRowFrame(val inset: Dp, val minHeight: Dp, val vertical: Dp)

/** Set by [ListCard]; null elsewhere, where rows keep their own compact defaults. */
internal val LocalListRowFrame = staticCompositionLocalOf<ListRowFrame?> { null }

/**
 * A card holding a list of rows ([ListRow], [ToggleRow], [RadioRow], [CheckboxRow]), as grouped
 * lists are drawn in current Android settings: each row spans the card, so its press highlight does
 * too, and rows are separated by their own height rather than by divider lines. Any other content
 * placed in it pads itself by [AapsSpacing.cardPad] horizontally.
 */
@Composable
fun ListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    AapsCard(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        CompositionLocalProvider(LocalListRowFrame provides ListRowFrame(inset = AapsSpacing.cardPad, minHeight = 56.dp, vertical = 8.dp)) {
            Column(content = content)
        }
    }

/**
 * Compact stat card: uppercase label, big value, optional sub, chevron when tappable.
 * Used for the Home IOB / COB / Basal trio.
 */
@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    valueColor: Color = AapsTheme.colors.textPrimary,
    onClick: (() -> Unit)? = null
) {
    val colors = AapsTheme.colors
    AapsCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = AapsSpacing.cardPadSmall, vertical = AapsSpacing.cardPadSmall)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = AapsTheme.type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                if (onClick != null) Chevron()
            }
            Text(value, style = AapsTheme.type.cardValue, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = AapsTheme.type.caption, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun Chevron() {
    Icon(
        imageVector = AapsIcons.ChevronRight,
        contentDescription = null,
        tint = AapsTheme.colors.textTertiary,
        modifier = Modifier.size(16.dp)
    )
}

/** Tinted rounded-square icon holder for list rows (leading-icon grammar). */
@Composable
fun TintIcon(
    icon: ImageVector,
    contentDescription: String? = null,
    tint: Color = AapsTheme.colors.accent,
    background: Color = AapsTheme.colors.accentTint,
    size: Dp = 36.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(AapsTheme.shape.iconButton)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}
