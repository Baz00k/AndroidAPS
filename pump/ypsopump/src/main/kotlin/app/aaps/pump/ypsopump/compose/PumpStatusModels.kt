package app.aaps.pump.ypsopump.compose

import androidx.compose.runtime.Immutable

@Immutable
data class PumpStatusRow(val label: String, val value: String)

@Immutable
data class QueueItem(val text: String, val running: Boolean)

@Immutable
data class PumpStatusState(
    val title: String = "YpsoPump",
    val connection: String = "",
    val connectionHealthy: Boolean = false,
    val reservoir: Double? = null,
    val reservoirMax: Double = 200.0,
    val battery: Int? = null,
    val unavailableLabel: String = "Unavailable",
    val rows: List<PumpStatusRow> = emptyList(),
    val queue: List<QueueItem> = emptyList(),
    val note: String = ""
)
