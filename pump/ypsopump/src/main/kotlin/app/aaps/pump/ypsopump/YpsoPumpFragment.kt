package app.aaps.pump.ypsopump

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.compose.PumpStatusRow
import app.aaps.pump.ypsopump.compose.PumpStatusScreen
import app.aaps.pump.ypsopump.compose.PumpStatusState
import app.aaps.pump.ypsopump.compose.QueueItem
import app.aaps.pump.ypsopump.data.YpsoPumpState
import dagger.android.support.DaggerFragment
import javax.inject.Inject

/**
 * YpsoPump driver tab — redesigned as a Compose status screen (connection pill, Reservoir/Battery
 * gauges, status rows, command-queue). Read-only view over [YpsoPumpState] + [CommandQueue].
 */
class YpsoPumpFragment : DaggerFragment() {

    @Inject lateinit var pumpState: YpsoPumpState
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var rh: ResourceHelper

    private val state = mutableStateOf(PumpStatusState())
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            build()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { PumpStatusScreen(state.value) } }
        }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun build() {
        val rows = buildList {
            if (pumpState.serialNumber.isNotEmpty()) add(PumpStatusRow(rh.gs(R.string.ypsopump_serial), pumpState.serialNumber))
            if (pumpState.firmwareVersion.isNotEmpty()) add(PumpStatusRow(rh.gs(R.string.ypsopump_firmware), pumpState.firmwareVersion))
            if (pumpState.lastConnectionTime > 0) {
                add(PumpStatusRow(rh.gs(R.string.ypsopump_last_status), dateUtil.minOrSecAgo(rh, pumpState.lastConnectionTime)))
            }
            if (pumpState.lastErrorCode != 0) {
                add(PumpStatusRow(rh.gs(R.string.ypsopump_last_error), "${pumpState.lastErrorCode} — ${pumpState.lastErrorMessage}"))
            }
        }
        val queue = buildList {
            val running = commandQueue.performing()
            if (running != null) add(QueueItem(running.status(), true))
            val queued = commandQueue.size()
            if (queued > 0) add(QueueItem("$queued command${if (queued == 1) "" else "s"} queued", false))
        }
        state.value = PumpStatusState(
            title = "YpsoPump",
            connection = when {
                pumpState.connectionState == ConnectionState.CONNECTED && !pumpState.hasVerifiedStatus   -> rh.gs(R.string.ypsopump_authenticated_no_status)
                else                                                                                     -> pumpState.connectionState.name.lowercase().replaceFirstChar { it.uppercase() }
            },
            connectionHealthy = pumpState.isConnected && pumpState.hasVerifiedStatus,
            reservoir = pumpState.reservoirUnits.takeIf { pumpState.hasVerifiedStatus },
            battery = pumpState.batteryPercent.takeIf { pumpState.hasVerifiedStatus },
            unavailableLabel = rh.gs(R.string.ypsopump_value_unavailable),
            rows = rows,
            queue = queue,
            note = buildList {
                if (pumpState.hasVerifiedStatus) add(rh.gs(R.string.ypsopump_provisional_status_note))
                if (YpsoPumpConst.READ_ONLY_MODE) add(rh.gs(R.string.ypsopump_status_only_note))
            }.joinToString(" ")
        )
    }
}
