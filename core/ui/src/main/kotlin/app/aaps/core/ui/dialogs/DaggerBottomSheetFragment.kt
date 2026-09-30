package app.aaps.core.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
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
 * Host for the app's Compose sheets as a native modal bottom sheet: it slides up, follows a drag and
 * is dismissed by dragging it down or tapping the scrim, like every other sheet on the platform. A
 * fragment that sets [isCancelable] to false gets a sheet that cannot be dragged away and shows no grabber.
 */
abstract class DaggerBottomSheetFragment : BottomSheetDialogFragment(), HasAndroidInjector {

    @Inject lateinit var androidInjector: DispatchingAndroidInjector<Any>

    override fun androidInjector(): AndroidInjector<Any> = androidInjector

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            // A form is either open or gone: no half-open peek state to drag through.
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        }

    override fun onStart() {
        super.onStart()
        // The Compose surface draws its own rounded corners; the platform container must not paint behind them.
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
    }

    /** The sheet's Compose content, with scrolling handed to the sheet so a drag at the top dismisses it. */
    protected fun sheetContent(content: @Composable () -> Unit): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AapsTheme {
                CompositionLocalProvider(LocalSheetDraggable provides isCancelable) {
                    Box(Modifier.nestedScroll(rememberNestedScrollInteropConnection())) { content() }
                }
            }
        }
    }
}
