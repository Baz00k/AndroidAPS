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
 * YpsoPump driver tab: read-only Compose status view over [YpsoPumpState] + [CommandQueue].
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
            val context = context
            if (context != null &&
                (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
                context.getSharedPreferences("ypso_ble_state", android.content.Context.MODE_PRIVATE).getBoolean("ypso_protocol_capture", false) &&
                commandQueue.performing() == null && commandQueue.size() == 0)
                commandQueue.readStatus(rh.gs(R.string.ypso_protocol_capture_reason), null)
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
        state.value = buildPumpStatusState(pumpState, commandQueue, dateUtil, rh)
    }
}

/** Build one render from one measurement snapshot, even at the expiry boundary. */
internal fun buildPumpStatusState(
    pumpState: YpsoPumpState,
    commandQueue: CommandQueue,
    dateUtil: DateUtil,
    rh: ResourceHelper
): PumpStatusState {
    val snapshot = pumpState.statusSnapshot
    val rows = buildList {
        if (pumpState.serialNumber.isNotEmpty()) add(PumpStatusRow(rh.gs(R.string.ypsopump_serial), pumpState.serialNumber))
        if (pumpState.firmwareVersion.isNotEmpty()) add(PumpStatusRow(rh.gs(R.string.ypsopump_firmware), pumpState.firmwareVersion))
        if (snapshot != null) {
            add(PumpStatusRow(rh.gs(R.string.ypsopump_last_status), dateUtil.minOrSecAgo(rh, snapshot.acquiredAt)))
        }
    }
    val queue = buildList {
        val running = commandQueue.performing()
        if (running != null) add(QueueItem(running.status(), true))
        val queued = commandQueue.size()
        if (queued > 0) add(QueueItem("$queued command${if (queued == 1) "" else "s"} queued", false))
    }
    val presentation = pumpSetupPresentation(
        causes = pumpState.availability.causes,
        hasSavedDetails = pumpState.claimedSerialNumber.isNotBlank(),
        verified = pumpState.serialNumber.isNotBlank(),
    )
    val connectionSummary = when {
        pumpState.connectionState == ConnectionState.CONNECTED -> rh.gs(R.string.ypsopump_connected)
        pumpState.connectionState == ConnectionState.DISCONNECTED -> rh.gs(R.string.ypsopump_disconnected)
        else -> rh.gs(R.string.ypsopump_connecting)
    }
    return PumpStatusState(
        title = "YpsoPump",
        connectionSummary = connectionSummary,
        connectionAction = when {
            presentation != PumpSetupPresentation.READY -> rh.gs(presentation.message)
            pumpState.connectionState == ConnectionState.CONNECTED && snapshot == null -> rh.gs(R.string.ypsopump_authenticated_no_status)
            else -> null
        },
        connectionHealthy = pumpState.isConnected && snapshot != null,
        reservoir = snapshot?.reservoirUnits,
        // Bars are the internal wire representation; the UI only ever shows the mapped percent.
        battery = pumpState.mappedBatteryPercent,
        unavailableLabel = rh.gs(R.string.ypsopump_value_unavailable),
        rows = rows,
        queue = queue,
        note = if (YpsoPumpConst.READ_ONLY_MODE) rh.gs(R.string.ypsopump_status_only_note) else ""
    )
}
