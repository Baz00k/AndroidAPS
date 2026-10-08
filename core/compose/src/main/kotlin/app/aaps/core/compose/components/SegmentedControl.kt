package app.aaps.core.compose.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * Segmented control (e.g. graph range [3h][6h][12h], profile tabs). Active segment uses the accent
 * tint (interactive) — instant switch. Options are given as display labels.
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
    Row(
        modifier = modifier
            .clip(AapsTheme.shape.pill)
            .background(colors.controlFill)
            .padding(TRACK_INSET)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selectedIndex
            val enabled = i !in disabled
            val bg by animateColorAsState(if (active) colors.accentTintStrong else Color.Transparent, label = "seg-bg")
            Text(
                text = label,
                style = AapsTheme.type.label,
                color = if (active) colors.accentOnLight else colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = (if (fillWidth) Modifier.weight(1f) else Modifier)
                    // Inside the track's inset, so the whole control is at least a touch target tall.
                    .heightIn(min = AapsSpacing.minTap - TRACK_INSET * 2)
                    .disabledAlpha(enabled)
                    .clip(AapsTheme.shape.pill)
                    .selectable(selected = active, enabled = enabled, role = Role.Tab) { onSelect(i) }
                    .then(if (!enabled && disabledReason != null) Modifier.semantics { stateDescription = disabledReason } else Modifier)
                    .background(bg)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

private val TRACK_INSET = 3.dp

@ComponentPreviews
@Composable
private fun SegmentedControlPreview() = PreviewSurface {
    SegmentedControl(listOf("3h", "6h", "12h", "24h"), selectedIndex = 1, onSelect = {})
    SegmentedControl(listOf("Basal", "IC", "ISF", "Target"), selectedIndex = 0, onSelect = {}, fillWidth = true, disabled = setOf(3), disabledReason = "Not available")
}
