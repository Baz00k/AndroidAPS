package app.aaps.pump.ypsopump

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import android.content.Context
import android.content.Intent
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * AndroidAPS pump plugin for the Ypsomed YpsoPump.
 *
 * Read-only milestone: exposes connection state, reservoir level and battery from [YpsoPumpState]
 * (populated by the BLE layer). All dosing operations return "not implemented" — deliberately
 * stubbed until the write/dosing path is finished and safety-validated.
 */
@Singleton
class YpsoPumpPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    commandQueue: CommandQueue,
    private val pumpState: YpsoPumpState,
    private val bleManager: YpsoBleManager,
    private val pumpSync: PumpSync,
    private val rxBus: RxBus,
    private val uiInteraction: UiInteraction,
    private val pumpEnactResultProvider: Provider<PumpEnactResult>,
    private val provisioning: YpsoProvisioningService
) : PumpPluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .fragmentClass(YpsoPumpFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.ypsopump_name)
        .shortName(R.string.ypsopump_name_short)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .description(R.string.ypsopump_description),
    ownPreferences = emptyList(),
    aapsLogger, rh, preferences, commandQueue
), Pump {

    private var publishedAvailabilityPresentation: PumpSetupPresentation? = null
    /** Shared store can outlive this plugin instance; the first publication must reconcile its ID. */
    private var availabilityNotificationSynchronized = false

    init {
        provisioning.availabilityChanged = { publishAvailabilityNotification() }
    }

    override val pumpDescription: PumpDescription = PumpDescription().fillFor(PumpType.YPSOPUMP).apply {
        // Profile programming is unsupported independently of the broader therapy milestone.
        isSetBasalProfileCapable = false
        if (YpsoPumpConst.READ_ONLY_MODE) {
            isBolusCapable = false
            isExtendedBolusCapable = false
            isTempBasalCapable = false
            isSetBasalProfileCapable = false
            supportsTDDs = false
            needsManualTDDLoad = false
        }
    }

    private fun notImplemented(): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false).comment(rh.gs(R.string.ypsopump_not_implemented))

    // ---- state (read-only) ----
    override fun isInitialized(): Boolean = pumpState.hasVerifiedStatus
    // A status-only build must not drive AAPS running-mode transitions from the still-unverified delivery
    // mode byte. The status-only artifact exposes this state without enabling dose requests.
    override fun isSuspended(): Boolean = !YpsoPumpConst.READ_ONLY_MODE && (pumpState.isSuspended || reservoirEmpty())
    override fun isBusy(): Boolean = false
    override fun isConnected(): Boolean = pumpState.isConnected
    override fun isConnecting(): Boolean = pumpState.connectionState == ConnectionState.CONNECTING
    override fun isHandshakeInProgress(): Boolean =
        pumpState.connectionState == ConnectionState.DISCOVERING || pumpState.connectionState == ConnectionState.READY

    private fun resolvedMac(): String = bleManager.installedPumpMac()
    /** A legacy replay tombstone without protected credentials is intentionally not connectable. */
    private fun configured(): Boolean = provisioning.isConfigured() && resolvedMac().isNotEmpty()

    private fun seedAndConnect() {
        try {
            if (!provisioning.retryAllowed()) {
                aapsLogger.info(LTag.PUMP, "YpsoPump retry deferred by durable backoff")
                return
            }
            check(bleManager.configureInstalledSession()) { "No protected session" }
            bleManager.connectConfiguredSession()
        } catch (exception: RuntimeException) {
            bleManager.disconnect()
            pumpState.invalidateStatus()
            provisioning.recordUnavailable(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE), operation = "configure-session")
            aapsLogger.error(LTag.PUMP, "YpsoPump configuration is invalid")
        }
    }

    override fun connect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "connect: $reason")
        if (!configured()) {
            bleManager.disconnect()
            pumpState.invalidateStatus()
            aapsLogger.info(LTag.PUMP, "YpsoPump: no protected pump session configured — skipping connect")
            // A failed replacement whose only fallback is the retained legacy bundle is being restored
            // asynchronously; do not overwrite its recorded failure with an unconfigured condition.
            if (!provisioning.isSessionRestorePending())
                provisioning.recordUnavailable(setOf(PumpSession.AvailabilityCause.UNCONFIGURED), operation = "connect")
            return
        }
        seedAndConnect()
    }

    override fun disconnect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "disconnect: $reason")
        bleManager.disconnect(preserveStatus = reason == "Queue empty")
    }
    override fun stopConnecting() { bleManager.disconnect() }

    override fun getPumpStatus(reason: String) {
        aapsLogger.debug(LTag.PUMP, "getPumpStatus: $reason")
        // CommandReadStatus infers success from lastDataTime. Clear the prior sample even when this
        // invocation only starts a connection, so it cannot report a recent older read as current.
        pumpState.invalidateStatus()
        if (!configured()) {
            aapsLogger.info(LTag.PUMP, "YpsoPump: session key and/or pump MAC not set — skipping read")
            return
        }
        if (!bleManager.isConnected) { seedAndConnect(); return }
        // Normal operation exposes only the supported authenticated status-only path.
        readStatusBlocking()
    }

    override val lastDataTime: Long get() = pumpState.lastStatusTime
    override val lastBolusTime: Long? get() = pumpSync.expectedPumpState().bolus?.timestamp
    override val lastBolusAmount: Double? get() = pumpSync.expectedPumpState().bolus?.amount
    // Status-only remains zero so LoopPlugin cannot run. A future therapy build must use the fresh
    // measured pump status, never mirror the desired AAPS profile as if it were pump configuration.
    override val baseBasalRate: Double get() =
        if (YpsoPumpConst.READ_ONLY_MODE) 0.0 else pumpState.baseBasalRateIfFresh() ?: 0.0
    override val reservoirLevel: Double get() = pumpState.statusSnapshot?.reservoirUnits ?: Double.NaN
    // The pump reports battery as 0–5 bars, not a percentage. AAPS consumers expect percent;
    // see the single canonical mapping at [YpsoPumpState.mappedBatteryPercent].
    override val batteryLevel: Int? get() = pumpState.mappedBatteryPercent

    private fun fail(stringRes: Int, vararg args: Any): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false).comment(rh.gs(stringRes, *args))

    override fun setNewBasalProfile(profile: Profile): PumpEnactResult =
        fail(R.string.ypsopump_profile_unavailable)

    // No target-qualified active-profile and 24-hour schedule read-back exists. Matching only the
    // current rate would be unsafe because A/B or a later hourly segment may differ.
    override fun isThisProfileSet(profile: Profile): Boolean = false

    // History identity and command origin are not yet wired into production therapy. Do not retain dormant
    // amount/recent-event or receipt-time fallbacks: later dosing tickets must snapshot a stable cursor before
    // dispatch and reconcile a uniquely attributable newer history event.
    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult =
        fail(R.string.ypsopump_read_only_bolus_blocked)

    override fun stopBolusDelivering() = Unit

    /** Everything that must happen after a status read lands in the status-only artifact. */
    private fun onStatusRead() {
        checkReservoir()
    }

    /**
     * Reservoir surveillance.
     *
     * The pump running dry is the one failure this app used to be completely silent about: the loop kept
     * commanding basal and boluses, the reservoir pill on Home *disappeared* at zero (it was only drawn
     * when `> 0`), and the first sign anything was wrong was the glucose curve. So: warn while there is
     * still time to act, alarm when there isn't.
     *
     * Gated on a status read having actually succeeded — [YpsoPumpState.reservoirUnits] is 0.0 before the
     * first read and after [YpsoPumpState.reset], and alarming on "not read yet" would train the alarm out.
     */
    private fun checkReservoir() {
        val units = pumpState.reservoirUnitsIfFresh() ?: return
        // Reuse the app's existing reservoir thresholds rather than inventing a second set of numbers
        // nobody can find.
        //
        // Only CRITICAL raises a notification. "Warning" is the level the status lights always meant —
        // a colour, not a nag — and it stays a colour, on the Home reservoir pill.
        val level = when {
            units <= RESERVOIR_EMPTY_UNITS                  -> ReservoirLevel.EMPTY
            units <= preferences.get(IntKey.OverviewResCritical) -> ReservoirLevel.LOW
            else                                            -> ReservoirLevel.OK
        }
        // Clear the other alerts only when the level actually MOVED. Raising is left unconditional: the
        // store de-dupes by id (so the alarm doesn't re-sound every read) but a user who swipes an empty
        // reservoir away and does nothing about it gets it back on the next read, which is the point.
        if (level != lastReservoirLevel) {
            if (level != ReservoirLevel.EMPTY) rxBus.send(EventDismissNotification(Notification.PUMP_RESERVOIR_EMPTY))
            if (level != ReservoirLevel.LOW) rxBus.send(EventDismissNotification(Notification.PUMP_RESERVOIR_LOW))
            lastReservoirLevel = level
        }
        when (level) {
            ReservoirLevel.EMPTY -> uiInteraction.addNotificationWithSound(
                Notification.PUMP_RESERVOIR_EMPTY,
                rh.gs(R.string.ypsopump_reservoir_empty_notification),
                Notification.URGENT,
                app.aaps.core.ui.R.raw.alarm
            )

            ReservoirLevel.LOW   -> uiInteraction.addNotification(
                Notification.PUMP_RESERVOIR_LOW,
                rh.gs(R.string.ypsopump_reservoir_low_notification, units),
                Notification.URGENT
            )

            ReservoirLevel.OK    -> Unit
        }
    }

    private enum class ReservoirLevel { OK, LOW, EMPTY }

    private var lastReservoirLevel = ReservoirLevel.OK

    /** True only on a fresh read — see [checkReservoir] for why "0.0" alone is not enough. */
    private fun reservoirEmpty(): Boolean = pumpState.reservoirUnitsIfFresh()?.let { it <= RESERVOIR_EMPTY_UNITS } == true

    /** One blocking status read, so a pre-flight check tests the pump's state now, not minutes ago. */
    private fun readStatusBlocking(timeoutMs: Long = 30_000): Boolean {
        var success = false
        val l = java.util.concurrent.CountDownLatch(1)
        val attempt = bleManager.readStatus {
            success = it
            l.countDown()
            if (it) onStatusRead()
        }
        if (l.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return success
        if (!attempt.cancel()) {
            // The BLE callback won the completion race and is publishing the validated sample now.
            l.await()
            return success
        }
        aapsLogger.error(LTag.PUMP, "YpsoPump status read timed out after ${timeoutMs}ms")
        pumpState.invalidateStatus()
        bleManager.disconnect()
        return false
    }

    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult =
        fail(R.string.ypsopump_read_only_tbr_blocked)

    override fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult =
        fail(R.string.ypsopump_read_only_tbr_blocked)

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult =
        fail(R.string.ypsopump_read_only_tbr_cancel_blocked)

    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult = notImplemented()
    override fun cancelExtendedBolus(): PumpEnactResult = notImplemented()
    override fun loadTDDs(): PumpEnactResult = notImplemented()

    // ---- identity ----
    override fun manufacturer(): ManufacturerType = ManufacturerType.Ypsomed
    override fun model(): PumpType = PumpType.YPSOPUMP
    override fun serialNumber(): String = pumpState.serialNumber

    override fun onStart() {
        super.onStart()
        provisioning.refreshState()
        publishAvailabilityNotification()
    }

    override fun onStop() {
        super.onStop()
        dismissAvailabilityNotification()
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return
        val category = PreferenceCategory(context).apply {
            key = "ypsopump_connection_setup"
            title = rh.gs(R.string.ypsopump_connection_setup)
        }
        // PreferenceGroup needs the host PreferenceManager before children can receive stable IDs.
        parent.addPreference(category)
        category.apply {
            addPreference(Preference(context).apply {
                title = rh.gs(R.string.ypsopump_connection_setup)
                summary = provisioning.installed()?.let { rh.gs(R.string.ypsopump_configured_summary, it.serial) }
                    ?: rh.gs(R.string.ypsopump_not_configured)
                setOnPreferenceClickListener {
                    context.startActivity(Intent(context, YpsoProvisioningActivity::class.java))
                    true
                }
            })
        }
    }

    @Synchronized
    internal fun publishAvailabilityNotification() {
        if (!provisioning.notificationRequired()) {
            dismissAvailabilityNotification()
            return
        }
        val availability = provisioning.availability()
        val presentation = pumpSetupPresentation(
            causes = availability.causes,
            hasSavedDetails = provisioning.installed() != null,
            verified = provisioning.installed()?.verifiedAt != null,
        )
        if (presentation == PumpSetupPresentation.READY) {
            dismissAvailabilityNotification()
            return
        }
        if (presentation == publishedAvailabilityPresentation) return
        // NotificationStore preserves existing text for a repeated ID. A plugin can also start
        // after a prior process left this ID in the store, so replace the first publication of
        // this plugin lifetime and every later changed instruction.
        rxBus.send(EventDismissNotification(Notification.YPSOPUMP_UNAVAILABLE))
        uiInteraction.addNotification(
            Notification.YPSOPUMP_UNAVAILABLE,
            rh.gs(
                R.string.ypsopump_unavailable_notification,
                rh.gs(presentation.message)
            ),
            Notification.URGENT
        )
        publishedAvailabilityPresentation = presentation
        availabilityNotificationSynchronized = true
    }

    @Synchronized
    private fun dismissAvailabilityNotification() {
        if (publishedAvailabilityPresentation == null && availabilityNotificationSynchronized) return
        rxBus.send(EventDismissNotification(Notification.YPSOPUMP_UNAVAILABLE))
        publishedAvailabilityPresentation = null
        availabilityNotificationSynchronized = true
    }
    override val isFakingTempsByExtendedBoluses: Boolean = false
    override fun canHandleDST(): Boolean = false
    override fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {}
    override fun pumpSpecificShortStatus(veryShort: Boolean): String {
        val snapshot = pumpState.statusSnapshot
        return if (snapshot != null) {
            // Framework-facing percent; bars stay internal (see batteryLevel mapping).
            val battery = batteryLevel
            if (battery != null)
                rh.gs(R.string.ypsopump_short_status, snapshot.reservoirUnits, battery)
            else rh.gs(R.string.ypsopump_short_status_reservoir, snapshot.reservoirUnits)
        } else {
            rh.gs(R.string.ypsopump_status_unavailable)
        }
    }

    companion object {

        /** The pump reports remaining insulin in centi-units, so a true empty reads as exactly 0. */
        private const val RESERVOIR_EMPTY_UNITS = 0.0

    }
}
