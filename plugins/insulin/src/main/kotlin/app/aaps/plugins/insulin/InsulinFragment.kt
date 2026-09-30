package app.aaps.plugins.insulin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.plugins.insulin.compose.InsulinScreen
import app.aaps.plugins.insulin.compose.InsulinUiState
import app.aaps.plugins.insulin.compose.buildInsulinActivityCurve
import dagger.android.support.DaggerFragment
import javax.inject.Inject

class InsulinFragment : DaggerFragment() {

    @Inject lateinit var activePlugin: ActivePlugin

    private val state = mutableStateOf(InsulinUiState())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // ViewPager can display this page while it is still STARTED, before onResume refreshes it.
        build()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { InsulinScreen(state.value) } }
        }
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val active = activePlugin.activeInsulin
        val diaHours = active.dia
        val activityCurve = buildInsulinActivityCurve(active, diaHours)
        state.value = InsulinUiState(
            activeName = active.friendlyName,
            comment = active.comment,
            diaHours = diaHours,
            peakMinutes = activityCurve?.peak?.minutes?.toInt() ?: active.peak,
            activityCurve = activityCurve
        )
    }
}
