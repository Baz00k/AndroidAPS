package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.data.YpsoPumpState

/** One display snapshot for the pump tab and overview; it conveys no therapy readiness. */
internal data class PumpStatusPresentation(
    val snapshot: YpsoPumpState.StatusSnapshot?,
    val battery: Int?,
    val connectionSummary: String,
    val connectionHealthy: Boolean,
    val shortStatus: String,
)

internal fun pumpStatusPresentation(state: YpsoPumpState, rh: ResourceHelper): PumpStatusPresentation {
    val display = state.displayStatus()
    val snapshot = display.snapshot
    val connection = state.connectionState
    val summary = rh.gs(when (connection) {
        ConnectionState.CONNECTED -> if (snapshot == null) R.string.ypsopump_awaiting_readings else R.string.ypsopump_connected
        ConnectionState.DISCONNECTED -> R.string.ypsopump_disconnected
        else -> R.string.ypsopump_connecting
    })
    val battery = snapshot?.mappedBatteryPercent
    val shortStatus = when {
        snapshot == null && connection == ConnectionState.CONNECTED -> summary
        snapshot == null -> "$summary · ${rh.gs(R.string.ypsopump_authenticated_no_status)}"
        battery != null -> rh.gs(R.string.ypsopump_short_status, snapshot.reservoirUnits, battery)
        else -> rh.gs(R.string.ypsopump_short_status_reservoir, snapshot.reservoirUnits)
    }
    return PumpStatusPresentation(snapshot, battery, summary, connection == ConnectionState.CONNECTED && display.isCurrent, shortStatus)
}
