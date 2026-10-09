package app.aaps.core.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.components.LocalSheetDragHandle
import app.aaps.core.compose.components.LocalSheetDraggable
import app.aaps.core.compose.theme.AapsTheme
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasAndroidInjector
import dagger.android.support.AndroidSupportInjection
import javax.inject.Inject

/**
 * Host for the app's Compose sheets as a native modal bottom sheet: it slides up and is dismissed by
 * dragging its header down, the scrim or Back. Only the header drags: these sheets are forms, and a
 * scroll or a swipe through the cards must never throw an entry away. A fragment that sets
 * [isCancelable] to false gets a sheet that cannot be dragged away and shows no grabber.
 */
abstract class DaggerBottomSheetFragment : BottomSheetDialogFragment(), HasAndroidInjector {

    @Inject lateinit var androidInjector: DispatchingAndroidInjector<Any>

    override fun androidInjector(): AndroidInjector<Any> = androidInjector

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    /** Where the sheet's header was last laid out; a drag may start only there. */
    private var dragHandle: LayoutCoordinates? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        object : BottomSheetDialog(requireContext(), theme) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                // Decided as the gesture starts, before the sheet's behaviour sees it: a drag that
                // begins anywhere but the header is the content's to handle.
                if (ev.actionMasked == MotionEvent.ACTION_DOWN)
                    behavior.isDraggable = isCancelable && dragHandle?.takeIf { it.isAttached }?.boundsInWindow()?.contains(Offset(ev.x, ev.y)) == true
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            // A form is either open or gone: no half-open peek state to drag through.
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            // Material's View-based BottomSheetDialog still uses resize for IME handling.
            // Preserve it until a window-insets migration is verified across the therapy forms.
            @Suppress("DEPRECATION")
            val inputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            window?.setSoftInputMode(inputMode)
        }

    override fun onStart() {
        super.onStart()
        // The Compose surface draws its own rounded corners; the platform container must not paint behind them.
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
    }

    /** The sheet's Compose content. Its scrolling never drags the sheet; the header is the drag handle. */
    protected fun sheetContent(content: @Composable () -> Unit): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AapsTheme {
                CompositionLocalProvider(
                    LocalSheetDraggable provides isCancelable,
                    LocalSheetDragHandle provides { dragHandle = it }
                ) { content() }
            }
        }
    }
}
