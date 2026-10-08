package app.aaps.core.compose.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Velocity
import app.aaps.core.compose.theme.AapsTheme
import kotlinx.coroutines.launch

/**
 * Modal bottom sheet host for a [SheetSurface] inside a Compose screen, for places that are not a
 * `DaggerBottomSheetFragment`. It behaves like that host: the sheet slides up and is dismissed by
 * dragging its header down, the scrim or Back, and the only grabber is the surface's own. A drag or a
 * scroll that starts below the header belongs to the content, so scrolling a form never throws it away.
 *
 * [content] receives `close`, which slides the sheet away and then runs what comes next, so an
 * action's own dialog does not open under a closing sheet. The sheet closes once: a second close, or a
 * Back press during the slide, does nothing. If the sheet leaves composition before it is gone, the
 * action does not run. [onDismissed] is called once, however the sheet went away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalSheet(onDismissed: () -> Unit, content: @Composable (close: (after: () -> Unit) -> Unit) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val close: (() -> Unit) -> Unit = { after ->
        if (!closing) {
            closing = true
            scope.launch {
                sheetState.hide()
                onDismissed()
                after()
            }
        }
    }
    val grip = remember { HeaderGrip() }
    ModalBottomSheet(
        onDismissRequest = {
            if (!closing) {
                closing = true
                onDismissed()
            }
        },
        sheetState = sheetState,
        containerColor = AapsTheme.colors.surface3,
        shape = AapsTheme.shape.sheet,
        scrimColor = AapsTheme.colors.scrim,
        // SheetSurface draws the grabber, over the header it belongs to.
        dragHandle = null
    ) {
        CompositionLocalProvider(
            LocalSheetDraggable provides true,
            LocalSheetDragHandle provides { grip.header = it }
        ) {
            Box(grip.modifier) { content(close) }
        }
    }
}

/**
 * Keeps the sheet's own drag to its header. The bottom sheet drags from anywhere and also follows
 * content that is pulled past its top. Both reach the sheet only after the content has passed on
 * them, so the content can keep them first.
 */
private class HeaderGrip {

    /** Where the surface's header was last laid out. */
    var header: LayoutCoordinates? = null
    private var body: LayoutCoordinates? = null

    private fun onHeader(position: Offset): Boolean {
        val header = header?.takeIf { it.isAttached } ?: return false
        val body = body?.takeIf { it.isAttached } ?: return false
        return body.localBoundingBoxOf(header).contains(position)
    }

    /** Scrolling the content to its top and on does not pull the sheet down. */
    private val keepOverscroll = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
    }

    val modifier: Modifier = Modifier
        .onGloballyPositioned { body = it }
        .nestedScroll(keepOverscroll)
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (onHeader(down.position)) return@awaitEachGesture
                // A drag the content left unclaimed (a tap stays a tap, a scroller takes its own) is
                // claimed here once it passes touch slop. The sheet, further out, then sees it consumed.
                val drag = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                drag(drag.id) { it.consume() }
            }
        }
}
