package app.aaps.plugins.source.compose

/**
 * What the Sensor screen draws. Built by the plugin; the composable stays dumb so it can be
 * previewed and reasoned about without a sensor attached.
 */
data class Libre3SensorState(
    val connection: Connection = Connection.Disconnected,
    val lifecycle: Lifecycle = Lifecycle.NoSensor,
    val serial: String? = null,
    val mac: String? = null,
    val startedLabel: String? = null,
    val expiresLabel: String? = null,
    /** null when nothing has arrived yet — render as "—", never as 0. */
    val lastReadingAgoMinutes: Int? = null,
    /** RSSI in dBm; null when unknown. */
    val signalDbm: Int? = null,
    val backfilledCount: Int = 0,
    val lastError: String? = null
) {

    enum class Connection { Connected, Connecting, Disconnected }

    sealed interface Lifecycle {
        /** No credentials — the plugin has nothing to connect to. */
        data object NoSensor : Lifecycle
        /** Counting up to the first reading. [minutesRemaining] drives a FILLING ring. */
        data class WarmingUp(val minutesRemaining: Int) : Lifecycle
        /** Normal running. [fractionRemaining] 1..0 drives a DEPLETING ring. */
        data class Active(val fractionRemaining: Float, val remainingLabel: String) : Lifecycle
        data object Expired : Lifecycle
        data class Failed(val patchState: Int) : Lifecycle
    }

    /**
     * Signal strength as bars 0..4. Deliberately coarse: an exact dBm invites the user to chase
     * a number they cannot act on, whereas "weak" is actionable — move the phone closer.
     */
    val signalBars: Int?
        get() = signalDbm?.let {
            when {
                it >= -60 -> 4
                it >= -70 -> 3
                it >= -80 -> 2
                it >= -90 -> 1
                else      -> 0
            }
        }

    /**
     * One line for the status row. Warm-up must never read as an error — an hour with no data is
     * the sensor working exactly as designed, and saying "no data" there trains distrust.
     */
    val statusLine: String
        get() = when (lifecycle) {
            is Lifecycle.NoSensor   -> "No sensor configured"
            is Lifecycle.WarmingUp  -> "Warming up · ${lifecycle.minutesRemaining} min left"
            is Lifecycle.Expired    -> "Sensor expired — replace it"
            is Lifecycle.Failed     -> "Sensor failed (state ${lifecycle.patchState})"
            is Lifecycle.Active     -> when (connection) {
                Connection.Connected    ->
                    lastReadingAgoMinutes?.let { "Connected · last reading ${it}m ago" } ?: "Connected"
                Connection.Connecting   -> "Connecting…"
                Connection.Disconnected -> "Disconnected — will reconnect"
            }
        }
}
