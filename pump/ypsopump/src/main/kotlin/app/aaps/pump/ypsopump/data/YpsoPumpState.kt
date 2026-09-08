package app.aaps.pump.ypsopump.data

import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current state of the YpsoPump connection and pump data.
 * Thread-safe via @Volatile annotations.
 */
@Singleton
class YpsoPumpState @Inject constructor() {

    companion object {
        /** Status viewer budget; this is not a therapy-readiness guarantee. */
        const val STATUS_MAX_AGE_MS = 5 * 60 * 1000L
    }

    data class StatusSnapshot(val reservoirUnits: Double, val batteryPercent: Int, val acquiredAt: Long, val elapsedAt: Long)

    internal var elapsedRealtime: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    @Volatile private var sample: StatusSnapshot? = null
    val statusSnapshot: StatusSnapshot?
        get() = sample?.takeIf { elapsedRealtime() - it.elapsedAt in 0 until STATUS_MAX_AGE_MS }

    // -- Connection State --
    @Volatile var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    @Volatile var serialNumber: String = ""
    @Volatile var pumpAddress: String = ""

    // -- Pump Status --
    val batteryPercent: Int get() = statusSnapshot?.batteryPercent ?: 0
    val reservoirUnits: Double get() = statusSnapshot?.reservoirUnits ?: 0.0
    @Volatile var isSuspended: Boolean = false
    @Volatile var isBolusingInProgress: Boolean = false
    @Volatile var isTbrActive: Boolean = false

    // -- Firmware --
    @Volatile var firmwareVersion: String = ""
    @Volatile var masterVersion: String = ""
    @Volatile var supervisorVersion: String = ""

    // -- Active Delivery --
    @Volatile var activeBasalRate: Double = 0.0
    @Volatile var activeTbrPercent: Int = 100
    @Volatile var activeTbrRemainingMinutes: Int = 0
    @Volatile var activeBolusRemaining: Double = 0.0

    // -- Profiles --
    val profileA: FloatArray = FloatArray(24) // 24 hourly basal rates
    val profileB: FloatArray = FloatArray(24) // alternate profile
    @Volatile var isProfileAActive: Boolean = true

    // -- Timestamps --
    @Volatile var lastConnectionTime: Long = 0L
    val lastStatusTime: Long get() = statusSnapshot?.acquiredAt ?: 0L
    @Volatile var keyExchangeTime: Long = 0L

    // -- Error Tracking --
    @Volatile var lastErrorCode: Int = 0
    @Volatile var lastErrorMessage: String = ""

    val isConnected: Boolean
        get() = connectionState == ConnectionState.CONNECTED

    val hasVerifiedStatus: Boolean
        get() = statusSnapshot != null

    val connectionHealthy: Boolean
        get() = isConnected || connectionState == ConnectionState.DISCONNECTED && hasVerifiedStatus

    val isInitialized: Boolean
        get() = serialNumber.isNotEmpty() && firmwareVersion.isNotEmpty()

    @Synchronized
    fun publishStatus(
        reservoirUnits: Double,
        batteryPercent: Int,
        isSuspended: Boolean,
        activeTbrPercent: Int,
        timestamp: Long
    ) {
        this.isSuspended = isSuspended
        this.activeTbrPercent = activeTbrPercent
        sample = StatusSnapshot(reservoirUnits, batteryPercent, timestamp, elapsedRealtime())
        lastConnectionTime = timestamp
    }

    @Synchronized
    fun reservoirUnitsIfFresh(): Double? = statusSnapshot?.reservoirUnits

    @Synchronized
    fun invalidateStatus() {
        // Freshness is cleared first; coherent consumers also read these fields under this monitor.
        sample = null
        lastConnectionTime = 0L
        isSuspended = false
        isBolusingInProgress = false
        isTbrActive = false
        activeBasalRate = 0.0
        activeTbrPercent = 100
        activeTbrRemainingMinutes = 0
        activeBolusRemaining = 0.0
    }

    fun reset() {
        connectionState = ConnectionState.DISCONNECTED
        invalidateStatus()
        lastErrorCode = 0
        lastErrorMessage = ""
    }
}
