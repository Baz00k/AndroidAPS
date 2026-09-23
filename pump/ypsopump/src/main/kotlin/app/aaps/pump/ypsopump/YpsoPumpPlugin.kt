package app.aaps.pump.ypsopump

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.lifecycle.AppLifecycle
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.BolusProgressData
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
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.data.YpsoBasalSchedule
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptFileStore
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptJournal
import app.aaps.pump.ypsopump.bolus.YpsoBolusRequestValidator
import app.aaps.pump.ypsopump.bolus.YpsoImmediateBolusController
import app.aaps.pump.ypsopump.bolus.toYpsoTreatment
import app.aaps.pump.ypsopump.history.YpsoHistoryIngestion
import app.aaps.pump.ypsopump.history.YpsoHistoryIngestionResult
import app.aaps.pump.ypsopump.history.YpsoHistorySnapshot
import app.aaps.pump.ypsopump.history.YpsoHistoryReconciler
import app.aaps.pump.ypsopump.history.YpsoHistoryReconciliation
import app.aaps.pump.ypsopump.history.YpsoPumpLocalTime
import app.aaps.pump.ypsopump.bolus.YpsoImmediateBolusReconciler
import app.aaps.pump.ypsopump.bolus.YpsoImmediateBolusReconciliation
import app.aaps.pump.ypsopump.bolus.YpsoImmediateBolusStatus
import app.aaps.pump.ypsopump.bolus.YpsoExtendedBolusReconciler
import app.aaps.pump.ypsopump.bolus.YpsoExtendedBolusReconciliation
import app.aaps.pump.ypsopump.bolus.YpsoExtendedBolusAccounting
import app.aaps.pump.ypsopump.bolus.YpsoBolusShape
import app.aaps.pump.ypsopump.bolus.YpsoValidatedBolusRequest
import app.aaps.pump.ypsopump.bolus.YpsoBolusMessage
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.history.YpsoHistoryStateFileStore
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.tbr.YpsoTbrAttempt
import app.aaps.pump.ypsopump.tbr.YpsoTbrAttemptFileStore
import app.aaps.pump.ypsopump.tbr.YpsoTbrBleLink
import app.aaps.pump.ypsopump.tbr.YpsoTbrController
import app.aaps.pump.ypsopump.tbr.YpsoTbrHistoryAccounting
import app.aaps.pump.ypsopump.tbr.YpsoTbrJournal
import app.aaps.pump.ypsopump.tbr.YpsoTbrObservation
import app.aaps.pump.ypsopump.tbr.YpsoTbrRecordLookup
import app.aaps.pump.ypsopump.tbr.YpsoTbrRecords
import app.aaps.pump.ypsopump.tbr.YpsoTbrRequest
import android.content.Context
import android.content.Intent
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * AndroidAPS pump plugin for the Ypsomed YpsoPump.
 *
 * Exposes connection/status data and durable immediate/square-bolus paths. Other therapy features remain
 * unsupported and [YpsoPumpConst.READ_ONLY_MODE] remains the final deployment gate.
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
    private val provisioning: YpsoProvisioningService,
    private val profileFunction: ProfileFunction,
    private val constraintsChecker: ConstraintsChecker,
    private val appLifecycle: AppLifecycle,
) : PumpPluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .fragmentClass(YpsoPumpFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.ypsopump_name)
        .shortName(R.string.ypsopump_name_short)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        // Advertised capability must follow the build's therapy gate, never outrun it.
        .description(
            if (YpsoPumpConst.READ_ONLY_MODE) R.string.ypsopump_description_read_only
            else R.string.ypsopump_description
        ),
    ownPreferences = emptyList(),
    aapsLogger, rh, preferences, commandQueue
), Pump {

    @Inject lateinit var persistenceLayer: app.aaps.core.interfaces.db.PersistenceLayer

    private var publishedAvailabilityPresentation: PumpSetupPresentation? = null
    /** Shared store can outlive this plugin instance; the first publication must reconcile its ID. */
    private var availabilityNotificationSynchronized = false
    /** Last published mismatch text, or null when no mismatch is currently published. */
    private var publishedProfileMismatch: String? = null
    private var publishedUnresolvedBolusWarning: String? = null
    private val tbrJournal by lazy {
        YpsoTbrJournal(YpsoTbrAttemptFileStore(java.io.File(bleManager.noBackupDirectory(), "ypsopump-tbr-attempts.json")))
    }
    private val historyIngestion by lazy {
        YpsoHistoryIngestion(
            YpsoHistoryStateFileStore(java.io.File(bleManager.noBackupDirectory(), "ypsopump-history-state.json")),
            pumpSync,
            resolveProvisional = ::bindProvisionalBolusToPumpId,
            tbrAccounting = YpsoTbrHistoryAccounting(pumpSync, tbrJournal, object : YpsoTbrRecordLookup {
                override fun byPumpId(pumpId: Long, pumpSerial: String, start: Long) =
                    tbrRecord(pumpSerial, start) { it.ids.pumpId == pumpId }?.let { it.timestamp to it.duration }

                override fun suspendActiveAt(pumpSerial: String, at: Long) = activeTbrRecords(pumpSerial, at)
                    .any { it.type == app.aaps.core.data.model.TB.Type.PUMP_SUSPEND }
            }),
        )
    }
    private val tbrController by lazy {
        YpsoTbrController(
            YpsoTbrBleLink(bleManager, readStatus = { readTherapyStatus() == TherapyStatusReadiness.READY }),
            tbrJournal,
            tbrRecords,
            ::serialNumber,
            historyBaseline = { historyIngestion.currentCursor()?.identity?.aapsPumpId },
        )
    }

    /**
     * The single valid YpsoPump TBR record starting at or after [notBefore] matching [predicate]. Every
     * caller knows the start it wrote, so read-back works however old the record is.
     */
    private fun tbrRecord(serial: String, notBefore: Long, predicate: (app.aaps.core.data.model.TB) -> Boolean): app.aaps.core.data.model.TB? {
        if (!::persistenceLayer.isInitialized) return null
        return persistenceLayer.getTemporaryBasalsStartingFromTime(notBefore - 60_000L, false).blockingGet()
            .singleOrNull { it.isValid && it.ids.pumpType == PumpType.YPSOPUMP && it.ids.pumpSerial == serial && predicate(it) }
    }

    private fun activeTbrRecords(serial: String, at: Long): List<app.aaps.core.data.model.TB> {
        if (!::persistenceLayer.isInitialized) return emptyList()
        return persistenceLayer.getTemporaryBasalsActiveBetweenTimeAndTime(at, at)
            .filter { it.isValid && it.ids.pumpType == PumpType.YPSOPUMP && it.ids.pumpSerial == serial }
    }

    /** Last status time each record was still seen matching the pump, for the conservative end of a cut. */
    private val lastSeenMatching = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /**
     * An AAPS start is a percent record under its temporary ID (or its bound pump ID). Ends are applied
     * as a shorter duration, never as an end-event identity, so pump history can still correct them.
     */
    private val tbrRecords = object : YpsoTbrRecords {
        private fun find(attempt: YpsoTbrAttempt, includeInvalid: Boolean = false): app.aaps.core.data.model.TB? {
            val start = attempt.effectiveAt ?: attempt.createdAt
            if (!::persistenceLayer.isInitialized) return null
            return persistenceLayer.getTemporaryBasalsStartingFromTimeIncludingInvalid(start - 60_000L, false).blockingGet()
                .filter { (includeInvalid || it.isValid) && it.ids.pumpType == PumpType.YPSOPUMP && it.ids.pumpSerial == attempt.pumpSerial }
                .singleOrNull { it.ids.temporaryId == attempt.temporaryId || attempt.pumpId != null && it.ids.pumpId == attempt.pumpId }
        }

        override fun saveStart(attempt: YpsoTbrAttempt, timestamp: Long): YpsoTbrRecords.Saved {
            val existing = find(attempt, includeInvalid = true)
            if (existing != null && !existing.isValid) return YpsoTbrRecords.Saved.DELETED
            if (existing == null) {
                pumpSync.addTemporaryBasalWithTempId(
                    timestamp, attempt.percent.toDouble(), attempt.durationMinutes * 60_000L, false,
                    attempt.temporaryId, PumpSync.TemporaryBasalType.valueOf(attempt.type), PumpType.YPSOPUMP, attempt.pumpSerial,
                )
            }
            val saved = find(attempt) ?: return YpsoTbrRecords.Saved.NOT_SAVED
            return if (saved.timestamp == timestamp && !saved.isAbsolute && saved.rate == attempt.percent.toDouble())
                YpsoTbrRecords.Saved.SAVED else YpsoTbrRecords.Saved.NOT_SAVED
        }

        override fun shortenStart(attempt: YpsoTbrAttempt, end: Long): Boolean {
            val saved = find(attempt) ?: return false
            return shorten(saved, end)
        }

        /** Duration-only update; never an end-event identity, which would freeze the record. */
        private fun shorten(saved: app.aaps.core.data.model.TB, end: Long): Boolean {
            val duration = (end - saved.timestamp).coerceAtLeast(1L)
            if (saved.duration <= duration) return true
            val type = PumpSync.TemporaryBasalType.fromDbType(saved.type)
            val pumpId = saved.ids.pumpId
            val temporaryId = saved.ids.temporaryId
            when {
                pumpId != null -> pumpSync.syncTemporaryBasalWithPumpId(
                    saved.timestamp, saved.rate, duration, saved.isAbsolute, type, pumpId, PumpType.YPSOPUMP, checkNotNull(saved.ids.pumpSerial),
                )
                temporaryId != null -> pumpSync.syncTemporaryBasalWithTempId(
                    saved.timestamp, saved.rate, duration, saved.isAbsolute, temporaryId, null, null, PumpType.YPSOPUMP, checkNotNull(saved.ids.pumpSerial),
                )
                else -> return false
            }
            return activeTbrRecords(checkNotNull(saved.ids.pumpSerial), end).none { it.id == saved.id }
        }

        /**
         * A fresh status that contradicts an active record means the pump ended it at some point since
         * that record was last seen matching. The end is chosen so IOB errs high: a net-negative record
         * (below 100 % or a stop) ends when it was last seen, a high TBR ends at this observation.
         * Pump history later moves the end to the pump's own time.
         */
        override fun reconcileWith(observation: YpsoTbrObservation) {
            val serial = serialNumber().takeIf(String::isNotBlank) ?: return
            val at = observation.observedAt
            for (record in activeTbrRecords(serial, at)) {
                val matches = when (record.type) {
                    app.aaps.core.data.model.TB.Type.PUMP_SUSPEND -> !observation.running
                    else -> observation.running && !record.isAbsolute && record.rate.toInt() == observation.percent &&
                        observation.remainingMinutes > 0
                }
                if (matches) {
                    lastSeenMatching[record.id] = at
                    continue
                }
                // A record written moments ago may still be settling against this sample.
                if (record.timestamp > at - 60_000L) continue
                val netNegative = record.type == app.aaps.core.data.model.TB.Type.PUMP_SUSPEND || record.isAbsolute || record.rate < 100.0
                val end = if (netNegative) lastSeenMatching[record.id] ?: record.timestamp + 1L else at
                if (!shorten(record, end)) aapsLogger.warn(LTag.PUMP, "YpsoPump could not end TBR record ${record.id} contradicted by status")
                lastSeenMatching.remove(record.id)
            }
        }
    }
    private val bolusController by lazy {
        YpsoImmediateBolusController(
            bleManager,
            YpsoBolusAttemptJournal(
                YpsoBolusAttemptFileStore(java.io.File(bleManager.noBackupDirectory(), "ypsopump-bolus-attempt.json")),
            ),
            ::serialNumber,
            historyIngestion::currentCursor,
        )
    }
    private val historyRecoveryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ypso-history-recovery").apply { isDaemon = true }
    }
    internal var dispatchHistoryRecovery: ((() -> Unit) -> Unit) = { task -> historyRecoveryExecutor.execute(task) }
    private val historyRecoveryActive = AtomicBoolean(false)
    private val historyRecoveryAttempt = AtomicReference<YpsoBleManager.HistoryReadAttempt?>()
    private val idleDisconnectDeferredToHistory = AtomicBoolean(false)
    private val lowerBoundRecoveryRequested = AtomicBoolean(false)
    private val foregroundConnectionLease = AtomicBoolean(false)
    private val visibilityListener: (Boolean) -> Unit = ::onAppVisibilityChanged

    internal fun requestLowerBoundHistoryRecovery(enqueue: () -> Boolean): Boolean {
        if (provisioning.owner.committedRecord()?.writeBootstrapState != PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND ||
            !lowerBoundRecoveryRequested.compareAndSet(false, true)
        ) return false
        return try {
            enqueue().also { accepted -> if (!accepted) lowerBoundRecoveryRequested.compareAndSet(true, false) }
        } catch (error: RuntimeException) {
            lowerBoundRecoveryRequested.compareAndSet(true, false)
            throw error
        }
    }
    internal fun readinessStatus(): String = bleManager.readinessStatus()
    @Volatile private var historyRecoveryEnabled = true

    init {
        provisioning.availabilityChanged = { publishAvailabilityNotification() }
    }

    override val pumpDescription: PumpDescription = PumpDescription().fillFor(PumpType.YPSOPUMP).apply {
        isBolusCapable = !YpsoPumpConst.READ_ONLY_MODE
        isExtendedBolusCapable = !YpsoPumpConst.READ_ONLY_MODE
        extendedBolusDurationStep = 15.0
        extendedBolusMaxDuration = 12.0 * 60.0
        isTempBasalCapable = !YpsoPumpConst.READ_ONLY_MODE
        isSetBasalProfileCapable = false
        supportsTDDs = false
        needsManualTDDLoad = false
    }

    private fun notImplemented(): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false).comment(rh.gs(R.string.ypsopump_not_implemented))

    // ---- state (read-only) ----
    // Setup completion is durable and must not disappear merely because an idle BLE link closes or a
    // fresh status read is in progress. Command readiness still independently requires a live,
    // authenticated connection and current status at the dispatch boundary.
    override fun isInitialized(): Boolean = provisioning.installed()?.verifiedAt != null
    // A status-only build must not drive AAPS running-mode transitions from the still-unverified delivery
    // mode byte. The status-only artifact exposes this state without enabling dose requests.
    override fun isSuspended(): Boolean = !YpsoPumpConst.READ_ONLY_MODE && (pumpState.isSuspended || reservoirEmpty())
    // Background accounting is abandonable and must never hold the serialized therapy queue.
    override fun isBusy(): Boolean = bolusController.isBusy || tbrController.isBusy
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
        if (reason == "Connection needed") {
            idleDisconnectDeferredToHistory.set(false)
            cancelHistoryRecovery()
        }
        if (!configured()) {
            bleManager.disconnect()
            pumpState.invalidateStatus()
            aapsLogger.info(
                LTag.PUMP,
                "YpsoPump: no protected pump session configured — skipping connect (${provisioning.ownershipStatus()})",
            )
            // A failed replacement whose only fallback is the retained legacy bundle is being restored
            // asynchronously; do not overwrite its recorded failure with an unconfigured condition.
            if (!provisioning.isSessionRestorePending())
                provisioning.recordUnavailable(setOf(PumpSession.AvailabilityCause.UNCONFIGURED), operation = "connect")
            return
        }
        if (reason == "Connection needed" &&
            (commandQueue.bolusInQueue() || commandQueue.extendedBolusInQueue())
        ) {
            provisioning.requestTherapyConnectionAttempt()
        }
        seedAndConnect()
    }

    override fun disconnect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "disconnect: $reason")
        if (reason == "Queue empty" && appLifecycle.uiVisible) {
            aapsLogger.debug(LTag.PUMP, "YpsoPump queue-empty disconnect suppressed by foreground connection lease")
            return
        }
        if (reason == "Queue empty" && historyRecoveryActive.get()) {
            idleDisconnectDeferredToHistory.set(true)
            aapsLogger.debug(LTag.PUMP, "YpsoPump queue-empty disconnect deferred until history recovery releases the connection")
            return
        }
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
        val statusRead = readStatusBlocking()
        if (reason in setOf(PROFILE_READ_REASON, ACTIVE_PROGRAM_REASON)) {
            val success = statusRead && bleManager.canReadProfile && readProfileBlocking(activeOnly = reason == ACTIVE_PROGRAM_REASON)
            // Compare before publishing the message, so the result names the consequence of the read
            // rather than only that the transfer finished.
            reconcileProfileWithLoop()
            pumpState.profileReadMessage = when {
                !success                                                                -> rh.gs(R.string.ypsopump_profile_read_incomplete)
                pumpState.profileComparison == YpsoPumpState.ProfileComparison.MISMATCH ->
                    rh.gs(R.string.ypsopump_profile_read_mismatch, pumpState.lastReadProgram)

                pumpState.profileComparison == YpsoPumpState.ProfileComparison.MATCHES  ->
                    rh.gs(R.string.ypsopump_profile_read_matches, pumpState.lastReadProgram)

                else                                                                    -> rh.gs(R.string.ypsopump_profile_read_complete)
            }
        }
        // Status polling runs on AAPS' serialized pump-command queue. History selection can take many
        // BLE round trips (and a stalled transfer used to hold this queue for two minutes), so it must
        // never run inline here: queued boluses and Stop would be unable to overtake it. Immediate
        // boluses reconcile their authoritative terminal history in awaitBolusTerminal().
        if (reason == LOWER_BOUND_RECOVERY_REASON) {
            if (statusRead && lowerBoundRecoveryRequested.compareAndSet(true, false)) {
                scheduleLowerBoundHistoryRecovery(reason)
            } else if (!statusRead) {
                lowerBoundRecoveryRequested.set(false)
            }
        } else if (statusRead) {
            scheduleHistoryRecovery(reason)
        }
    }

    override val lastDataTime: Long get() = pumpState.lastStatusTime
    override val lastBolusTime: Long? get() = pumpSync.expectedPumpState().bolus?.timestamp
    override val lastBolusAmount: Double? get() = pumpSync.expectedPumpState().bolus?.amount
    // Stored-profile evidence is independent of current TBR scaling and the desired AAPS profile.
    override val baseBasalRate: Double get() = pumpState.scheduledBaseBasalRateIfFresh() ?: 0.0
    override val reservoirLevel: Double get() = pumpState.statusSnapshot?.reservoirUnits ?: Double.NaN
    // The pump reports battery as 0–5 bars, not a percentage. AAPS consumers expect percent;
    // see the single canonical mapping at [YpsoPumpState.mappedBatteryPercent].
    override val batteryLevel: Int? get() = pumpState.mappedBatteryPercent

    /** Presentation boundary: driver situations become translated operator text only here. */
    private fun message(reason: YpsoBolusMessage): String = rh.gs(
        when (reason) {
            YpsoBolusMessage.ANOTHER_BOLUS_IN_PROGRESS        -> R.string.ypsopump_bolus_another_in_progress
            YpsoBolusMessage.BOLUS_CANCELLED_BEFORE_START     -> R.string.ypsopump_bolus_cancelled_before_start
            YpsoBolusMessage.PUMP_BUSY                        -> R.string.ypsopump_bolus_pump_busy
            YpsoBolusMessage.PUMP_UNREADABLE                  -> R.string.ypsopump_bolus_pump_unreadable
            YpsoBolusMessage.PUMP_STOPPED_OR_EMPTY            -> R.string.ypsopump_bolus_pump_stopped_or_empty
            YpsoBolusMessage.PUMP_NOT_SET_UP                  -> R.string.ypsopump_bolus_pump_not_set_up
            YpsoBolusMessage.PUMP_NOT_CONNECTED               -> R.string.ypsopump_bolus_pump_not_connected
            YpsoBolusMessage.PUMP_ALREADY_BOLUSING            -> R.string.ypsopump_bolus_pump_already_bolusing
            YpsoBolusMessage.PUMP_ALREADY_EXTENDED_BOLUSING   -> R.string.ypsopump_bolus_pump_already_extended
            YpsoBolusMessage.PUMP_RESTARTED                   -> R.string.ypsopump_bolus_pump_restarted
            YpsoBolusMessage.SYNC_IN_PROGRESS                 -> R.string.ypsopump_bolus_sync_in_progress
            YpsoBolusMessage.SAVING_PREVIOUS_DOSE             -> R.string.ypsopump_bolus_saving_previous
            YpsoBolusMessage.CARBS_NOT_STORED                 -> R.string.ypsopump_bolus_carbs_not_stored
            YpsoBolusMessage.CONNECTION_DROPPED_NO_INSULIN    -> R.string.ypsopump_bolus_connection_dropped
            YpsoBolusMessage.CONNECTION_DROPPED_WHILE_SENDING -> R.string.ypsopump_bolus_connection_dropped_sending
            YpsoBolusMessage.PUMP_DID_NOT_RESPOND             -> R.string.ypsopump_bolus_pump_no_response
            YpsoBolusMessage.MAY_HAVE_BEEN_GIVEN              -> R.string.ypsopump_bolus_may_have_been_given
            YpsoBolusMessage.COULD_NOT_COMPLETE               -> R.string.ypsopump_bolus_could_not_complete
            YpsoBolusMessage.NOT_MATCHED_TO_PUMP              -> R.string.ypsopump_bolus_not_matched
            YpsoBolusMessage.EXTENDED_NOT_MATCHED_TO_PUMP     -> R.string.ypsopump_bolus_extended_not_matched
            YpsoBolusMessage.EXTENDED_NOT_STARTED_BY_AAPS     -> R.string.ypsopump_bolus_extended_not_from_aaps
            YpsoBolusMessage.STOPPED_AMOUNT_UNKNOWN           -> R.string.ypsopump_bolus_stopped_amount_unknown
            YpsoBolusMessage.STOPPED_AMOUNT_NOT_SAVED         -> R.string.ypsopump_bolus_stopped_not_saved
            YpsoBolusMessage.NOT_CONFIRMED_FINISHED           -> R.string.ypsopump_bolus_not_confirmed_finished
            YpsoBolusMessage.EXTENDED_NOT_SAVED               -> R.string.ypsopump_bolus_extended_not_saved
            YpsoBolusMessage.EXTENDED_STOP_UNCONFIRMED        -> R.string.ypsopump_bolus_extended_stop_unconfirmed
            YpsoBolusMessage.EXTENDED_CANCEL_UNCONFIRMED      -> R.string.ypsopump_bolus_extended_cancel_unconfirmed
            YpsoBolusMessage.EXTENDED_CANCEL_NOT_SAVED        -> R.string.ypsopump_bolus_extended_cancel_not_saved
        }
    )

    private fun fail(reason: YpsoBolusMessage): PumpEnactResult =
        fail(R.string.ypsopump_bolus_failed, message(reason))

    private fun fail(stringRes: Int, vararg args: Any): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false).comment(rh.gs(stringRes, *args))

    /**
     * This pump is programmed by hand: AAPS never writes a schedule, so `enacted` is always false.
     *
     * Success here means "the pump was read, and it already holds exactly this schedule" — the whole
     * 48-setting A/B configuration plus the active program, compared against the effective AAPS
     * values. That is the same evidence a writing driver would have after reading back its own write,
     * so AAPS may record the effective profile switch. Reporting failure instead would be actively
     * harmful: without an effective profile switch [app.aaps.core.interfaces.profile.ProfileFunction]
     * has no running profile, the loop cannot dose at all, and the keepalive retries this request
     * every five minutes forever — sounding the failed-basal-update alarm each time, even while the
     * pump is delivering precisely the requested schedule.
     *
     * The retained configuration is last-read, not live; an unreported manual pump edit can outdate
     * it. That is the documented polling boundary, and it is why a divergence is surfaced loudly
     * rather than being silently tolerated.
     *
     * THERAPY GATE: this evidence is scoped by pump-session generation and timezone only, so it can
     * outlive an edit made on the pump between reads. That is acceptable while delivery is blocked
     * ([YpsoPumpConst.READ_ONLY_MODE] clears every dosing capability above and the dosing entry
     * points fail), because the recorded effective profile switch drives no insulin. Before enabling
     * therapy, this confirmation must additionally be bounded by current pump-side evidence — at
     * minimum active-program continuity plus detection of schedule edits — rather than by retained
     * configuration alone. See issue #14 and docs/driver.md.
     */
    override fun setNewBasalProfile(profile: Profile): PumpEnactResult {
        if (isThisProfileSet(profile))
            return pumpEnactResultProvider.get().success(true).enacted(false)
                .comment(rh.gs(R.string.ypsopump_profile_verified, pumpState.lastReadProgram))
        return if (pumpState.profileComparison == YpsoPumpState.ProfileComparison.MISMATCH)
            fail(R.string.ypsopump_profile_mismatch_action, pumpState.lastReadProgram)
        else fail(R.string.ypsopump_profile_unread_action)
    }

    // The effective values already include AAPS percentage and time shift. Comparing only the
    // current rate would be unsafe because A/B or a later/sub-hour interval may differ.
    override fun isThisProfileSet(profile: Profile): Boolean {
        val matches = pumpState.profileMatches(
            profile.getBasalValues().map {
                YpsoBasalSchedule.EffectiveSegment(it.timeAsSeconds, it.value)
            },
        )
        publishProfileComparisonNotification()
        return matches
    }

    /**
     * Compare the retained pump configuration against the profile AAPS is dosing with. A manual A/B
     * switch on the pump only becomes knowable here, so an explicit read/check must report its
     * consequence immediately rather than waiting for the next keepalive comparison.
     */
    internal fun reconcileProfileWithLoop() {
        val profile = profileFunction.getProfile()
        if (profile == null) {
            publishProfileComparisonNotification()
            return
        }
        isThisProfileSet(profile)
    }

    @Synchronized
    private fun publishProfileComparisonNotification() {
        // Deduplicate on the message, not the verdict: a second read can stay MISMATCH while naming a
        // different program, and NotificationStore keeps the existing text for a repeated ID. Keying on
        // the verdict alone would leave a B mismatch still reading "A".
        val message = if (pumpState.profileComparison == YpsoPumpState.ProfileComparison.MISMATCH)
            rh.gs(R.string.ypsopump_profile_mismatch_notification, pumpState.lastReadProgram) else null
        if (message == publishedProfileMismatch) return
        publishedProfileMismatch = message
        rxBus.send(EventDismissNotification(Notification.YPSOPUMP_PROFILE_MISMATCH))
        message ?: return
        uiInteraction.addNotification(Notification.YPSOPUMP_PROFILE_MISMATCH, message, Notification.URGENT)
    }

    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        if (YpsoPumpConst.READ_ONLY_MODE) return fail(R.string.ypsopump_read_only_bolus_blocked)
        if (!bolusController.beginDelivery()) return fail(YpsoBolusMessage.ANOTHER_BOLUS_IN_PROGRESS)
        try {
        return runCatching {
        if (detailedBolusInfo.carbs != 0.0) return fail(R.string.ypsopump_bolus_invalid, message(YpsoBolusMessage.CARBS_NOT_STORED))
        val constrainedMaximum = constraintsChecker.getMaxBolusAllowed().value()
        val request = runCatching {
            YpsoBolusRequestValidator.validate(
                detailedBolusInfo.insulin,
                detailedBolusInfo.bolusType.toYpsoTreatment(),
                constrainedMaximum,
            )
        }.getOrElse { return fail(R.string.ypsopump_bolus_invalid, it.message ?: "invalid request") }
        when (readTherapyStatus(stopWhen = { bolusController.cancellationRequested })) {
            TherapyStatusReadiness.READY              -> Unit
            TherapyStatusReadiness.CANCELLED          -> return fail(YpsoBolusMessage.BOLUS_CANCELLED_BEFORE_START)
            TherapyStatusReadiness.HISTORY_BUSY       -> return fail(YpsoBolusMessage.PUMP_BUSY)
            TherapyStatusReadiness.STATUS_UNAVAILABLE -> return fail(YpsoBolusMessage.PUMP_UNREADABLE)
        }
        if (pumpState.isSuspended || reservoirEmpty()) return fail(YpsoBolusMessage.PUMP_STOPPED_OR_EMPTY)
        val reboot = bleManager.session?.snapshot()?.reboot
            ?: return fail(YpsoBolusMessage.PUMP_UNREADABLE)
        historyIngestion.bolusReadiness(serialNumber(), reboot.toLong())?.let { return fail(it) }
        when (val result = bolusController.deliver(request)) {
            is YpsoImmediateBolusController.DeliveryResult.Started -> {
                recordProvisionalBolus(result.attempt, detailedBolusInfo.bolusType)
                awaitBolusTerminal(result.attempt.requestId, detailedBolusInfo.id, result.observedDeliveredUnits)
            }
            is YpsoImmediateBolusController.DeliveryResult.NotSent ->
                fail(result.reason)
            is YpsoImmediateBolusController.DeliveryResult.Uncertain -> {
                // Delivery may have happened. Account for it in full now; terminal history corrects the
                // amount later. Insulin that is possibly in the body must never be invisible to IOB.
                bolusController.currentAttempt()?.let { recordProvisionalBolus(it, detailedBolusInfo.bolusType) }
                pumpEnactResultProvider.get()
                    .success(false)
                    .enacted(true)
                    .bolusDelivered(0.0)
                    .comment(rh.gs(R.string.ypsopump_bolus_uncertain, message(result.reason)))
            }
        }
        }.getOrElse {
            aapsLogger.error(LTag.PUMP, "YpsoPump bolus lifecycle failed: ${it.message}")
            fail(R.string.ypsopump_bolus_failed, it.message ?: message(YpsoBolusMessage.COULD_NOT_COMPLETE))
        }
        } finally {
            bolusController.finishDelivery()
            // The recorded amount is the requested one until the terminal history row proves what was
            // actually delivered, so reconcile in the background without delaying this command.
            scheduleHistoryRecovery("bolus delivery")
        }
    }

    override fun stopBolusDelivering() {
        cancelHistoryRecovery()
        if (!YpsoPumpConst.READ_ONLY_MODE) runCatching { bolusController.requestStop() }
            .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump stop bolus failed: ${it.message}") }
    }

    /**
     * Records the dispatched dose immediately so it reaches IOB without waiting for pump history. The
     * full requested amount is the conservative figure; the terminal history row later replaces it with
     * the delivered amount through [provisionalTemporaryId], which also prevents a duplicate record.
     */
    private fun recordProvisionalBolus(attempt: YpsoBolusAttempt, type: BS.Type) {
        if (attempt.shape != YpsoBolusShape.IMMEDIATE) return
        val timestamp = attempt.dispatchedAt ?: attempt.createdAt
        runCatching {
            pumpSync.addBolusWithTempId(
                timestamp,
                attempt.requestedUnits,
                provisionalTemporaryId(attempt),
                type,
                PumpType.YPSOPUMP,
                serialNumber(),
            )
        }.onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump provisional bolus accounting failed: ${it.message}") }
    }

    /**
     * Links the provisional record for this dose to the pump identity of its terminal history row, so
     * the authoritative amount updates that record rather than inserting a second one.
     */
    private fun bindProvisionalBolusToPumpId(
        pumpSerial: String,
        pumpId: Long,
        timestamp: Long,
        amount: Double,
        type: BS.Type,
        knownAttempt: YpsoBolusAttempt? = null,
    ) {
        val attempt = knownAttempt ?: run {
            val candidates = YpsoBolusAttemptFileStore(java.io.File(bleManager.noBackupDirectory(), "ypsopump-bolus-attempt.json"))
                .loadAll().filter { it.shape == YpsoBolusShape.IMMEDIATE && it.pumpSerial == pumpSerial && it.accountingPumpId == pumpId }
            check(candidates.size <= 1) { "multiple bolus attempts claim the same pump history identity" }
            candidates.singleOrNull() ?: return
        }
        if (::persistenceLayer.isInitialized) {
            persistenceLayer.syncPumpBolusWithTempId(
                BS(timestamp = timestamp, amount = amount, type = type,
                    ids = app.aaps.core.data.model.IDs(temporaryId = provisionalTemporaryId(attempt),
                        pumpId = pumpId, pumpType = PumpType.YPSOPUMP, pumpSerial = pumpSerial)), type,
            ).blockingGet()
            return
        }
        run {
            pumpSync.syncBolusWithTempId(
                timestamp,
                amount,
                provisionalTemporaryId(attempt),
                type,
                pumpId,
                PumpType.YPSOPUMP,
                pumpSerial,
            )
        }
    }

    /** Stable per-attempt identity so the provisional record can be found again after a restart. */
    private fun provisionalTemporaryId(attempt: YpsoBolusAttempt): Long =
        attempt.requestId.hashCode().toLong() and 0xffffffffL or ((attempt.createdAt / 1000L) shl 32)

    private fun awaitBolusTerminal(
        requestId: String,
        progressId: Long,
        initiallyDelivered: Double,
        timeoutMs: Long = 90_000,
    ): PumpEnactResult {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var reportedDelivered = -1.0
        fun publishProgress(delivered: Double) {
            if (delivered <= reportedDelivered || delivered > BolusProgressData.insulin) return
            reportedDelivered = delivered
            BolusProgressData.delivered = delivered
            rxBus.send(EventOverviewBolusProgress(rh, delivered, progressId))
        }
        publishProgress(initiallyDelivered)
        var cancellationSignalled = false
        /** Highest same-command pump-reported delivered amount, or -1 before any status proved one. */
        var observedDeliveredCentiUnits = -1
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (bolusController.cancellationRequested && !cancellationSignalled) {
                // Stop is a request, not terminal evidence. Signal it promptly once, then continue
                // observing same-command status and authoritative type-2 history for partial delivery.
                cancellationSignalled = bolusController.requestStop() ==
                    YpsoImmediateBolusController.StopResult.DISPATCHED_OR_PENDING
            }
            val attempt = bolusController.currentAttempt()
            if (attempt?.requestId != requestId) {
                bolusController.markUnresolved("another request owns the bolus journal")
                publishUnresolvedBolusWarningIfNeeded()
                return pumpEnactResultProvider.get()
                    .success(false)
                    .enacted(true)
                    .bolusDelivered(0.0)
                    .comment(rh.gs(R.string.ypsopump_bolus_uncertain, message(YpsoBolusMessage.NOT_MATCHED_TO_PUMP)))
            }
            if (attempt.confirmedCentiUnits != null) {
                val delivered = attempt.confirmedUnits ?: 0.0
                publishProgress(delivered)
                return pumpEnactResultProvider.get()
                    .success(true)
                    .enacted(true)
                    .bolusDelivered(delivered)
                    .comment(rh.gs(R.string.ypsopump_bolus_completed, delivered))
            }
            val status = readBolusStatusBlocking()
            val provenSequence = attempt?.pumpFastSequence
            if (status != null && provenSequence != null &&
                status.fastSequence == provenSequence &&
                Math.round(status.totalProgrammedUnits * 100.0).toInt() == attempt.requestedCentiUnits
            ) {
                // This is same-command status evidence suitable for UI progress. Pump history remains
                // authoritative for the final delivered amount and PumpSync accounting.
                publishProgress(status.deliveredUnits)
                observedDeliveredCentiUnits = Math.round(status.deliveredUnits * 100.0).toInt()
            }
            // The pump announced that this exact dose finished. Insulin is already accounted for, so the
            // command completes here; background history replaces the requested amount with the
            // delivered one. History is never read on the therapy path: it cannot finish in time.
            if (attempt.blockTerminalAt != null) {
                val delivered = attempt.requestedUnits
                publishProgress(delivered)
                // A stop was requested, so the pump may have ended this dose early. The amount is not
                // knowable here, so report it as uncertain rather than claiming the full request was
                // given; history reconciliation corrects the record to what was actually delivered.
                if (cancellationSignalled || bolusController.cancellationRequested) {
                    publishUnresolvedBolusWarningIfNeeded()
                    return pumpEnactResultProvider.get()
                        .success(false)
                        .enacted(true)
                        .bolusDelivered(0.0)
                        .comment(rh.gs(R.string.ypsopump_bolus_uncertain, message(YpsoBolusMessage.STOPPED_AMOUNT_UNKNOWN)))
                }
                // The pump uses one terminal code for normal completion and for a cancellation made on
                // the pump itself, and the announcement carries no amount. Full delivery may therefore
                // only be claimed when same-command status actually observed it; otherwise the recorded
                // amount stays the request and history confirms the delivered figure shortly after.
                val proven = observedDeliveredCentiUnits == attempt.requestedCentiUnits
                return pumpEnactResultProvider.get()
                    .success(true)
                    .enacted(true)
                    .bolusDelivered(delivered)
                    .comment(
                        if (proven) rh.gs(R.string.ypsopump_bolus_completed, delivered)
                        else rh.gs(R.string.ypsopump_bolus_recorded, delivered)
                    )
            }
            Thread.sleep(250L)
        }
        // Insulin was recorded at dispatch, so nothing is unaccounted; only the exact amount is pending.
        bolusController.markUnresolved("the pump did not report the end of this bolus")
        publishUnresolvedBolusWarningIfNeeded()
        return pumpEnactResultProvider.get()
            .success(false)
            .enacted(true)
            .bolusDelivered(0.0)
            .comment(rh.gs(R.string.ypsopump_bolus_uncertain, message(YpsoBolusMessage.NOT_CONFIRMED_FINISHED)))
    }

    private fun deliverExtended(request: YpsoValidatedBolusRequest): PumpEnactResult {
        if (YpsoPumpConst.READ_ONLY_MODE) return fail(R.string.ypsopump_read_only_bolus_blocked)
        if (!bolusController.beginDelivery()) return fail(YpsoBolusMessage.ANOTHER_BOLUS_IN_PROGRESS)
        try {
            when (readTherapyStatus()) {
                TherapyStatusReadiness.READY              -> Unit
                TherapyStatusReadiness.HISTORY_BUSY       -> return fail(YpsoBolusMessage.PUMP_BUSY)
                TherapyStatusReadiness.CANCELLED,
                TherapyStatusReadiness.STATUS_UNAVAILABLE -> return fail(YpsoBolusMessage.PUMP_UNREADABLE)
            }
            if (pumpState.isSuspended || reservoirEmpty()) return fail(YpsoBolusMessage.PUMP_STOPPED_OR_EMPTY)
            val reboot = bleManager.session?.snapshot()?.reboot
                ?: return fail(YpsoBolusMessage.PUMP_UNREADABLE)
            historyIngestion.bolusReadiness(serialNumber(), reboot.toLong())?.let { return fail(it) }
            return when (val result = bolusController.deliver(request)) {
                is YpsoImmediateBolusController.DeliveryResult.Started -> {
                    val attempt = result.attempt
                    val start = requireNotNull(attempt.dispatchedAt)
                    val pumpId = attempt.pumpSlowSequence
                        ?.let { bolusHistoryPumpId(attempt.baseline.historyPumpId, it) }
                        ?: return uncertainExtendedDelivery(
                            YpsoBolusMessage.EXTENDED_NOT_SAVED,
                            "extended delivery identity was never journalled",
                        )
                    val synced = pumpSync.syncExtendedBolusWithPumpId(
                        start,
                        request.units,
                        request.durationMinutes * 60_000L,
                        false,
                        pumpId,
                        PumpType.YPSOPUMP,
                        serialNumber(),
                    )
                    if (!synced && !extendedAccountingMatches(pumpId, start, request.units, request.durationMinutes * 60_000L, serialNumber())) {
                        bolusController.markUnresolved("AAPS rejected the extended bolus accounting record")
                        publishUnresolvedBolusWarningIfNeeded()
                        return pumpEnactResultProvider.get().success(false).enacted(true)
                            .comment(rh.gs(R.string.ypsopump_bolus_uncertain, message(YpsoBolusMessage.EXTENDED_NOT_SAVED)))
                    }
                    pumpEnactResultProvider.get().success(true).enacted(true)
                        .duration(request.durationMinutes)
                        .absolute(request.units * 60.0 / request.durationMinutes)
                        .isPercent(false).isTempCancel(false)
                        .comment(rh.gs(R.string.ypsopump_bolus_started))
                }
                is YpsoImmediateBolusController.DeliveryResult.NotSent -> fail(result.reason)
                is YpsoImmediateBolusController.DeliveryResult.Uncertain ->
                    pumpEnactResultProvider.get().success(false).enacted(true)
                        .comment(rh.gs(R.string.ypsopump_bolus_uncertain, message(result.reason)))
            }
        } finally {
            bolusController.finishDelivery()
            // An extended dose whose identity could not be bound is still recoverable from its
            // terminal history row, so reconcile in the background without delaying this command.
            scheduleHistoryRecovery("extended bolus delivery")
        }
    }

    /**
      * The pump may be delivering, so the dose is reported as uncertain and never as not delivered.
      * [journalDetail] stays untranslated: it is durable diagnostic evidence, not operator text.
      */
    private fun uncertainExtendedDelivery(
        reason: YpsoBolusMessage,
        journalDetail: String,
        tempCancel: Boolean = false,
    ): PumpEnactResult {
        bolusController.markUnresolved(journalDetail)
        publishUnresolvedBolusWarningIfNeeded()
        return pumpEnactResultProvider.get().success(false).enacted(true).isTempCancel(tempCancel)
            .comment(rh.gs(R.string.ypsopump_bolus_uncertain, message(reason)))
    }

    @Synchronized
    private fun publishUnresolvedBolusWarningIfNeeded() {
        val message = if (bolusController.currentAttempt()?.hasUnresolvedWarning == true)
            rh.gs(R.string.ypsopump_bolus_uncertain_notification) else null
        if (message == publishedUnresolvedBolusWarning) return
        publishedUnresolvedBolusWarning = message
        rxBus.send(EventDismissNotification(Notification.YPSOPUMP_BOLUS_UNCERTAIN))
        message ?: return
        uiInteraction.addNotification(Notification.YPSOPUMP_BOLUS_UNCERTAIN, message, Notification.URGENT)
    }

    /** Everything that must happen after a status read lands. */
    private fun onStatusRead() {
        checkReservoir()
        if (!YpsoPumpConst.READ_ONLY_MODE) {
            bleManager.observedTbr()?.let { observation ->
                runCatching { tbrController.onStatus(observation) }
                    .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump TBR resolution failed: ${it.message}") }
            }
            publishTbrWarningIfNeeded()
        }
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
    private fun readStatusBlocking(timeoutMs: Long = 30_000, stopWhen: () -> Boolean = { false }): Boolean {
        var success = false
        val l = java.util.concurrent.CountDownLatch(1)
        val attempt = bleManager.readStatus {
            success = it
            l.countDown()
            if (it) onStatusRead()
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (!l.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (stopWhen() || android.os.SystemClock.elapsedRealtime() >= deadline) break
        }
        if (l.count == 0L) return success
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

    internal enum class TherapyStatusReadiness { READY, CANCELLED, HISTORY_BUSY, STATUS_UNAVAILABLE }

    /**
     * A background history scan owns the same logical BLE operation lane as status reads. A bolus
     * command therefore has to request a selector-safe yield and wait for ownership to be released
     * before starting its mandatory fresh status read. Otherwise [YpsoBleManager.readStatus] rejects
     * immediately even though the previously displayed pump status is valid.
     */
    internal fun readTherapyStatus(
        historyYieldTimeoutMs: Long = HISTORY_YIELD_GRACE_MS,
        statusTimeoutMs: Long = 30_000L,
        stopWhen: () -> Boolean = { false },
    ): TherapyStatusReadiness {
        if (!yieldHistoryRecoveryForTherapy(historyYieldTimeoutMs)) return TherapyStatusReadiness.HISTORY_BUSY
        if (stopWhen()) return TherapyStatusReadiness.CANCELLED
        if (readStatusBlocking(statusTimeoutMs, stopWhen)) return TherapyStatusReadiness.READY
        return if (stopWhen()) TherapyStatusReadiness.CANCELLED else TherapyStatusReadiness.STATUS_UNAVAILABLE
    }

    private fun readProfileBlocking(
        timeoutMs: Long = 120_000,
        activeOnly: Boolean = false,
        yieldForQueue: Boolean = true,
        stopWhen: () -> Boolean = { false },
    ): Boolean {
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)
        val onDone: (Boolean) -> Unit = {
            success = it
            latch.countDown()
        }
        val attempt = bleManager.readProfileConfiguration(
            activeOnly,
            { stopWhen() || (yieldForQueue && (commandQueue.size() > 0 || commandQueue.bolusInQueue())) },
            onDone,
        )
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (!latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (stopWhen() || android.os.SystemClock.elapsedRealtime() >= deadline) break
        }
        if (latch.count == 0L) return success
        if (!attempt.cancel()) {
            latch.await()
            return success
        }
        aapsLogger.error(LTag.PUMP, "YpsoPump profile read timed out after ${timeoutMs}ms")
        bleManager.disconnect()
        return false
    }

    private fun readHistoryBlocking(
        timeoutMs: Long = 120_000,
        maxRows: Int = 128,
        stopWhen: () -> Boolean = { false },
        onAttempt: (YpsoBleManager.HistoryReadAttempt) -> Unit = {},
    ): YpsoHistorySnapshot? {
        var snapshot: YpsoHistorySnapshot? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        val attempt = bleManager.readStableHistory(historyIngestion.currentCursor(), maxRows) {
            snapshot = it
            latch.countDown()
        }
        onAttempt(attempt)
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var yielded = false
        while (!latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (stopWhen() && !yielded) {
                yielded = true
                attempt.requestYield()
            }
            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                // A deadline is also a cooperative stop: let an in-flight selector finish its
                // read-back instead of stranding a write reservation on an otherwise healthy link.
                attempt.requestYield()
                break
            }
        }
        if (latch.count == 0L) return snapshot
        if (attempt.shouldYield) {
            // A selector write that has left the phone must complete semantic read-back before the
            // therapy command can own the connection. Hard-cancelling here strands its reservation.
            if (latch.await(HISTORY_YIELD_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) return snapshot
        }
        if (!attempt.cancel()) {
            // The read already reached a completion path. Wait only briefly for its callback: an
            // unbounded wait here blocks the therapy command forever if that callback never arrives.
            latch.await(HISTORY_COMPLETION_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            return snapshot
        }
        if (yielded) {
            aapsLogger.error(LTag.PUMP, "YpsoPump history yield did not reach a safe boundary")
            bleManager.disconnect()
            return null
        }
        // A large scan can outlast this budget while individual reads succeed. Its decoded prefix
        // remains checkpointed for the next attempt.
        aapsLogger.warn(LTag.PUMP, "YpsoPump history read did not finish within ${timeoutMs}ms")
        return null
    }

    /**
     * Runs history/accounting independently of AAPS' serialized command execution. The operation is
     * finite (at most the pump's complete 3000-row ring), and immediately yields to any queued pump
     * command. Empty stores use a one-row anchor and therefore never import pre-install insulin.
     */
    private fun scheduleHistoryRecovery(reason: String) {
        // Never fail silently here: a skipped reconciliation leaves cancelled doses showing their
        // planned amount forever, and the reason must be visible in pump logs.
        val blocked = when {
            !historyRecoveryEnabled       -> "history recovery is disabled"
            !bleManager.isConnected       -> "not connected"
            !bleManager.canReadHistory    -> "pump session or another read owns the link"
            else                          -> null
        }
        if (blocked != null) {
            aapsLogger.debug(LTag.PUMP, "YpsoPump history recovery skipped after $reason: $blocked")
            return
        }
        if (!historyRecoveryActive.compareAndSet(false, true)) {
            aapsLogger.debug(LTag.PUMP, "YpsoPump history recovery skipped after $reason: already running")
            return
        }
        aapsLogger.debug(LTag.PUMP, "YpsoPump history recovery starting after $reason")
        dispatchHistoryRecovery {
            try {
                val cursor = historyIngestion.currentCursor()
                // The scan must reach the durable cursor row, because reconciliation can only prove no
                // events were missed by seeing it. A short scan reports COVERAGE_INCOMPLETE and is
                // discarded whole, so background recovery always reads the full ring.
                val maxRows = if (cursor == null) 1 else HISTORY_RECOVERY_MAX_ROWS
                val snapshot = readHistoryBlocking(
                    timeoutMs = HISTORY_RECOVERY_TIMEOUT_MS,
                    maxRows = maxRows,
                    stopWhen = ::historyRecoveryMustYield,
                    onAttempt = historyRecoveryAttempt::set,
                )
                // Report the outcome: a recovery that reads nothing leaves cancelled doses showing
                // their planned amount, and silence here hides that from the logs entirely.
                when {
                    snapshot == null              ->
                        aapsLogger.warn(LTag.PUMP, "YpsoPump history recovery after $reason read no usable history")
                    historyRecoveryMustYield()    ->
                        aapsLogger.debug(LTag.PUMP, "YpsoPump history recovery after $reason yielded to a pump command")
                    else                          ->
                        aapsLogger.debug(LTag.PUMP, "YpsoPump history recovery after $reason ingested: ${ingestHistory(snapshot)}")
                }
            } catch (exception: RuntimeException) {
                aapsLogger.error(LTag.PUMP, "YpsoPump history recovery failed after $reason: ${exception.message}")
            } finally {
                historyRecoveryAttempt.set(null)
                historyRecoveryActive.set(false)
                if (idleDisconnectDeferredToHistory.getAndSet(false) &&
                    commandQueue.size() == 0 && !bolusController.isBusy
                ) {
                    bleManager.disconnect(preserveStatus = true)
                }
            }
        }
    }

    private fun scheduleLowerBoundHistoryRecovery(reason: String) {
        if (!bleManager.isConnected) return
        if (!historyRecoveryActive.compareAndSet(false, true)) {
            lowerBoundRecoveryRequested.set(true)
            return
        }
        dispatchHistoryRecovery {
            try {
                val latch = java.util.concurrent.CountDownLatch(1)
                var result = YpsoBleManager.LowerBoundRecoveryResult.STOPPED
                fun attempt() {
                    bleManager.recoverHistorySelectorLowerBound { outcome ->
                        if (outcome == YpsoBleManager.LowerBoundRecoveryResult.COUNTER_TOO_LOW) {
                            try {
                                aapsLogger.info(
                                    LTag.PUMP,
                                    "YpsoPump lower-bound selector counter rejected; retrying persisted exponential candidate",
                                )
                                attempt()
                            } catch (exception: RuntimeException) {
                                aapsLogger.error(
                                    LTag.PUMP,
                                    "YpsoPump lower-bound selector recovery stopped: ${exception.message}",
                                )
                                result = YpsoBleManager.LowerBoundRecoveryResult.STOPPED
                                latch.countDown()
                            }
                        } else {
                            result = outcome
                            latch.countDown()
                        }
                    }
                }
                attempt()
                if (!latch.await(5, java.util.concurrent.TimeUnit.MINUTES)) {
                    aapsLogger.error(LTag.PUMP, "YpsoPump lower-bound history recovery timed out after $reason")
                    bleManager.disconnect()
                } else if (result != YpsoBleManager.LowerBoundRecoveryResult.RECOVERED) {
                    aapsLogger.info(LTag.PUMP, "YpsoPump lower-bound history recovery was not confirmed after $reason")
                }
            } catch (exception: RuntimeException) {
                aapsLogger.error(LTag.PUMP, "YpsoPump lower-bound history recovery failed after $reason: ${exception.message}")
            } finally {
                historyRecoveryActive.set(false)
                if (idleDisconnectDeferredToHistory.getAndSet(false) &&
                    commandQueue.size() == 0 && !bolusController.isBusy
                ) {
                    bleManager.disconnect(preserveStatus = true)
                }
            }
        }
    }

    private fun historyRecoveryMustYield(): Boolean =
        !historyRecoveryEnabled || bolusController.isBusy || tbrController.isBusy ||
            commandQueue.size() > 0 || commandQueue.bolusInQueue() || commandQueue.extendedBolusInQueue()

    private fun cancelHistoryRecovery() {
        historyRecoveryAttempt.get()?.requestYield()
    }

    /** Give an in-flight selector enough time to reconcile at a safe boundary before therapy I/O. */
    internal fun yieldHistoryRecoveryForTherapy(timeoutMs: Long = HISTORY_YIELD_GRACE_MS): Boolean {
        cancelHistoryRecovery()
        val deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (historyRecoveryActive.get() && System.nanoTime() < deadlineNanos) {
            Thread.sleep(25L)
        }
        return !historyRecoveryActive.get()
    }

    private fun ingestHistory(snapshot: YpsoHistorySnapshot): YpsoHistoryIngestionResult {
        logHistorySnapshotShape(snapshot)
        val serial = serialNumber()
        val zone = pumpState.historyZone
        val reboot = bleManager.session?.snapshot()?.reboot
        if (serial.isBlank() || reboot == null || bleManager.session?.activeRecord()?.generation == null) {
            aapsLogger.warn(LTag.PUMP, "YpsoPump history ingestion blocked: pump identity or session evidence unavailable")
            return YpsoHistoryIngestionResult.Blocked("pump identity or session evidence unavailable")
        }
        repairJournalledAccounting(serial)
        reconcileBolusAttempt(serial, zone, reboot, snapshot)
        publishUnresolvedBolusWarningIfNeeded()
        val attempt = bolusController.currentAttempt()
        val terminalSequence = when (attempt?.shape) {
            YpsoBolusShape.IMMEDIATE -> attempt.pumpFastSequence
            YpsoBolusShape.EXTENDED, YpsoBolusShape.COMBINED -> attempt.pumpSlowSequence
            null -> null
        }
        val terminalKinds = when (attempt?.shape) {
            YpsoBolusShape.IMMEDIATE -> setOf(app.aaps.pump.ypsopump.history.YpsoHistoryKind.IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED)
            YpsoBolusShape.EXTENDED -> setOf(app.aaps.pump.ypsopump.history.YpsoHistoryKind.DELAYED_BOLUS_COMPLETED)
            YpsoBolusShape.COMBINED -> setOf(app.aaps.pump.ypsopump.history.YpsoHistoryKind.COMBINED_BOLUS_COMPLETED)
            null -> emptySet()
        }
        if (attempt?.holdsTerminalRow(
                System.currentTimeMillis(),
                YpsoImmediateBolusController.OBSERVATION_WINDOW_MS,
                YpsoImmediateBolusController.EXTENDED_RECONCILIATION_MARGIN_MS,
            ) == true && terminalSequence != null && snapshot.rowsNewestFirst.any {
                it.sequence == terminalSequence && app.aaps.pump.ypsopump.history.YpsoHistoryClassifier.classify(it).kind in terminalKinds
            }) {
            return YpsoHistoryIngestionResult.Blocked("terminal bolus event is awaiting identity reconciliation")
        }
        val result = historyIngestion.ingest(serial, zone, reboot.toLong(), snapshot) { event ->
            if (attempt?.pumpHistoryId == event.identity.aapsPumpId) {
                when (attempt.treatment) {
                    app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment.NORMAL -> app.aaps.core.data.model.BS.Type.NORMAL
                    app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment.SMB -> app.aaps.core.data.model.BS.Type.SMB
                    app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment.PRIME -> app.aaps.core.data.model.BS.Type.PRIMING
                }
            } else app.aaps.core.data.model.BS.Type.NORMAL
        }
        if (result is YpsoHistoryIngestionResult.Blocked) {
            aapsLogger.error(LTag.PUMP, "YpsoPump history ingestion blocked: ${result.reason}")
        }
        return result
    }

    /** Repairs earlier imports using journalled identities, never matching by dose or proximity. */
    private fun repairJournalledAccounting(serial: String) {
        if (!::persistenceLayer.isInitialized) return
        val attempts = YpsoBolusAttemptFileStore(java.io.File(bleManager.noBackupDirectory(), "ypsopump-bolus-attempt.json"))
            .loadAll().filter { it.pumpSerial == serial && it.accountingPumpId != null && it.dispatchedAt != null }
        if (attempts.isEmpty()) return
        val from = attempts.minOf { it.dispatchedAt!! }.coerceAtLeast(1L) - 60_000L
        val records = persistenceLayer.getBolusesFromTime(from, true).blockingGet()
            .filter { it.ids.pumpType == PumpType.YPSOPUMP && it.ids.pumpSerial == serial }
        for (attempt in attempts) {
            val id = attempt.accountingPumpId!!
            check(attempts.count { it.accountingPumpId == id } == 1) { "ambiguous journalled pump bolus identity" }
            if (attempt.shape == YpsoBolusShape.IMMEDIATE) {
                val temporaryId = provisionalTemporaryId(attempt)
                val provisional = records.singleOrNull { it.ids.temporaryId == temporaryId } ?: continue
                val imported = records.singleOrNull { it.ids.pumpId == id } ?: continue
                if (provisional.id == imported.id) continue
                // The imported amount is already pump-confirmed. This transaction atomically merges
                // both records even if their timestamps predate the current driver activation.
                persistenceLayer.syncPumpBolusWithTempId(
                    imported.copy(ids = imported.ids.copy(temporaryId = temporaryId)), provisional.type,
                ).blockingGet()
                aapsLogger.info(LTag.PUMP, "YpsoPump merged journalled provisional bolus with pump event $id")
            } else {
                val existing = pumpSync.getExtendedBolusWithPumpId(id, PumpType.YPSOPUMP, serial) ?: continue
                if (attempt.cancelStoppedAt == null && attempt.blockTerminalAt == null) continue
                val delivered = Math.round(existing.amount * 100).toInt()
                val window = YpsoExtendedBolusAccounting.terminalWindow(attempt, delivered, System.currentTimeMillis())
                // A terminal history row may already have supplied a shorter elapsed window.
                // The observed stop is an upper bound, not permission to extend that delivery again.
                if (existing.duration <= window.duration) continue
                pumpSync.correctExtendedBolusWithPumpId(existing.timestamp, existing.amount, window.duration,
                    existing.isEmulatingTempBasal, id, PumpType.YPSOPUMP, serial)
            }
        }
    }

    /**
     * Diagnostic only. Reconciliation must find the durable cursor row inside the scan to prove no
     * events were missed, so when it cannot, the useful evidence is what it was looking for versus
     * what the ring actually returned. Amounts are not logged.
     */
    private fun logHistorySnapshotShape(snapshot: YpsoHistorySnapshot) {
        val cursor = historyIngestion.currentCursor()
        val rows = snapshot.rowsNewestFirst
        val cursorIndex = cursor?.let { target ->
            rows.indexOfFirst { it.sequence == target.identity.sequence && it.fingerprint() == target.fingerprint }
        } ?: -1
        val sequenceOnlyIndex = cursor?.let { target ->
            rows.indexOfFirst { it.sequence == target.identity.sequence }
        } ?: -1
        aapsLogger.debug(
            LTag.PUMP,
            "YpsoPump history snapshot: count=${snapshot.countBefore}/${snapshot.countAfter} rows=${rows.size} " +
                "fullCoverage=${snapshot.fullCoverage} cursorSeq=${cursor?.identity?.sequence} " +
                "cursorFoundAt=$cursorIndex sequenceOnlyMatchAt=$sequenceOnlyIndex " +
                "headSeq=${rows.firstOrNull()?.sequence} oldestScannedSeq=${rows.lastOrNull()?.sequence}",
        )
        if (cursorIndex < 0 && sequenceOnlyIndex >= 0) {
            val row = rows[sequenceOnlyIndex]
            aapsLogger.warn(
                LTag.PUMP,
                "YpsoPump history cursor sequence ${row.sequence} matched at index $sequenceOnlyIndex but its " +
                    "fingerprint changed: type=${row.eventType} storedFingerprint=${cursor?.fingerprint} " +
                    "rowFingerprint=${row.fingerprint()}",
            )
        }
    }

    private fun reconcileBolusAttempt(serial: String, zone: java.time.ZoneId, reboot: Int, snapshot: YpsoHistorySnapshot) {
        val attempt = bolusController.currentAttempt() ?: return
        if (!attempt.awaitsReconciliation) return
        if (attempt.pumpSerial != serial || attempt.sessionGeneration != bleManager.session?.activeRecord()?.generation ||
            attempt.baseline.pumpReboot != reboot) {
            bolusController.markUnresolved("pump identity epoch changed before terminal bolus reconciliation")
            return
        }
        val cursor = app.aaps.pump.ypsopump.history.YpsoHistoryCursor(
            app.aaps.pump.ypsopump.history.YpsoEventIdentity(
                serial,
                (attempt.baseline.historyPumpId ushr 32).toInt(),
                attempt.baseline.historyPumpId and 0xffffffffL,
            ),
            app.aaps.pump.ypsopump.history.YpsoHistoryFingerprint(attempt.baseline.historyFingerprintHigh, attempt.baseline.historyFingerprintLow),
            reboot.toLong(),
        )
        val stable = YpsoHistoryReconciler.reconcile(cursor, snapshot) as? YpsoHistoryReconciliation.Stable ?: return
        if (attempt.shape != YpsoBolusShape.IMMEDIATE) {
            when (val resolution = YpsoExtendedBolusReconciler.reconcile(attempt, stable.stateUpdates + stable.newEventsOldestFirst)) {
                is YpsoExtendedBolusReconciliation.AttemptCompleted -> {
                    val historyTimestamp = (YpsoPumpLocalTime.resolve(resolution.event.entry.factorySeconds, zone) as? YpsoPumpLocalTime.Resolution.Resolved)
                        ?.instant?.toEpochMilli() ?: return
                    val observedWindow = YpsoExtendedBolusAccounting.terminalWindow(
                        attempt,
                        resolution.amountCentiUnits,
                        System.currentTimeMillis(),
                    )
                    val terminal = if (resolution.event.entry.eventType == 3)
                        observedWindow.copy(duration = YpsoExtendedBolusAccounting.squareHistoryDuration(
                            resolution.event.entry.value2, observedWindow.duration))
                    else observedWindow
                    pumpSync.correctExtendedBolusWithPumpId(
                        terminal.start,
                        resolution.amountCentiUnits / 100.0,
                        terminal.duration,
                        false,
                        resolution.event.identity.aapsPumpId,
                        PumpType.YPSOPUMP,
                        serial,
                    )
                    if (!extendedAccountingMatches(
                            resolution.event.identity.aapsPumpId,
                            terminal.start,
                            resolution.amountCentiUnits / 100.0,
                            terminal.duration,
                            serial,
                        )) {
                        bolusController.markUnresolved("terminal extended bolus accounting was rejected")
                        publishUnresolvedBolusWarningIfNeeded()
                        return
                    }
                    bolusController.confirmTerminal(
                        resolution.amountCentiUnits,
                        terminal.end.coerceAtLeast(historyTimestamp),
                        resolution.event.identity.sequence,
                        resolution.event.identity.aapsPumpId,
                        cancelled = resolution.amountCentiUnits < attempt.requestedCentiUnits,
                    )
                }
                is YpsoExtendedBolusReconciliation.Unresolved ->
                    if (resolution.reason == YpsoExtendedBolusReconciliation.Reason.NO_COMPATIBLE_HISTORY) {
                        recoverRunningExtendedAccounting(attempt, serial)
                    } else {
                        bolusController.markUnresolved("extended history reconciliation: ${resolution.reason}")
                    }
            }
            return
        }
        val status = readBolusStatusBlocking()?.let {
            YpsoImmediateBolusStatus(
                it.fastSequence,
                it.bolusStatusCode,
                Math.round(it.totalProgrammedUnits * 100.0).toInt(),
                Math.round(it.deliveredUnits * 100.0).toInt(),
            )
        }
        when (val resolution = YpsoImmediateBolusReconciler.reconcile(attempt, status, stable.stateUpdates + stable.newEventsOldestFirst)) {
            is YpsoImmediateBolusReconciliation.AttemptCompleted -> {
                val timestamp = (YpsoPumpLocalTime.resolve(resolution.event.entry.factorySeconds, zone) as? YpsoPumpLocalTime.Resolution.Resolved)
                    ?.instant?.toEpochMilli() ?: return
                bolusController.confirmTerminal(
                    resolution.amountCentiUnits,
                    timestamp,
                    resolution.event.identity.sequence,
                    resolution.event.identity.aapsPumpId,
                    cancelled = resolution.amountCentiUnits < attempt.requestedCentiUnits,
                )
            }
            is YpsoImmediateBolusReconciliation.Unresolved -> {
                // A dose dispatched without ever proving its block identity still created a
                // provisional record. History is about to import the same physical insulin under a
                // pump identity the journal cannot supply, which would leave two records in IOB.
                // Binding is only safe because the reconciler fails closed on ambiguity: it offers
                // confirmed insulin only when exactly one compatible row exists in this window.
                resolution.confirmedInsulin
                    ?.takeIf { resolution.reason == YpsoImmediateBolusReconciliation.Reason.STATUS_IDENTITY_UNPROVEN }
                    ?.let { bindUnprovenProvisionalBolus(serial, attempt, it, zone) }
                if (resolution.reason != YpsoImmediateBolusReconciliation.Reason.NO_COMPATIBLE_HISTORY)
                    bolusController.markUnresolved("history reconciliation: ${resolution.reason}")
            }
            is YpsoImmediateBolusReconciliation.ConfirmedInsulin -> Unit
        }
    }

    /**
     * Merges the provisional record of a dose that never proved its pump block identity onto the
     * single compatible terminal history row, so one physical bolus keeps one record.
     *
     * This does not claim the dose was attributed: the attempt stays unresolved and visible to the
     * operator. It only prevents the same insulin from being counted twice while that is true.
     */
    private fun bindUnprovenProvisionalBolus(
        serial: String,
        attempt: YpsoBolusAttempt,
        confirmed: YpsoImmediateBolusReconciliation.ConfirmedInsulin,
        zone: java.time.ZoneId,
    ) {
        if (attempt.shape != YpsoBolusShape.IMMEDIATE || attempt.pumpSerial != serial) return
        if (attempt.dispatchedAt == null) return
        // The pump cannot deliver more than this command programmed, so a larger row belongs to a
        // different dose. Merging onto it would erase insulin, which is the fatal direction.
        if (confirmed.amountCentiUnits > attempt.requestedCentiUnits) return
        val timestamp = (YpsoPumpLocalTime.resolve(confirmed.event.entry.factorySeconds, zone) as? YpsoPumpLocalTime.Resolution.Resolved)
            ?.instant?.toEpochMilli() ?: return
        runCatching {
            bindProvisionalBolusToPumpId(
                serial,
                confirmed.event.identity.aapsPumpId,
                timestamp,
                confirmed.amountCentiUnits / 100.0,
                app.aaps.core.data.model.BS.Type.NORMAL,
                attempt,
            )
        }.onFailure {
            aapsLogger.error(LTag.PUMP, "YpsoPump unproven provisional bolus binding failed: ${it.message}")
        }
    }

    /** Waits for the authenticated link to come back so pump reads can succeed again. */
    private fun awaitConnection(timeoutMs: Long): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (bleManager.isConnected) return true
            Thread.sleep(250L)
        }
        return false
    }

    private fun readBolusStatusBlocking(timeoutMs: Long = 15_000): BolusCommand? {
        var status: BolusCommand? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        bleManager.readBolusStatus { status = it; latch.countDown() }
        return if (latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) status else null
    }

    private fun bolusHistoryPumpId(baselinePumpId: Long, sequence: Long): Long? =
        app.aaps.pump.ypsopump.history.YpsoBolusPumpIdentity.of(baselinePumpId, sequence)

    private fun extendedAccountingMatches(pumpId: Long, timestamp: Long, amount: Double, duration: Long, serial: String): Boolean =
        YpsoExtendedBolusAccounting.matches(
            pumpSync.getExtendedBolusWithPumpId(pumpId, PumpType.YPSOPUMP, serial),
            pumpId,
            timestamp,
            amount,
            duration,
            serial,
        )

    /** Repairs restart/crash gaps after durable slow-block identity proof but before PumpSync creation. */
    private fun recoverRunningExtendedAccounting(attempt: YpsoBolusAttempt, serial: String) {
        val start = attempt.dispatchedAt ?: return
        val duration = attempt.durationMinutes * 60_000L
        // A dispatch that lost its link before the slow sequence was journalled has no identity to
        // repair under. Its insulin is not lost: the terminal history row is ingested on its own
        // evidence below. Throwing here would abort every later ingestion instead.
        val sequence = attempt.pumpSlowSequence ?: return
        val pumpId = bolusHistoryPumpId(attempt.baseline.historyPumpId, sequence) ?: return
        if (extendedAccountingMatches(pumpId, start, attempt.requestedUnits, duration, serial)) return
        val status = readBolusStatusBlocking() ?: return
        if (status.extendedSequence != attempt.pumpSlowSequence ||
            status.extendedStatusCode !in setOf(BolusCommand.STATUS_DELIVERING, BolusCommand.STATUS_MIXED_DELIVERING) ||
            Math.round(status.extendedTotalUnits * 100.0).toInt() != attempt.requestedCentiUnits ||
            status.extendedMinutesTotal != attempt.durationMinutes ||
            Math.round(status.comboImmediateTotalUnits * 100.0).toInt() != attempt.immediateCentiUnits
        ) return
        val synced = pumpSync.syncExtendedBolusWithPumpId(
            start,
            attempt.requestedUnits,
            duration,
            false,
            pumpId,
            PumpType.YPSOPUMP,
            serial,
        )
        if (!synced && !extendedAccountingMatches(pumpId, start, attempt.requestedUnits, duration, serial)) {
            bolusController.markUnresolved("active extended bolus accounting recovery was rejected")
            publishUnresolvedBolusWarningIfNeeded()
        }
    }

    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        if (YpsoPumpConst.READ_ONLY_MODE) return fail(R.string.ypsopump_read_only_tbr_blocked)
        // AAPS schedules 100% as a cancellation; the pump itself treats it as the scheduled rate.
        if (percent == YpsoTbrRequest.STOP_PERCENT) return cancelTempBasal(enforceNew)
        val request = runCatching { YpsoTbrRequest(percent.coerceAtMost(YpsoTbrRequest.MAX_PERCENT), durationInMinutes) }
            .getOrElse { return fail(R.string.ypsopump_tbr_invalid, it.message ?: "invalid request") }
        return enactTbr(request, tbrType)
    }

    override fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        if (YpsoPumpConst.READ_ONLY_MODE) return fail(R.string.ypsopump_read_only_tbr_blocked)
        // The pump only runs percent TBRs. AAPS records percent TBRs against the profile rate at each
        // instant, and setNewBasalProfile only succeeds when the pump schedule matches that profile.
        val percent = runCatching { YpsoTbrRequest.percentFor(absoluteRate, profile.getBasal()) }
            .getOrElse { return fail(R.string.ypsopump_tbr_invalid, it.message ?: "invalid request") }
        return setTempBasalPercent(percent, durationInMinutes, profile, enforceNew, tbrType)
    }

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        if (YpsoPumpConst.READ_ONLY_MODE) return fail(R.string.ypsopump_read_only_tbr_cancel_blocked)
        return tbrResult(tbrController.cancel(), cancel = true)
    }

    private fun enactTbr(request: YpsoTbrRequest, type: PumpSync.TemporaryBasalType): PumpEnactResult =
        tbrResult(tbrController.start(request, type.name), cancel = false)

    /**
     * The loop delivers a paired SMB whenever `success || enacted`, so both stay false unless the pump
     * is proven to run exactly what was asked. A pump change that did not fulfil the request is
     * reported only through the persistent warning and the record of what was proven.
     */
    private fun tbrResult(result: YpsoTbrController.Result, cancel: Boolean): PumpEnactResult {
        scheduleHistoryRecovery("temporary basal")
        publishTbrWarningIfNeeded()
        return when (result) {
            is YpsoTbrController.Result.Started -> pumpEnactResultProvider.get().success(true).enacted(true)
                .isPercent(true).percent(result.request.percent).duration(result.request.durationMinutes)
                .comment(rh.gs(R.string.ypsopump_tbr_started, result.request.percent, result.request.durationMinutes))
            is YpsoTbrController.Result.Stopped ->
                if (cancel) pumpEnactResultProvider.get().success(true).enacted(result.enacted)
                    .isTempCancel(true).comment(rh.gs(R.string.ypsopump_tbr_cancelled))
                else fail(R.string.ypsopump_tbr_failed, tbrMessage(YpsoTbrController.Reason.START_NOT_CONFIRMED))
            is YpsoTbrController.Result.Failed -> fail(
                if (result.pumpChanged) R.string.ypsopump_tbr_uncertain else R.string.ypsopump_tbr_failed,
                tbrMessage(result.reason),
            ).isTempCancel(cancel)
        }
    }

    private fun tbrMessage(reason: YpsoTbrController.Reason): String = rh.gs(
        when (reason) {
            YpsoTbrController.Reason.BUSY,
            YpsoTbrController.Reason.COMMAND_NOT_SENT    -> R.string.ypsopump_tbr_not_sent
            YpsoTbrController.Reason.PUMP_UNREADABLE     -> R.string.ypsopump_bolus_pump_unreadable
            YpsoTbrController.Reason.PUMP_STOPPED        -> R.string.ypsopump_tbr_pump_stopped
            YpsoTbrController.Reason.NOT_SET_UP          -> R.string.ypsopump_bolus_pump_not_set_up
            YpsoTbrController.Reason.HISTORY_NOT_READY   -> R.string.ypsopump_bolus_sync_in_progress
            YpsoTbrController.Reason.STOP_NOT_CONFIRMED  -> R.string.ypsopump_tbr_stop_unconfirmed
            YpsoTbrController.Reason.START_REJECTED      -> R.string.ypsopump_tbr_start_rejected
            YpsoTbrController.Reason.START_NOT_CONFIRMED,
            YpsoTbrController.Reason.INTERNAL_ERROR      -> R.string.ypsopump_tbr_start_unconfirmed
            YpsoTbrController.Reason.NOT_SAVED           -> R.string.ypsopump_tbr_not_saved
        }
    )

    /** Derived from durable journal state, so it survives restarts and clears only on pump evidence. */
    @Synchronized
    private fun publishTbrWarningIfNeeded() {
        val unresolved = runCatching { tbrController.hasUnresolved() }.getOrDefault(true)
        if (unresolved == publishedUncertainTbr) return
        publishedUncertainTbr = unresolved
        rxBus.send(EventDismissNotification(Notification.YPSOPUMP_TBR_UNCERTAIN))
        if (unresolved) uiInteraction.addNotification(
            Notification.YPSOPUMP_TBR_UNCERTAIN, rh.gs(R.string.ypsopump_tbr_uncertain_notification), Notification.URGENT,
        )
    }

    @Volatile private var publishedUncertainTbr = false

    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult {
        val constrainedMaximum = constraintsChecker.getMaxExtendedBolusAllowed().value()
        val request = runCatching {
            YpsoBolusRequestValidator.validateDelivery(
                insulin,
                durationInMinutes,
                immediateUnits = 0.0,
                treatment = app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment.NORMAL,
                aapsMaxBolus = constrainedMaximum,
            )
        }.getOrElse { return fail(R.string.ypsopump_bolus_invalid, it.message ?: "invalid request") }
        return deliverExtended(request)
    }

    override fun cancelExtendedBolus(): PumpEnactResult {
        if (YpsoPumpConst.READ_ONLY_MODE) return fail(R.string.ypsopump_read_only_bolus_blocked)
        // Therapy cancellation outranks abandonable accounting. Do not overlap bolus status/cancel I/O
        // with an in-flight selector transaction; it must first reconcile at a selector-safe boundary.
        if (!yieldHistoryRecoveryForTherapy()) {
            return fail(YpsoBolusMessage.PUMP_BUSY)
        }
        val attempt = bolusController.currentAttempt()
        if (attempt == null || attempt.shape == YpsoBolusShape.IMMEDIATE || !attempt.awaitsReconciliation) {
            if (pumpSync.expectedPumpState().extendedBolus != null) {
                return fail(YpsoBolusMessage.EXTENDED_NOT_STARTED_BY_AAPS)
            }
            return pumpEnactResultProvider.get().success(true).enacted(false).isTempCancel(true)
        }
        if (!bolusController.beginDelivery()) return fail(YpsoBolusMessage.ANOTHER_BOLUS_IN_PROGRESS)
        try {
            bolusController.requestStop()
            val deadline = android.os.SystemClock.elapsedRealtime() + 90_000L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                bolusController.requestStop()
                val current = bolusController.currentAttempt()
                    ?: return fail(YpsoBolusMessage.EXTENDED_NOT_MATCHED_TO_PUMP)
                if (current.confirmedCentiUnits != null) {
                    return pumpEnactResultProvider.get().success(true).enacted(true).isTempCancel(true)
                        .comment(rh.gs(R.string.ypsopump_bolus_completed, current.confirmedUnits ?: 0.0))
                }
                current.cancelStoppedAt?.let { stoppedAt ->
                    return finishStatusConfirmedExtendedCancellation(
                        current,
                        requireNotNull(current.cancelObservedCentiUnits),
                        stoppedAt,
                    )
                }
                // The pump announced that this exact slow sequence stopped. That is terminal proof the
                // delivery ended; only the delivered amount is still outstanding, so resolve now and let
                // background history replace the elapsed estimate with the exact figure.
                current.blockTerminalAt?.let { return finishUnprovenExtendedCancellation(current) }
                if (!bleManager.isConnected) {
                    aapsLogger.debug(LTag.PUMP, "YpsoPump reconnecting to confirm extended bolus cancellation")
                    // Release the stale GATT client first; reconnecting without closing it registers a
                    // new client per attempt and exhausts the platform's client interfaces.
                    bleManager.disconnect(preserveStatus = true)
                    seedAndConnect()
                    if (!awaitConnection(10_000L)) { Thread.sleep(250L); continue }
                }
                val status = readBolusStatusBlocking()
                val observedAt = System.currentTimeMillis()
                val observed = status?.let { bolusController.observeCancelledStatus(it, observedAt) }
                if (observed?.cancelStoppedAt != null) {
                    return finishStatusConfirmedExtendedCancellation(
                        observed,
                        requireNotNull(observed.cancelObservedCentiUnits),
                        observed.cancelStoppedAt,
                    )
                }
                // History is deliberately not read here. A large scan cannot finish inside this window
                // and previously consumed it entirely, starving the status read that proves cancellation.
                // Background recovery reconciles the durable record after the command returns.
                Thread.sleep(250L)
            }
            return finishUnprovenExtendedCancellation(bolusController.currentAttempt())
        } finally {
            bolusController.finishDelivery()
            // Reconcile the authoritative record without holding up the command result.
            scheduleHistoryRecovery("extended bolus cancellation")
        }
    }

    /**
     * Closes a cancellation that returned without a pump-confirmed delivered amount.
     *
     * Shortening the record is only safe once the pump has proved that delivery stopped. A cancel
     * that was merely dispatched may never have reached the pump, so the insulin may still be
     * running; reducing the record then would hide insulin from IOB and invite stacking. The
     * programmed record therefore stands until pump evidence replaces it, which over-states insulin
     * in the safe direction and leaves background history to correct it.
     */
    private fun finishUnprovenExtendedCancellation(attempt: YpsoBolusAttempt?): PumpEnactResult {
        val uncertain = { reason: YpsoBolusMessage, journalDetail: String ->
            uncertainExtendedDelivery(reason, journalDetail, tempCancel = true)
        }
        val unconfirmed = YpsoBolusMessage.EXTENDED_CANCEL_UNCONFIRMED
        if (attempt == null) return uncertain(unconfirmed, "no bolus attempt owns this cancellation")
        // Only the pump's own terminal announcement proves this delivery ended.
        val announced = attempt.blockTerminalAt ?: return uncertain(
            YpsoBolusMessage.EXTENDED_STOP_UNCONFIRMED,
            "cancellation was dispatched but the pump never announced a terminal transition",
        )
        val sequence = attempt.pumpSlowSequence ?: return uncertain(
            unconfirmed, "extended delivery identity was never journalled",
        )
        val window = YpsoExtendedBolusAccounting.unprovenCancelWindow(attempt, announced)
        val centiUnits = YpsoExtendedBolusAccounting.elapsedCentiUnits(attempt, window)
        val pumpId = bolusHistoryPumpId(attempt.baseline.historyPumpId, sequence)
            ?: return uncertain(unconfirmed, "extended bolus identity generation is not representable")
        pumpSync.correctExtendedBolusWithPumpId(
            window.start,
            centiUnits / 100.0,
            window.duration,
            false,
            pumpId,
            PumpType.YPSOPUMP,
            serialNumber(),
        )
        if (!extendedAccountingMatches(pumpId, window.start, centiUnits / 100.0, window.duration, serialNumber())) {
            return uncertain(
                YpsoBolusMessage.EXTENDED_CANCEL_NOT_SAVED,
                "cancelled extended bolus accounting was rejected",
            )
        }
        // The recorded amount is only the elapsed schedule, so the attempt must stay open for
        // reconciliation: closing it here with confirmTerminal would make the estimate permanent.
        aapsLogger.info(
            LTag.PUMP,
            "YpsoPump extended bolus cancelled; recorded ${centiUnits / 100.0} U over ${window.duration / 60_000} min pending history",
        )
        return pumpEnactResultProvider.get().success(true).enacted(true).isTempCancel(true)
            .comment(rh.gs(R.string.ypsopump_bolus_completed, centiUnits / 100.0))
    }

    private fun finishStatusConfirmedExtendedCancellation(
        attempt: YpsoBolusAttempt,
        deliveredCentiUnits: Int,
        observedAt: Long,
    ): PumpEnactResult {
        // Terminal cancel evidence is only accepted against a proven slow sequence, so it exists here.
        val sequence = requireNotNull(attempt.pumpSlowSequence)
        val pumpId = bolusHistoryPumpId(attempt.baseline.historyPumpId, sequence)
            ?: return uncertainExtendedDelivery(
                YpsoBolusMessage.STOPPED_AMOUNT_NOT_SAVED,
                "extended bolus identity generation is not representable",
                tempCancel = true,
            )
        val terminal = YpsoExtendedBolusAccounting.terminalWindow(attempt, deliveredCentiUnits, observedAt)
        pumpSync.correctExtendedBolusWithPumpId(
            terminal.start,
            deliveredCentiUnits / 100.0,
            terminal.duration,
            false,
            pumpId,
            PumpType.YPSOPUMP,
            serialNumber(),
        )
        if (!extendedAccountingMatches(pumpId, terminal.start, deliveredCentiUnits / 100.0, terminal.duration, serialNumber())) {
            bolusController.markUnresolved("post-cancel extended bolus accounting was rejected")
            publishUnresolvedBolusWarningIfNeeded()
            return pumpEnactResultProvider.get().success(false).enacted(true).isTempCancel(true)
                .comment(rh.gs(R.string.ypsopump_bolus_uncertain, message(YpsoBolusMessage.STOPPED_AMOUNT_NOT_SAVED)))
        }
        bolusController.confirmTerminal(
            deliveredCentiUnits,
            terminal.end,
            sequence,
            pumpId,
            cancelled = deliveredCentiUnits < attempt.requestedCentiUnits,
        )
        return pumpEnactResultProvider.get().success(true).enacted(true).isTempCancel(true)
            .comment(rh.gs(R.string.ypsopump_bolus_completed, deliveredCentiUnits / 100.0))
    }
    override fun loadTDDs(): PumpEnactResult = notImplemented()

    // ---- identity ----
    override fun manufacturer(): ManufacturerType = ManufacturerType.Ypsomed
    override fun model(): PumpType = PumpType.YPSOPUMP
    override fun serialNumber(): String = pumpState.serialNumber

    override fun onStart() {
        super.onStart()
        bleManager.onBolusNotification = { bolusController.observeBolusNotification(it) }
        historyRecoveryEnabled = true
        publishedUnresolvedBolusWarning = null
        rxBus.send(EventDismissNotification(Notification.YPSOPUMP_BOLUS_UNCERTAIN))
        // Process death must not turn the finite 90-second immediate-bolus observation period into a
        // permanent therapy block. Identity and accounting evidence remain durable and recoverable.
        bolusController.expireStaleAttempt(
            YpsoImmediateBolusController.OBSERVATION_WINDOW_MS,
            YpsoImmediateBolusController.EXTENDED_RECONCILIATION_MARGIN_MS,
        )
        provisioning.refreshState()
        publishAvailabilityNotification()
        publishUnresolvedBolusWarningIfNeeded()
        publishedUncertainTbr = false
        rxBus.send(EventDismissNotification(Notification.YPSOPUMP_TBR_UNCERTAIN))
        if (!YpsoPumpConst.READ_ONLY_MODE) publishTbrWarningIfNeeded()
        appLifecycle.addVisibilityListener(visibilityListener)
        onAppVisibilityChanged(appLifecycle.uiVisible)
    }

    override fun onStop() {
        appLifecycle.removeVisibilityListener(visibilityListener)
        foregroundConnectionLease.set(false)
        historyRecoveryEnabled = false
        cancelHistoryRecovery()
        super.onStop()
        dismissAvailabilityNotification()
    }

    /**
     * myLife-style foreground lease: opening any AAPS screen requests one status command, which opens
     * and authenticates the GATT link. Queue-empty teardown is suppressed while the app remains in
     * front, so subsequent commands reuse that link. Backgrounding releases only an otherwise idle
     * link; an in-flight command/history operation keeps its normal ownership until completion.
     */
    internal fun onAppVisibilityChanged(visible: Boolean) {
        if (!visible) {
            foregroundConnectionLease.set(false)
            if (commandQueue.performing() == null && commandQueue.size() == 0 && !historyRecoveryActive.get()) {
                bleManager.disconnect(preserveStatus = true)
            }
            return
        }
        if (!foregroundConnectionLease.compareAndSet(false, true)) return
        provisioning.refreshState()
        publishAvailabilityNotification()
        if (!configured()) {
            foregroundConnectionLease.set(false)
            aapsLogger.info(
                LTag.PUMP,
                "YpsoPump foreground connection unavailable (${provisioning.ownershipStatus()})",
            )
            return
        }
        // Do not wait for QueueWorker's next loop to begin the radio handshake. Opening AAPS should
        // behave like myLife: start the authenticated GATT connection immediately, then let the
        // serialized queue perform the status read once the link is ready.
        seedAndConnect()
        if (!commandQueue.readStatus(FOREGROUND_CONNECTION_REASON, null)) {
            aapsLogger.info(LTag.PUMP, "YpsoPump foreground status command already queued or not accepted; retaining connection lease")
        }
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
        val profiles = PreferenceCategory(context).apply {
            key = "ypsopump_basal_configuration"
            title = rh.gs(R.string.ypsopump_basal_configuration)
        }
        parent.addPreference(profiles)
        for (action in listOf(
            ConfigurationAction(
                PROFILE_READ_REASON, R.string.ypsopump_read_profile,
                R.string.ypsopump_read_profile_summary, R.string.ypsopump_read_profile_started
            ),
            ConfigurationAction(
                ACTIVE_PROGRAM_REASON, R.string.ypsopump_check_program,
                R.string.ypsopump_check_program_summary, R.string.ypsopump_check_program_started
            ),
        )) {
            profiles.addPreference(Preference(context).apply {
                key = action.reason
                title = rh.gs(action.title)
                summary = rh.gs(action.summary)
                isEnabled = provisioning.isConfigured()
                setOnPreferenceClickListener { startConfigurationRead(context, action) }
            })
        }
    }

    /** A configuration action and the three things the user is told about it. */
    private data class ConfigurationAction(val reason: String, val title: Int, val summary: Int, val started: Int)

    /**
     * Reading takes noticeably longer than a tap, so silence is indistinguishable from a dead button:
     * confirm the tap immediately and report the outcome when it lands. The queue rejects a duplicate
     * itself, so a second tap is answered rather than silently dropped.
     */
    private fun startConfigurationRead(context: Context, action: ConfigurationAction): Boolean {
        // A full read runs for about a minute, well past the life of the settings screen that started
        // it. Report the outcome against the application context so the result still arrives, and a
        // closed screen cannot be leaked or written to.
        val appContext = context.applicationContext
        val accepted = commandQueue.readStatus(action.reason, object : app.aaps.core.interfaces.queue.Callback() {
            override fun run() {
                val outcome = if (result.success) pumpState.profileReadMessage.ifBlank { rh.gs(R.string.ypsopump_profile_read_incomplete) }
                else rh.gs(R.string.ypsopump_profile_read_incomplete)
                if (pumpState.profileComparison == YpsoPumpState.ProfileComparison.MISMATCH)
                    ToastUtils.warnToast(appContext, outcome)
                else ToastUtils.okToast(appContext, outcome)
            }
        })
        if (accepted) ToastUtils.infoToast(appContext, rh.gs(action.started))
        else ToastUtils.warnToast(appContext, rh.gs(R.string.ypsopump_profile_read_not_queued))
        return true
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
        uiInteraction.dismissNotification(Notification.YPSOPUMP_UNAVAILABLE)
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
        uiInteraction.dismissNotification(Notification.YPSOPUMP_UNAVAILABLE)
        publishedAvailabilityPresentation = null
        availabilityNotificationSynchronized = true
    }
    override val isFakingTempsByExtendedBoluses: Boolean = false
    override fun canHandleDST(): Boolean = false
    override fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
        // Configuration is pump-local and survives DST. A changed ZoneId inhibits comparison
        // in pumpState without destroying either stored schedule.
    }
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
        internal const val FOREGROUND_CONNECTION_REASON = "Ypso foreground connection"
        internal const val LOWER_BOUND_RECOVERY_REASON = "Ypso lower-bound history recovery"
        private const val HISTORY_COMPLETION_GRACE_MS = 2_000L
        private const val HISTORY_RECOVERY_MAX_ROWS = 3000
        private const val HISTORY_RECOVERY_TIMEOUT_MS = 10 * 60 * 1000L
        private const val HISTORY_YIELD_GRACE_MS = 10_000L
        internal const val PROFILE_READ_REASON = "YpsoPump explicit profile read"
        internal const val ACTIVE_PROGRAM_REASON = "YpsoPump explicit active program check"

        /** The pump reports remaining insulin in centi-units, so a true empty reads as exactly 0. */
        private const val RESERVOIR_EMPTY_UNITS = 0.0

    }
}
