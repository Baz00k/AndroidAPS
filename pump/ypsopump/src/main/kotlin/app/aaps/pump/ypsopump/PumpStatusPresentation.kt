package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.utils.compactDurationLabel
import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.data.YpsoPumpState

/** One display snapshot for the pump tab and overview; it conveys no therapy readiness. */
internal data class PumpStatusPresentation(
    val snapshot: YpsoPumpState.StatusSnapshot?,
    val battery: Int?,
    val connectionSummary: String,
    val connectionHealthy: Boolean,
    val readingsNotice: String?,
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
    val notice = snapshot?.let {
        val age = display.ageMs?.takeIf { it >= 0 }?.let(::compactDurationLabel) ?: rh.gs(R.string.ypsopump_value_unavailable)
        when {
            !display.isCurrent -> rh.gs(R.string.ypsopump_cached_readings, age)
            connection != ConnectionState.CONNECTED -> rh.gs(R.string.ypsopump_last_readings, age)
            else -> null
        }
    }
    val shortStatus = if (snapshot == null) {
        if (connection == ConnectionState.CONNECTED) summary
        else "$summary · ${rh.gs(R.string.ypsopump_authenticated_no_status)}"
    } else {
        val values = if (battery != null) rh.gs(R.string.ypsopump_short_status, snapshot.reservoirUnits, battery)
        else rh.gs(R.string.ypsopump_short_status_reservoir, snapshot.reservoirUnits)
        if (notice != null) "$notice: $values" else values
    }
    return PumpStatusPresentation(snapshot, battery, summary, connection == ConnectionState.CONNECTED && display.isCurrent, notice, shortStatus)
}
