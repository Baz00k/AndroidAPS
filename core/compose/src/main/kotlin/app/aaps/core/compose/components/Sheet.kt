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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * True while the surrounding host really lets the sheet be dragged away. The grabber is drawn only
 * then: a handle on something that cannot be dragged is a false affordance.
 */
val LocalSheetDraggable = staticCompositionLocalOf { false }

/**
 * Set by a host that drags the sheet by its header only, so scrolling a form never throws it away.
 * [SheetSurface] hands it the header (grabber and title row) to hit-test a touch against.
 */
val LocalSheetDragHandle = staticCompositionLocalOf<((LayoutCoordinates) -> Unit)?> { null }

/**
 * Set by a Compose host ([ModalSheet]) to the drag gesture that moves the sheet. [SheetSurface] puts
 * it on the header only, so nothing below the header can move the sheet.
 */
val LocalSheetHeaderDrag = compositionLocalOf<Modifier> { Modifier }

/**
 * Bottom-sheet surface: rounded-top panel with a grabber and a title row. Hosted by a native modal
 * bottom sheet ([LocalSheetDraggable] = true): `DaggerBottomSheetFragment` for a fragment, [ModalSheet]
 * inside a Compose screen. Blocking content (an alarm, a progress that must finish) sits in a plain
 * dialog instead, which cannot be dragged and so shows no grabber.
 *
 * The title row is the same on every sheet: [onBack] (a step back within the sheet) leads, [onClose]
 * trails. Together with the grabber it is the sheet's handle.
 *
 * With [scrollContent] the sheet owns the scrolling: [content] scrolls between the header and the
 * [footer], the sheet's action, which stays on screen however long the content grows. The content
 * must not scroll itself, and the host must bound the sheet's height (both sheet hosts and a dialog
 * window do). A footer implies this mode.
 *
 * Without it (the transitional mode, until #155 migrates the last callers) the content is laid out
 * as given and the caller scrolls it itself; there is no footer.
 */
@Composable
fun SheetSurface(
    title: String,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    scrollContent: Boolean = footer != null,
    content: @Composable () -> Unit
) {
    require(footer == null || scrollContent) { "A sheet footer is pinned below content the sheet scrolls" }
    val colors = AapsTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(AapsTheme.shape.sheet)
            .background(colors.surface3)
            .padding(bottom = 12.dp)
    ) {
        val dragHandle = LocalSheetDragHandle.current
        Column(
            (if (dragHandle != null) Modifier.onGloballyPositioned(dragHandle) else Modifier)
                .then(LocalSheetHeaderDrag.current)
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
                    // The icon buttons reach the sheet's edges so their glyphs, not their touch
                    // targets, sit on the same keyline as the title and the content below.
                    .padding(start = if (onBack != null) 0.dp else AapsSpacing.screenH, end = if (onClose != null) 0.dp else AapsSpacing.screenH)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) SheetIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
                Text(title, style = AapsTheme.type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                if (onClose != null) SheetIconButton(Icons.Rounded.Close, "Close", onClose)
            }
        }
        if (!scrollContent)
            Column(Modifier.padding(horizontal = AapsSpacing.screenH), verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) {
                content()
            }
        else {
            // Takes only the height left over by the header and the footer, so a long body scrolls
            // instead of pushing the action off screen.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AapsSpacing.screenH)
                    .padding(bottom = if (footer != null) 12.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)
            ) { content() }
            if (footer != null) {
                // A hairline where the scrolling cards pass under the pinned action.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(AapsSpacing.hairlineWidth)
                        .background(colors.hairline)
                )
                Box(Modifier.padding(start = AapsSpacing.screenH, end = AapsSpacing.screenH, top = 12.dp)) { footer() }
            }
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

@ComponentPreviews
@Composable
private fun SheetSurfacePreview() = PreviewSurface {
    // The sheet scrolls a body taller than its host and keeps the footer on screen.
    Box(Modifier.height(360.dp)) {
        SheetSurface(title = "Temp basal", onClose = {}, footer = { PrimaryButton("Set temp basal", {}) }) {
            repeat(8) { Text("Row ${it + 1}", style = AapsTheme.type.body, color = AapsTheme.colors.textPrimary) }
        }
    }
}
