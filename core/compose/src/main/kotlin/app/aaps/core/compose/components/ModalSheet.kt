package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.aaps.core.compose.theme.AapsTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Modal bottom sheet host for a [SheetSurface] inside a Compose screen, for places that are not a
 * `DaggerBottomSheetFragment`. It behaves like that host: the sheet slides up and is dismissed by
 * dragging its header down, the scrim or Back, and the only grabber is the surface's own. The drag is
 * attached to the header and nowhere else, and only once the sheet is up, so a tap, a drag or a scroll in
 * the content never moves the sheet, even while it is still sliding. For accessibility services the
 * scrim is a "Close sheet" button and the sheet offers a dismiss action, both through the same close.
 *
 * [content] receives `close`, which slides the sheet away and then runs what comes next, so an
 * action's own dialog does not open under a closing sheet. Every way out (an action, Back, the scrim,
 * a header drag) goes through that one close: the first counts, later ones do nothing, and nothing can
 * interrupt the slide. [onDismissed] is called once, then the action. If the sheet leaves composition
 * before it is gone, neither runs.
 */
@Composable
fun ModalSheet(onDismissed: () -> Unit, content: @Composable (close: (after: () -> Unit) -> Unit) -> Unit) {
    val position = remember { AnchoredDraggableState(SheetPosition.Hidden) }
    val scope = rememberCoroutineScope()
    val currentOnDismissed by rememberUpdatedState(onDismissed)
    var closing by remember { mutableStateOf(false) }
    // The header drags only once the sheet is up: a drag would cut the opening slide short.
    var opened by remember { mutableStateOf(false) }
    val close: (() -> Unit) -> Unit = { after ->
        if (!closing) {
            closing = true
            scope.launch {
                position.slideAway()
                currentOnDismissed()
                after()
            }
        }
    }
    LaunchedEffect(position) {
        // Slide up once the sheet has been measured, then watch for a header drag that let it go.
        snapshotFlow { position.anchors.size }.first { it > 0 }
        position.animateTo(SheetPosition.Open)
        opened = true
        snapshotFlow { position.settledValue }.first { it == SheetPosition.Hidden }
        close {}
    }

    Dialog(
        onDismissRequest = { close {} },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)
    ) {
        // The sheet draws its own scrim, which follows the drag; the window must not dim on top of it.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        val scrim = AapsTheme.colors.scrim
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(scrim, alpha = position.shown()) }
                    .pointerInput(Unit) { detectTapGestures { close {} } }
                    // Read last, after the sheet, as Material's scrim is.
                    .semantics {
                        traversalIndex = 1f
                        contentDescription = "Close sheet"
                        onClick { close {}; true }
                    }
            )
            // Above the scrim. The sheet takes every touch inside it, so one that no control in the
            // content claims (plain text, padding) does not fall through to the scrim.
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { size ->
                            // A resize keeps the sheet heading where it was going (up, back, or away).
                            position.updateAnchors(
                                DraggableAnchors {
                                    SheetPosition.Open at 0f
                                    SheetPosition.Hidden at size.height.toFloat()
                                },
                                position.targetValue
                            )
                        }
                        // Until it is measured the sheet sits one full height down, out of sight.
                        .graphicsLayer { translationY = position.offset.takeUnless { it.isNaN() } ?: size.height }
                        .clip(AapsTheme.shape.sheet)
                        .background(AapsTheme.colors.surface3)
                        .pointerInput(Unit) {}
                        .semantics {
                            paneTitle = "Sheet"
                            dismiss { close {}; true }
                        }
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                ) {
                    CompositionLocalProvider(
                        LocalSheetDraggable provides true,
                        LocalSheetHeaderDrag provides Modifier.anchoredDraggable(position, Orientation.Vertical, enabled = opened && !closing)
                    ) { content(close) }
                }
            }
        }
    }
}

private enum class SheetPosition { Open, Hidden }

/** How far the sheet is up, from 0 (hidden) to 1 (open); the scrim's strength. */
private fun AnchoredDraggableState<SheetPosition>.shown(): Float {
    val hidden = anchors.positionOf(SheetPosition.Hidden)
    if (offset.isNaN() || hidden.isNaN() || hidden <= 0f) return 0f
    return (1f - offset / hidden).coerceIn(0f, 1f)
}

/**
 * A drag in progress outranks this slide and would cancel it. Drags are switched off while the sheet
 * closes, so one that was already under way ends at once; slide again on the next frame.
 */
private suspend fun AnchoredDraggableState<SheetPosition>.slideAway() {
    while (true) {
        try {
            animateTo(SheetPosition.Hidden)
            return
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            withFrameNanos { }
        }
    }
}
