package app.aaps.plugins.source

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.plugins.source.compose.Libre3SensorScreen
import dagger.android.support.DaggerFragment
import javax.inject.Inject

/**
 * Host for [Libre3SensorScreen]. State comes straight from the plugin's flow, so the screen has
 * no state of its own to drift.
 *
 * Both destructive actions confirm through `OKDialog` — the same path every other irreversible
 * action in AAPS uses, rather than a bespoke dialog that might be dismissed differently.
 */
class Libre3SensorFragment : DaggerFragment() {

    @Inject lateinit var libre3SourcePlugin: Libre3SourcePlugin

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AapsTheme {
                    val state by libre3SourcePlugin.sensorState.collectAsState()
                    Libre3SensorScreen(
                        state = state,
                        onStartNewSensor = {
                            // Tier 3 lands here. Until the NFC response parser exists there is
                            // nothing safe to run, and silently doing nothing would be worse than
                            // saying so.
                            OKDialog.show(
                                requireContext(),
                                rh.gs(R.string.source_libre3),
                                "Starting a sensor from AAPS is not enabled yet. Activate with " +
                                    "Juggluco and import the credentials."
                            )
                        },
                        onStopSensor = {
                            OKDialog.showConfirmation(
                                requireActivity(),
                                "Stop this sensor? It cannot be restarted."
                            ) { libre3SourcePlugin.stopSensor() }
                        },
                        onForgetSensor = {
                            OKDialog.showConfirmation(
                                requireActivity(),
                                "Forget this sensor? AAPS will no longer connect to it."
                            ) { libre3SourcePlugin.forgetSensor() }
                        }
                    )
                }
            }
        }

    @Inject lateinit var rh: app.aaps.core.interfaces.resources.ResourceHelper
}
