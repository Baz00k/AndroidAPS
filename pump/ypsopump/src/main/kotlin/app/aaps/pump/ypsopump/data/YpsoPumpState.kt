package app.aaps.pump.ypsopump.data

import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.history.YpsoHistoryKind
import java.time.Instant
import java.time.ZoneId
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
        /** Profile evidence is deliberately short-lived and is also cleared on every disconnect. */
        const val PROFILE_MAX_AGE_MS = 5 * 60 * 1000L
    }

    data class StatusSnapshot(
        val reservoirUnits: Double,
        val batteryPercent: Int?,
        val batteryBars: Int?,
        /** Current pump-reported basal after TBR scaling; not stored-profile evidence. */
        val activeBasalRate: Double?,
        val activeTbrPercent: Int,
        val isSuspended: Boolean,
        val acquiredAt: Long,
        val elapsedAt: Long
    )

    internal var elapsedRealtime: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    internal var currentInstant: () -> Instant = { Instant.now() }
    internal var currentZone: () -> ZoneId = { ZoneId.systemDefault() }
    @Volatile private var sample: StatusSnapshot? = null
    @Volatile private var verifiedProfile: YpsoProfileReadback.VerifiedReadback? = null
    val statusSnapshot: StatusSnapshot?
        get() = sample?.takeIf { elapsedRealtime() - it.elapsedAt in 0 until STATUS_MAX_AGE_MS }

    // -- Connection State --
    @Volatile var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    @Volatile var serialNumber: String = ""
    @Volatile var claimedSerialNumber: String = ""
    @Volatile var observedIdentitySerial: String = ""
    @Volatile var pumpAddress: String = ""

    // -- Pump Status --
    val batteryPercent: Int get() = statusSnapshot?.batteryPercent ?: 0
    val reservoirUnits: Double get() = statusSnapshot?.reservoirUnits ?: 0.0
    // Single canonical mapping: the wire reports bars, consumers expect percent.
    val mappedBatteryPercent: Int?
        get() = statusSnapshot?.batteryPercent
            ?: statusSnapshot?.batteryBars?.let { (it * 20).coerceIn(0, 100) }
    @Volatile var isSuspended: Boolean = false
    @Volatile var isBolusingInProgress: Boolean = false
    @Volatile var isTbrActive: Boolean = false

    // -- Firmware --
    @Volatile var firmwareVersion: String = ""
    @Volatile var masterVersion: String = ""
    @Volatile var supervisorVersion: String = ""
    // Service protocol versions: GATT service compatibility, never pump firmware.
    @Volatile var baseServiceVersion: String = ""
    @Volatile var settingsServiceVersion: String = ""
    @Volatile var historyServiceVersion: String = ""
    @Volatile var controlServiceVersion: String = ""

    // -- Active Delivery --
    @Volatile var activeBasalRate: Double = 0.0
    @Volatile var activeTbrPercent: Int = 100
    @Volatile var activeTbrRemainingMinutes: Int = 0
    @Volatile var activeBolusRemaining: Double = 0.0

    // -- Profiles --
    internal val profileEvidence: YpsoProfileReadback.VerifiedReadback?
        get() = verifiedProfile?.takeIf {
            elapsedRealtime() - it.acquiredElapsedMs in 0 until PROFILE_MAX_AGE_MS && currentZone() == it.zone
        }

    val hasFreshProfileEvidence: Boolean
        get() = profileEvidence != null

    // -- Timestamps --
    @Volatile var lastConnectionTime: Long = 0L
    val lastStatusTime: Long get() = statusSnapshot?.acquiredAt ?: 0L
    @Volatile var keyExchangeTime: Long = 0L

    // -- Error Tracking --
    @Volatile var lastErrorCode: Int = 0
    @Volatile var lastErrorMessage: String = ""
    @Volatile var availability: PumpSession.Availability = PumpSession.Availability()

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
        batteryPercent: Int?,
        isSuspended: Boolean,
        activeTbrPercent: Int,
        timestamp: Long,
        batteryBars: Int? = null,
        activeBasalRate: Double? = null,
    ) {
        this.isSuspended = isSuspended
        this.activeTbrPercent = activeTbrPercent
        this.activeBasalRate = activeBasalRate ?: 0.0
        sample =
            StatusSnapshot(
                reservoirUnits,
                batteryPercent,
                batteryBars,
                activeBasalRate,
                activeTbrPercent,
                isSuspended,
                timestamp,
                elapsedRealtime(),
            )
        lastConnectionTime = timestamp
    }

    @Synchronized
    fun reservoirUnitsIfFresh(): Double? = statusSnapshot?.reservoirUnits

    /**
     * Derive the scheduled base rate only from a fresh measured status. A zero-percent TBR cannot
     * reveal the underlying rate, and this current-rate observation never proves profile coherence.
     */
    @Synchronized
    fun baseBasalRateIfFresh(): Double? =
        statusSnapshot?.let { status ->
            val rate = status.activeBasalRate ?: return@let null
            val percent = status.activeTbrPercent
            if (status.isSuspended || percent <= 0) null else rate * 100.0 / percent
        }

    @Synchronized
    internal fun publishProfileEvidence(value: YpsoProfileReadback.VerifiedReadback) {
        verifiedProfile = value
    }

    @Synchronized
    internal fun profileMatches(effective: List<YpsoBasalSchedule.EffectiveSegment>): Boolean =
        profileEvidence?.activeSchedule?.matches(effective) == true

    /** Scheduled pump base rate, independent of current TBR scaling and requested AAPS profile. */
    @Synchronized
    fun scheduledBaseBasalRateIfFresh(): Double? {
        val evidence = profileEvidence ?: return null
        val local = currentInstant().atZone(evidence.zone).toLocalTime().toSecondOfDay()
        return evidence.activeSchedule.rateAt(local)
    }

    @Synchronized
    fun invalidateProfileEvidence() {
        verifiedProfile = null
    }

    fun observeHistory(kind: YpsoHistoryKind) {
        if (kind in PROFILE_INVALIDATING_HISTORY) invalidateProfileEvidence()
    }

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

    fun updateAvailability(value: PumpSession.Availability) {
        availability = value
    }

    fun reset() {
        connectionState = ConnectionState.DISCONNECTED
        invalidateStatus()
        invalidateProfileEvidence()
        lastErrorCode = 0
        lastErrorMessage = ""
    }

    private val PROFILE_INVALIDATING_HISTORY = setOf(
        YpsoHistoryKind.BASAL_PROFILE_CHANGED,
        YpsoHistoryKind.BASAL_PROFILE_A_CHANGED,
        YpsoHistoryKind.BASAL_PROFILE_B_CHANGED,
        YpsoHistoryKind.DATE_CHANGED,
        YpsoHistoryKind.TIME_CHANGED,
    )
}
