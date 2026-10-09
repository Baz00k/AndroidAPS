package app.aaps.core.compose.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme

/**
 * Segmented control (e.g. graph range [3h][6h][12h], profile tabs). Active segment uses the accent
 * tint (interactive) — instant switch. Options are given as display labels.
 *
 * Drawn at Material's segmented-button height, while each segment still takes a full touch target:
 * the track sits centred in the taller row it is pressed in, so it reads as a control rather than a
 * second row of buttons.
 *
 * [fillWidth] shares the full width equally between segments. A segment in [disabled] keeps its place
 * but cannot be chosen; [disabledReason] is announced for it.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    disabled: Set<Int> = emptySet(),
    disabledReason: String? = null
) {
    val colors = AapsTheme.colors
    val shape = AapsTheme.shape.pill
    // The tallest segment as drawn, so the track still holds a label that wraps at a large font size.
    val drawnHeights = remember { mutableStateMapOf<Int, Int>() }
    Row(
        modifier = modifier
            // The track is only the visible band; the row around it is as tall as a touch target.
            .drawWithCache {
                // Only current segments count, so a shorter set of options shrinks the track back.
                val tallest = (options.indices.mapNotNull { drawnHeights[it] }.maxOrNull() ?: 0).toFloat()
                val band = maxOf((SEGMENT_HEIGHT + TRACK_INSET * 2).toPx(), tallest + (TRACK_INSET * 2).toPx()).coerceAtMost(size.height)
                val outline = shape.createOutline(Size(size.width, band), layoutDirection, this)
                onDrawBehind { translate(top = (size.height - band) / 2) { drawOutline(outline, colors.controlFill) } }
            }
            .padding(horizontal = TRACK_INSET)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selectedIndex
            val enabled = i !in disabled
            val bg by animateColorAsState(if (active) colors.accentTintStrong else Color.Transparent, label = "seg-bg")
            val interaction = remember { MutableInteractionSource() }
            Box(
                (if (fillWidth) Modifier.weight(1f) else Modifier)
                    .selectable(selected = active, enabled = enabled, role = Role.Tab, interactionSource = interaction, indication = null) { onSelect(i) }
                    .then(if (!enabled && disabledReason != null) Modifier.semantics { stateDescription = disabledReason } else Modifier)
                    .minimumInteractiveComponentSize()
                    // Room for the track's inset above and below a segment taller than the touch target.
                    .padding(vertical = TRACK_INSET)
                    .disabledAlpha(enabled),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = AapsTheme.type.label,
                    color = if (active) colors.accentOnLight else colors.textSecondary,
                    textAlign = TextAlign.Center,
                    // As wide as its touch target, so short labels ("6h") still make even segments.
                    modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier.widthIn(min = 48.dp))
                        .heightIn(min = SEGMENT_HEIGHT)
                        .onSizeChanged { drawnHeights[i] = it.height }
                        .clip(shape)
                        .indication(interaction, ripple())
                        .background(bg)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 14.dp)
                )
            }
        }
    }
}

private val SEGMENT_HEIGHT = 32.dp
private val TRACK_INSET = 4.dp

@ComponentPreviews
@Composable
private fun SegmentedControlPreview() = PreviewSurface {
    SegmentedControl(listOf("3h", "6h", "12h", "24h"), selectedIndex = 1, onSelect = {})
    SegmentedControl(listOf("Basal", "IC", "ISF", "Target"), selectedIndex = 0, onSelect = {}, fillWidth = true, disabled = setOf(3), disabledReason = "Not available")
}
