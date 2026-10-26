package app.aaps.pump.ypsopump

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.configuration.Config
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
import app.aaps.core.interfaces.rx.events.EventProfileSwitchChanged
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.ConnectionState
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.data.YpsoBasalSchedule
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.bolus.YpsoBolusBleLink
import app.aaps.pump.ypsopump.bolus.YpsoBolusDelivery
import app.aaps.pump.ypsopump.bolus.YpsoBolusLink
import app.aaps.pump.ypsopump.bolus.YpsoBolusRecords
import app.aaps.pump.ypsopump.history.YpsoHistoryIngestion
import app.aaps.pump.ypsopump.history.YpsoHistoryIngestionResult
import app.aaps.pump.ypsopump.history.YpsoHistorySnapshot
import app.aaps.pump.ypsopump.bolus.YpsoBolusMessage
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * AndroidAPS pump plugin for the Ypsomed YpsoPump.
 *
 * Exposes connection/status data, immediate/square boluses and temporary basals.
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
    private val config: Config,
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

    @Inject lateinit var persistenceLayer: app.aaps.core.interfaces.db.PersistenceLayer

    private var publishedAvailabilityPresentation: PumpSetupPresentation? = null
    private val keyExpiryNotification = KeyExpiryNotification()
    private val keyExpiryExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ypso-key-reminder").apply { isDaemon = true }
    }
    private var keyExpiryTask: ScheduledFuture<*>? = null
    private var keyReminderEnabled = false
    private var keyReminderEpoch = 0L
    // Reminder storage/notification work must never hold the plugin's therapy monitor.
    private val keyReminderLock = Any()
    private val keyObservationLock = Any()
    /** Shared store can outlive this plugin instance; the first publication must reconcile its ID. */
    private var availabilityNotificationSynchronized = false
    /** Last published mismatch text, or null when no mismatch is currently published. */
    private var publishedProfileMismatch: String? = null
    /** Last published unresolved-bolus warning state, or null before the first publication. */
    @Volatile private var publishedUnresolvedBolusWarning: Boolean? = null
    private val tbrJournal by lazy {
        YpsoTbrJournal(YpsoTbrAttemptFileStore(java.io.File(bleManager.noBackupDirectory(), "ypsopump-tbr-attempts.json")))
    }
    private val historyIngestion by lazy {
        YpsoHistoryIngestion(
            YpsoHistoryStateFileStore(java.io.File(bleManager.noBackupDirectory(), "ypsopump-history-state.json")),
            pumpSync,
            hasRecordedBolus = { serial, pumpId -> bolus.ownsHistoryBolus(serial, pumpId) },
            resolveProvisional = { serial, pumpId, timestamp, amount, type -> bolus.bindProvisional(serial, pumpId, timestamp, amount, type) },
            registeredAt = ::pumpRegisteredAt,
            tbrAccounting = YpsoTbrHistoryAccounting(pumpSync, tbrJournal, object : YpsoTbrRecordLookup {
                // Row times move by the clock offset measured on each read; search well before this one.
                override fun byPumpId(pumpId: Long, pumpSerial: String, start: Long) =
                    tbrRecords(pumpSerial, start - 24 * 60 * 60_000L, includeInvalid = true).singleOrNull { it.ids.pumpId == pumpId }
                        ?.let { YpsoTbrRecordLookup.Record(it.timestamp, it.duration, it.isValid) }

                override fun suspendActiveAt(pumpSerial: String, at: Long) = activeTbrRecords(pumpSerial, at)
                    .any { it.type == app.aaps.core.data.model.TB.Type.PUMP_SUSPEND }

                override fun anyStartedBetween(pumpSerial: String, from: Long, to: Long, exceptPumpId: Long) =
                    tbrRecords(pumpSerial, from).any { it.timestamp > from && it.timestamp < to && it.ids.pumpId != exceptPumpId }

                override fun latestSuspendBefore(pumpSerial: String, at: Long) =
                    tbrRecords(pumpSerial, at - 24 * 60 * 60_000L, includeInvalid = true)
                        .filter {
                            it.type == app.aaps.core.data.model.TB.Type.PUMP_SUSPEND && it.timestamp <= at &&
                                (it.ids.pumpId != null || it.ids.temporaryId != null)
                        }
                        .maxByOrNull { it.timestamp }
                        ?.let { YpsoTbrRecordLookup.Suspend(it.ids.pumpId, it.ids.temporaryId, it.timestamp, it.duration, it.isValid) }

                override fun rowSuspendsAt(pumpSerial: String, at: Long) = tbrRecords(pumpSerial, at, includeInvalid = true)
                    .filter { it.type == app.aaps.core.data.model.TB.Type.PUMP_SUSPEND && it.timestamp == at && it.ids.pumpId != null }
                    .map { YpsoTbrRecordLookup.Suspend(it.ids.pumpId, it.ids.temporaryId, it.timestamp, it.duration, it.isValid) }

                override fun statusSuspendsFrom(pumpSerial: String, from: Long) = tbrRecords(pumpSerial, from)
                    .filter { it.type == app.aaps.core.data.model.TB.Type.PUMP_SUSPEND && it.ids.pumpId == null && it.timestamp >= from }
                    .mapNotNull { record -> record.ids.temporaryId?.let { YpsoTbrRecordLookup.StatusSuspend(it, record.timestamp, record.duration) } }
            }, registeredAt = ::pumpRegisteredAt),
        )
    }
    private val tbrControllerLazy = lazy {
        YpsoTbrController(
            YpsoTbrBleLink(bleManager, readStatus = { readTherapyStatus() == TherapyStatusReadiness.READY }),
            tbrJournal,
            tbrRecords,
            ::serialNumber,
            historyBaseline = { historyIngestion.currentCursor()?.identity?.aapsPumpId },
        )
    }
    private val tbrController by tbrControllerLazy

    /**
     * YpsoPump TBR records starting at or after [notBefore] (less a minute). Every caller knows the
     * start it wrote, so read-back works however old the record is.
     */
    private fun tbrRecords(serial: String, notBefore: Long, includeInvalid: Boolean = false): List<app.aaps.core.data.model.TB> {
        if (!::persistenceLayer.isInitialized) return emptyList()
        return persistenceLayer.getTemporaryBasalsStartingFromTimeIncludingInvalid(notBefore - 60_000L, false).blockingGet()
            .filter { (includeInvalid || it.isValid) && it.referenceId == null && it.ids.pumpType == PumpType.YPSOPUMP && it.ids.pumpSerial == serial }
    }

    /** Start of AAPS' use of this pump; PumpSync refuses pump records from before it. */
    private fun pumpRegisteredAt(serial: String): Long =
        if (preferences.get(app.aaps.core.keys.StringNonKey.ActivePumpSerialNumber) == serial)
            preferences.get(app.aaps.core.keys.LongNonKey.ActivePumpChangeTimestamp) else 0L

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
        private fun find(attempt: YpsoTbrAttempt, includeInvalid: Boolean = false): app.aaps.core.data.model.TB? =
            tbrRecords(attempt.pumpSerial, attempt.effectiveAt ?: attempt.createdAt, includeInvalid)
                .singleOrNull { it.ids.temporaryId == attempt.temporaryId || attempt.pumpId != null && it.ids.pumpId == attempt.pumpId }

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
            var allEnded = true
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
                // A record written moments ago may still be settling against this sample. A stopped
                // pump runs no TBR at all, so that contradiction is final at once.
                if (observation.running && record.timestamp > at - 60_000L) continue
                val netNegative = record.type == app.aaps.core.data.model.TB.Type.PUMP_SUSPEND || record.isAbsolute || record.rate < 100.0
                val end = if (netNegative) lastSeenMatching[record.id] ?: record.timestamp + 1L else at
                if (shorten(record, end)) lastSeenMatching.remove(record.id)
                else {
                    allEnded = false
                    aapsLogger.warn(LTag.PUMP, "YpsoPump could not end TBR record ${record.id} contradicted by status")
                }
            }
            // A stop record on top of a TBR record still open would count the missing basal twice.
            if (!observation.running && allEnded) recordStop(serial, at)
        }

        /**
         * A stopped pump delivers nothing, so AAPS must record zero basal as soon as status sees it,
         * not only when history reports the Stop row. The pump stopped at some point before this
         * observation; starting the record here keeps IOB on the high side. The Stop row later moves
         * the start to the pump's own time, and status or the Resume row ends it.
         */
        private fun recordStop(serial: String, at: Long) {
            if (activeTbrRecords(serial, at).any { it.type == app.aaps.core.data.model.TB.Type.PUMP_SUSPEND }) return
            val temporaryId = java.util.UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
            pumpSync.addTemporaryBasalWithTempId(
                at, 0.0, STATUS_STOP_WINDOW_MS, true, temporaryId, PumpSync.TemporaryBasalType.PUMP_SUSPEND, PumpType.YPSOPUMP, serial,
            )
            val saved = activeTbrRecords(serial, at).firstOrNull { it.ids.temporaryId == temporaryId }
            if (saved == null) aapsLogger.warn(LTag.PUMP, "YpsoPump could not record the pump stop seen by status")
            else lastSeenMatching[saved.id] = at
        }
    }
    private val bolusLazy: Lazy<YpsoBolusDelivery> = lazy {
        YpsoBolusDelivery(
            bolusLink,
            bolusRecords,
            java.io.File(bleManager.noBackupDirectory(), "ypsopump-bolus-attempt.json"),
            ::serialNumber,
            historyCursor = { historyIngestion.currentCursor() },
            historyReadiness = { serial, reboot -> historyIngestion.bolusReadiness(serial, reboot) },
            showWarning = ::showUnresolvedBolusWarning,
            aapsLogger = aapsLogger,
        )
    }
    private val bolus: YpsoBolusDelivery by bolusLazy

    /** The bolus module's pump: the BLE link, with this plugin handing it over from history. */
    private val bolusLink: YpsoBolusLink = object : YpsoBolusBleLink(bleManager) {
        override fun therapyStatus(stopWhen: () -> Boolean) = when (readTherapyStatus(stopWhen = stopWhen)) {
            TherapyStatusReadiness.READY              -> YpsoBolusLink.Readiness.READY
            TherapyStatusReadiness.CANCELLED          -> YpsoBolusLink.Readiness.CANCELLED
            TherapyStatusReadiness.HISTORY_BUSY       -> YpsoBolusLink.Readiness.HISTORY_BUSY
            TherapyStatusReadiness.STATUS_UNAVAILABLE -> YpsoBolusLink.Readiness.STATUS_UNAVAILABLE
        }

        override fun yieldHistory() = yieldHistoryRecovery()
        override fun stoppedOrEmpty() = pumpState.isSuspended || reservoirEmpty()
        override fun reconcileHistoryLater(reason: String) = scheduleHistoryRecovery(reason)

        override fun reconnect(timeoutMs: Long): Boolean {
            // Release the stale GATT client first; reconnecting without closing it registers a new
            // client per attempt and exhausts the platform's client interfaces.
            bleManager.disconnect(preserveStatus = true)
            seedAndConnect()
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (bleManager.isConnected) return true
                Thread.sleep(250L)
            }
            return false
        }
    }

    /** YpsoPump bolus records through PumpSync and the database. */
    private val bolusRecords: YpsoBolusRecords = object : YpsoBolusRecords {
        override fun addProvisional(timestamp: Long, units: Double, temporaryId: Long, type: BS.Type, pumpSerial: String) =
            pumpSync.addBolusWithTempId(timestamp, units, temporaryId, type, PumpType.YPSOPUMP, pumpSerial)

        override fun syncWithTemporaryId(bolus: BS, type: BS.Type) {
            if (::persistenceLayer.isInitialized) persistenceLayer.syncPumpBolusWithTempId(bolus, type).blockingGet()
            else pumpSync.syncBolusWithTempId(
                bolus.timestamp, bolus.amount, checkNotNull(bolus.ids.temporaryId), type, bolus.ids.pumpId,
                PumpType.YPSOPUMP, checkNotNull(bolus.ids.pumpSerial),
            )
        }

        override fun bindNotified(bolus: BS, type: BS.Type) =
            if (::persistenceLayer.isInitialized) persistenceLayer.bindPumpBolusToTempIdIfValid(bolus, type).blockingGet()
            else app.aaps.core.interfaces.db.PersistenceLayer.TempIdBinding.NO_RECORD

        override fun bolus(pumpId: Long, pumpSerial: String) =
            if (::persistenceLayer.isInitialized) persistenceLayer.getBolusByPumpId(pumpId, PumpType.YPSOPUMP, pumpSerial) else null

        override fun bolusesFrom(from: Long, pumpSerial: String) =
            if (!::persistenceLayer.isInitialized) emptyList()
            else persistenceLayer.getBolusesFromTime(from, true).blockingGet()
                .filter { it.ids.pumpType == PumpType.YPSOPUMP && it.ids.pumpSerial == pumpSerial }

        override fun extended(pumpId: Long, pumpSerial: String) = pumpSync.getExtendedBolusWithPumpId(pumpId, PumpType.YPSOPUMP, pumpSerial)

        override fun syncExtended(start: Long, units: Double, duration: Long, pumpId: Long, pumpSerial: String) =
            pumpSync.syncExtendedBolusWithPumpId(start, units, duration, false, pumpId, PumpType.YPSOPUMP, pumpSerial)

        override fun correctExtended(start: Long, units: Double, duration: Long, emulatingTempBasal: Boolean, pumpId: Long, pumpSerial: String) {
            pumpSync.correctExtendedBolusWithPumpId(start, units, duration, emulatingTempBasal, pumpId, PumpType.YPSOPUMP, pumpSerial)
        }

        override fun extendedRunning() = pumpSync.expectedPumpState().extendedBolus != null
    }
    private val historyRecoveryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ypso-history-recovery").apply { isDaemon = true }
    }
    internal var dispatchHistoryRecovery: ((() -> Unit) -> Unit) = { task -> historyRecoveryExecutor.execute(task) }
    /** Elapsed-time clock for blocking pump reads. */
    internal var elapsedRealtime: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    private var transientHistoryFailures = 0
    private val historyRecoveryActive = AtomicBoolean(false)
    private val historyRecoveryAttempt = AtomicReference<YpsoBleManager.HistoryReadAttempt?>()
    private val historyRecoveryLock = Any()
    @Volatile private var historyRecoveryYieldRequested = false
    private val idleDisconnectDeferredToHistory = AtomicBoolean(false)
    /** Held by every queued pump command and by the idle disconnect; see [onLink]. */
    private val linkUse = java.util.concurrent.locks.ReentrantLock()
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
        provisioning.durableBolusRecoveryEvidence = { bolus.recoveryEvidence() }
    }

    override val pumpDescription: PumpDescription = PumpDescription().fillFor(PumpType.YPSOPUMP).apply {
        isBolusCapable = true
        isExtendedBolusCapable = true
        extendedBolusDurationStep = 15.0
        extendedBolusMaxDuration = 12.0 * 60.0
        isTempBasalCapable = true
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
    override fun isSuspended(): Boolean = pumpState.isSuspended || reservoirEmpty()
    // Background accounting is abandonable and must never hold the serialized therapy queue.
    override fun isBusy(): Boolean = bolus.isBusy || tbrController.isBusy
    override fun isConnected(): Boolean = pumpState.isConnected
    override fun isConnecting(): Boolean = pumpState.connectionState == ConnectionState.CONNECTING
    override fun isHandshakeInProgress(): Boolean =
        pumpState.connectionState == ConnectionState.DISCOVERING || pumpState.connectionState == ConnectionState.READY

    private fun resolvedMac(): String = bleManager.installedPumpMac()
    /** A journal without protected credentials is intentionally not connectable. */
    private fun configured(): Boolean = provisioning.isConfigured() && resolvedMac().isNotEmpty()

    private fun seedAndConnect() {
        // Counted from before the retry grant is taken until BLE reports CONNECTING or fails, so setup never
        // sees a consumed attempt with nothing under way while this one is still starting.
        pumpState.connectionsStarting.incrementAndGet()
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
        } finally {
            pumpState.connectionsStarting.decrementAndGet()
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
            provisioning.recordUnavailable(setOf(PumpSession.AvailabilityCause.UNCONFIGURED), operation = "connect")
            return
        }
        if (reason == "Connection needed" &&
            (commandQueue.bolusInQueue() || commandQueue.extendedBolusInQueue())
        ) {
            provisioning.requestConnectionAttempt()
        }
        // Only a status read checks a pending key. If the queue dropped the read requested on save, the
        // queue connecting for any other command (a KeepAlive profile sync, say) must also check it.
        if (reason == "Connection needed" && provisioning.pending() != null && !commandQueue.statusInQueue()) {
            commandQueue.ensureStatusReadQueued(PENDING_KEY_CHECK_REASON)
        }
        seedAndConnect()
    }

    override fun disconnect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "disconnect: $reason")
        if (reason == "Queue empty") {
            if (appLifecycle.uiVisible) {
                aapsLogger.debug(LTag.PUMP, "YpsoPump queue-empty disconnect suppressed by foreground connection lease")
                return
            }
            if (historyRecoveryActive.get()) {
                idleDisconnectDeferredToHistory.set(true)
                aapsLogger.debug(LTag.PUMP, "YpsoPump queue-empty disconnect deferred until history recovery releases the connection")
                return
            }
            releaseIdleConnection("queue empty")
            return
        }
        bleManager.disconnect()
    }

    /**
     * The only place an idle connection is closed. Commands never manage the link themselves: while
     * any queued or running command, therapy controller or history read needs it, it stays open.
     * A link fault still tears the connection down wherever it is detected.
     */
    private fun releaseIdleConnection(reason: String) {
        // Commands run under the same lock, so none can start between the check and the disconnect.
        // A running command holds it, and the link is not idle then; this never waits for it, so it is
        // safe on the main thread. A command queued meanwhile finds the link closed and reopens it.
        if (!linkUse.tryLock()) {
            aapsLogger.debug(LTag.PUMP, "YpsoPump idle disconnect after $reason skipped: a pump command uses the connection")
            return
        }
        try {
            val user = when {
                commandQueue.performing() != null || commandQueue.size() > 0 -> "a queued pump command"
                bolusLazy.isInitialized() && bolus.isBusy                   -> "a bolus command"
                tbrControllerLazy.isInitialized() && tbrController.isBusy   -> "a TBR command"
                historyRecoveryActive.get()                                 -> "history recovery"
                else                                                        -> null
            }
            if (user != null) {
                aapsLogger.debug(LTag.PUMP, "YpsoPump idle disconnect after $reason skipped: $user uses the connection")
                return
            }
            bleManager.disconnect(preserveStatus = true)
        } finally {
            linkUse.unlock()
        }
    }

    /**
     * Runs one queued pump command. It holds [linkUse] for its whole run, so no idle disconnect can
     * close the link under it; a link fault still disconnects at once. If an idle disconnect closed
     * the link just before a therapy command took the lock, the link is reopened first.
     */
    private fun <T> onLink(reopen: Boolean = true, command: () -> T): T {
        linkUse.lock()
        try {
            if (reopen && !bleManager.isConnected && configured()) {
                seedAndConnect()
                val deadline = android.os.SystemClock.elapsedRealtime() + RECONNECT_FOR_COMMAND_MS
                while (!bleManager.isConnected && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100L)
            }
            return command()
        } finally {
            linkUse.unlock()
        }
    }
    override fun stopConnecting() { bleManager.disconnect() }

    // A pending replacement key is checked by the first status read after it is staged. Staging closes
    // the old link, so a read picked up just then would only start the connection and be consumed;
    // reopen and read instead, or the check waits for an unrelated later poll.
    override fun getPumpStatus(reason: String) = onLink(reopen = provisioning.pending() != null) {
        val callback = commandQueue.performing()
            ?.takeIf { it.commandType == app.aaps.core.interfaces.queue.Command.CommandType.READSTATUS }
            ?.callback as? ConfigurationReadCallback
        getPumpStatusNow(reason)
        if (callback?.reason == reason) {
            val message = if (configurationReadSucceeded) pumpState.profileReadMessage.ifBlank { rh.gs(R.string.ypsopump_profile_read_incomplete) }
            else rh.gs(R.string.ypsopump_profile_read_incomplete)
            callback.outcome = ConfigurationReadResult(message, configurationReadNeedsWarning())
        }
    }

    @Volatile internal var configurationReadSucceeded = false
        private set
    internal fun configurationReadNeedsWarning(): Boolean =
        !configurationReadSucceeded ||
            pumpState.profileComparison == YpsoPumpState.ProfileComparison.MISMATCH

    private fun getPumpStatusNow(reason: String) {
        aapsLogger.debug(LTag.PUMP, "getPumpStatus: $reason")
        val configurationRead = reason in setOf(PROFILE_READ_REASON, ACTIVE_PROGRAM_REASON)
        if (configurationRead) {
            configurationReadSucceeded = false
            pumpState.profileReadMessage = rh.gs(R.string.ypsopump_profile_read_incomplete)
        }
        // CommandReadStatus infers success from lastDataTime. Clear the prior sample even when this
        // invocation only starts a connection, so it cannot report a recent older read as current.
        pumpState.invalidateStatus(preserveDisplay = true)
        if (!configured()) {
            pumpState.invalidateStatus()
            aapsLogger.info(LTag.PUMP, "YpsoPump: session key and/or pump MAC not set — skipping read")
            return
        }
        if (!bleManager.isConnected) { seedAndConnect(); return }
        // History must release the BLE lane at a selector-safe boundary before configuration I/O.
        if (configurationRead && !yieldHistoryRecovery()) {
            aapsLogger.warn(LTag.PUMP, "YpsoPump configuration read skipped: history still owns the link")
            return
        }
        val statusRead = readStatusBlocking()
        if (statusRead) reconcileTbrWithStatus()
        if (configurationRead) {
            val success = statusRead && bleManager.canReadProfile && readProfileBlocking(activeOnly = reason == ACTIVE_PROGRAM_REASON)
            configurationReadSucceeded = success
            // Compare before publishing the message, so the result names the consequence of the read
            // rather than only that the transfer finished.
            if (!success || !acceptPendingProfileSwitch()) reconcileProfileWithLoop()
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
    // see the single canonical mapping at [YpsoPumpState.StatusSnapshot.mappedBatteryPercent].
    override val batteryLevel: Int? get() = pumpState.mappedBatteryPercent
    // Display only: automation, alerts and uploads keep reading the fresh-gated levels above.
    override val lastKnownLevels: Pump.LastKnownLevels
        get() = pumpState.displayStatus().snapshot.let {
            Pump.LastKnownLevels(it?.reservoirUnits ?: Double.NaN, it?.mappedBatteryPercent)
        }

    /** Presentation boundary: driver situations become translated operator text only here. */
    private fun message(reason: YpsoBolusMessage): String = rh.gs(
        when (reason) {
            YpsoBolusMessage.ANOTHER_BOLUS_IN_PROGRESS        -> R.string.ypsopump_bolus_another_in_progress
            YpsoBolusMessage.BOLUS_CANCELLED_BEFORE_START     -> R.string.ypsopump_bolus_cancelled_before_start
            YpsoBolusMessage.PUMP_BUSY                        -> R.string.ypsopump_bolus_pump_busy
            YpsoBolusMessage.PUMP_UNREADABLE                  -> R.string.ypsopump_bolus_pump_unreadable
            YpsoBolusMessage.PUMP_STOPPED_OR_EMPTY            -> R.string.ypsopump_bolus_pump_stopped_or_empty
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
            YpsoBolusMessage.AWAITING_HISTORY_AMOUNT          -> R.string.ypsopump_bolus_awaiting_history
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

    /** Called only after a coherent read; the normal queued profile check still confirms acceptance. */
    private fun acceptPendingProfileSwitch(): Boolean {
        val requested = profileFunction.getRequestedProfile()?.takeIf { profileFunction.isProfileChangePending() }
            ?: return false
        val profile = ProfileSealed.PS(requested, null)
        val effective = profile.getBasalValues().map { YpsoBasalSchedule.EffectiveSegment(it.timeAsSeconds, it.value) }
        // A rejected pending switch must not transiently change the running-profile notification.
        if (!pumpState.activeScheduleMatches(effective)) return false
        if (!isThisProfileSet(profile)) return false
        rxBus.send(EventProfileSwitchChanged())
        return true
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
        bolus.beginRequest()
        val order = YpsoBolusDelivery.Order(detailedBolusInfo.insulin, detailedBolusInfo.carbs, detailedBolusInfo.bolusType)
        return onLink {
            bolusResult(bolus.deliver(order, { constraintsChecker.getMaxBolusAllowed().value() }, bolusProgress(detailedBolusInfo.id)))
        }
    }

    override fun stopBolusDelivering() {
        bolus.stop(handOverLink = ::cancelHistoryRecovery)
    }

    /** Publishes same-command delivery progress, never backwards and never beyond the requested dose. */
    private fun bolusProgress(progressId: Long): (Double) -> Unit {
        var reportedDelivered = -1.0
        return { delivered ->
            if (delivered > reportedDelivered && delivered <= BolusProgressData.insulin) {
                reportedDelivered = delivered
                BolusProgressData.delivered = delivered
                rxBus.send(EventOverviewBolusProgress(rh, delivered, progressId))
            }
        }
    }

    private fun bolusResult(result: YpsoBolusDelivery.Result): PumpEnactResult = when (result) {
        is YpsoBolusDelivery.Result.Failed ->
            fail(if (result.invalidRequest) R.string.ypsopump_bolus_invalid else R.string.ypsopump_bolus_failed, message(result.reason))
        is YpsoBolusDelivery.Result.Invalid -> fail(R.string.ypsopump_bolus_invalid, result.detail)
        is YpsoBolusDelivery.Result.Error ->
            fail(R.string.ypsopump_bolus_failed, result.detail ?: message(YpsoBolusMessage.COULD_NOT_COMPLETE))
        is YpsoBolusDelivery.Result.Uncertain -> pumpEnactResultProvider.get().success(false).enacted(true).bolusDelivered(0.0)
            .isTempCancel(result.cancel).comment(rh.gs(R.string.ypsopump_bolus_uncertain, message(result.reason)))
        YpsoBolusDelivery.Result.AwaitingHistory -> pumpEnactResultProvider.get().success(false).enacted(true).bolusDelivered(0.0)
            .comment(message(YpsoBolusMessage.AWAITING_HISTORY_AMOUNT))
        is YpsoBolusDelivery.Result.Delivered -> pumpEnactResultProvider.get().success(true).enacted(true).bolusDelivered(result.units)
            .comment(rh.gs(if (result.proven) R.string.ypsopump_bolus_completed else R.string.ypsopump_bolus_recorded, result.units))
        is YpsoBolusDelivery.Result.ExtendedStarted -> pumpEnactResultProvider.get().success(true).enacted(true)
            .duration(result.durationMinutes)
            .absolute(result.units * 60.0 / result.durationMinutes)
            .isPercent(false).isTempCancel(false)
            .comment(rh.gs(R.string.ypsopump_bolus_started))
        is YpsoBolusDelivery.Result.Cancelled -> pumpEnactResultProvider.get().success(true).enacted(true).isTempCancel(true)
            .comment(rh.gs(R.string.ypsopump_bolus_completed, result.units))
        YpsoBolusDelivery.Result.NothingToCancel -> pumpEnactResultProvider.get().success(true).enacted(false).isTempCancel(true)
    }

    /** Called only by the bolus module, which serializes it with deciding [show]. */
    private fun showUnresolvedBolusWarning(show: Boolean) {
        if (show == publishedUnresolvedBolusWarning) return
        publishedUnresolvedBolusWarning = show
        rxBus.send(EventDismissNotification(Notification.YPSOPUMP_BOLUS_UNCERTAIN))
        if (show) uiInteraction.addNotification(
            Notification.YPSOPUMP_BOLUS_UNCERTAIN, rh.gs(R.string.ypsopump_bolus_uncertain_notification), Notification.URGENT,
        )
    }

    /** Everything that must happen after a status read lands. Runs on the BLE callback thread. */
    private fun onStatusRead() {
        checkReservoir()
    }

    /**
     * TBR reconciliation after a routine status read. Runs on the reading thread, not the BLE callback
     * thread: it performs database work and must not race a queued TBR command for the controller.
     */
    private fun reconcileTbrWithStatus() {
        bleManager.observedTbr()?.let { observation ->
            runCatching { tbrController.onStatus(observation) }
                .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump TBR resolution failed: ${it.message}") }
        }
        publishTbrWarningIfNeeded()
    }

    /**
     * Reservoir surveillance.
     */
    private fun checkReservoir() {
        val units = pumpState.reservoirUnitsIfFresh() ?: return
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
        val read = YpsoBlockingRead<Boolean>(elapsedRealtime)
        val attempt = bleManager.readStatus {
            read.complete(it)
            if (it) onStatusRead()
        }
        if (read.await(timeoutMs) { expired -> stopWhen() || expired }) return read.value == true
        if (!attempt.cancel()) {
            // The BLE callback won the completion race and is publishing the validated sample now.
            read.awaitCompletion()
            return read.value == true
        }
        aapsLogger.error(LTag.PUMP, "YpsoPump status read timed out after ${timeoutMs}ms")
        pumpState.invalidateStatus(preserveDisplay = true)
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
        if (!yieldHistoryRecovery(historyYieldTimeoutMs)) return TherapyStatusReadiness.HISTORY_BUSY
        if (stopWhen()) return TherapyStatusReadiness.CANCELLED
        if (readStatusBlocking(statusTimeoutMs, stopWhen)) {
            reconcileTbrWithStatus()
            return TherapyStatusReadiness.READY
        }
        return if (stopWhen()) TherapyStatusReadiness.CANCELLED else TherapyStatusReadiness.STATUS_UNAVAILABLE
    }

    /**
     * A configuration read cannot resume: coherence requires an unchanged event count across the
     * acquisition. Yield only to queued boluses so routine TBRs and profile retries cannot repeatedly
     * restart the read. TBR cancellation and zero-TBR requests also wait under this policy.
     */
    private fun readProfileBlocking(timeoutMs: Long = PROFILE_READ_TIMEOUT_MS, activeOnly: Boolean = false): Boolean {
        val read = YpsoBlockingRead<Boolean>(elapsedRealtime)
        val attempt = bleManager.readProfileConfiguration(
            activeOnly,
            { commandQueue.bolusInQueue() || commandQueue.extendedBolusInQueue() },
            read::complete,
        )
        if (read.await(timeoutMs)) return read.value == true
        if (!attempt.cancel()) {
            read.awaitCompletion()
            return read.value == true
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
        onTimeout: () -> Unit = {},
    ): YpsoHistorySnapshot? {
        val read = YpsoBlockingRead<YpsoHistorySnapshot?>(elapsedRealtime)
        val attempt = bleManager.readStableHistory(historyIngestion.currentCursor(), maxRows, read::complete)
        onAttempt(attempt)
        var yielded = false
        val completed = read.await(timeoutMs) { expired ->
            if (stopWhen() && !yielded) {
                yielded = true
                attempt.requestYield()
            }
            if (expired) {
                onTimeout()
                // A deadline is also a cooperative stop: let an in-flight selector finish its
                // read-back instead of stranding a write reservation on an otherwise healthy link.
                attempt.requestYield()
            }
            expired
        }
        if (completed) return read.value
        if (attempt.shouldYield) {
            // A selector write that has left the phone must complete semantic read-back before the
            // therapy command can own the connection. Hard-cancelling here strands its reservation.
            if (read.awaitCompletion(HISTORY_YIELD_GRACE_MS)) return read.value
        }
        if (!attempt.cancel()) {
            // The read already reached a completion path. Wait only briefly for its callback: an
            // unbounded wait here blocks the therapy command forever if that callback never arrives.
            read.awaitCompletion(HISTORY_COMPLETION_GRACE_MS)
            return read.value
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
        if (!beginHistoryRecovery()) {
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
                var timedOut = false
                val snapshot = readHistoryBlocking(
                    timeoutMs = HISTORY_RECOVERY_TIMEOUT_MS,
                    maxRows = maxRows,
                    stopWhen = ::historyRecoveryMustYield,
                    onAttempt = ::publishHistoryRecoveryAttempt,
                    onTimeout = { timedOut = true },
                )
                // Report the outcome: a recovery that reads nothing leaves cancelled doses showing
                // their planned amount, and silence here hides that from the logs entirely.
                when {
                    historyRecoveryMustYield() || (!timedOut && historyRecoveryAttempt.get()?.shouldYield == true) ->
                        aapsLogger.debug(LTag.PUMP, "YpsoPump history recovery after $reason yielded to a pump command")
                    snapshot == null              -> {
                        aapsLogger.warn(LTag.PUMP, "YpsoPump history recovery after $reason read no usable history")
                        reportHistoryRecovery(YpsoHistoryIngestionResult.Blocked("no usable history", retry = YpsoHistoryIngestionResult.Retry.TRANSIENT))
                    }
                    else                          -> {
                        val result = ingestHistory(snapshot)
                        reportHistoryRecovery(result)
                        aapsLogger.debug(LTag.PUMP, "YpsoPump history recovery after $reason ingested: $result")
                        publishTbrWarningIfNeeded()
                    }
                }
            } catch (exception: RuntimeException) {
                aapsLogger.error(LTag.PUMP, "YpsoPump history recovery failed after $reason: ${exception.message}")
                reportHistoryRecovery(YpsoHistoryIngestionResult.Blocked("history recovery exception"))
            } finally {
                finishHistoryRecovery()
                if (idleDisconnectDeferredToHistory.getAndSet(false)) releaseIdleConnection("history recovery")
            }
        }
    }

    /** Only a committed history result clears this warning; status reads and intentional yields do not. */
    @Synchronized
    internal fun reportHistoryRecovery(result: YpsoHistoryIngestionResult) {
        if (!historyRecoveryEnabled) return
        when (result) {
            is YpsoHistoryIngestionResult.Applied -> {
                transientHistoryFailures = 0
                uiInteraction.dismissNotification(Notification.YPSOPUMP_HISTORY_INCOMPLETE)
            }
            is YpsoHistoryIngestionResult.Blocked -> {
                // The ingestion layer itself bounds clock retries; the next pass accounts or reports
                // a real failure. Do not warn during that grace period or clear an existing warning.
                if (result.retry == YpsoHistoryIngestionResult.Retry.BOUNDED) return
                transientHistoryFailures = (transientHistoryFailures + 1).coerceAtMost(HISTORY_WARNING_RETRIES)
                if (result.retry == YpsoHistoryIngestionResult.Retry.NONE || transientHistoryFailures >= HISTORY_WARNING_RETRIES) {
                    // NotificationStore updates the same ID in place. No looping alarm, expiry or dismiss/add.
                    uiInteraction.addNotification(
                        Notification.YPSOPUMP_HISTORY_INCOMPLETE,
                        rh.gs(R.string.ypsopump_history_incomplete_notification),
                        Notification.NORMAL,
                    )
                }
            }
        }
    }

    private fun scheduleLowerBoundHistoryRecovery(reason: String) {
        if (!bleManager.isConnected) return
        if (!beginHistoryRecovery()) {
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
                finishHistoryRecovery()
                if (idleDisconnectDeferredToHistory.getAndSet(false)) releaseIdleConnection("lower-bound recovery")
            }
        }
    }

    private fun historyRecoveryMustYield(): Boolean =
        historyRecoveryYieldRequested || !historyRecoveryEnabled || bolus.isBusy || tbrController.isBusy ||
            commandQueue.size() > 0 || commandQueue.bolusInQueue() || commandQueue.extendedBolusInQueue()

    private fun beginHistoryRecovery(): Boolean = synchronized(historyRecoveryLock) {
        if (!historyRecoveryActive.compareAndSet(false, true)) return@synchronized false
        historyRecoveryYieldRequested = false
        true
    }

    private fun publishHistoryRecoveryAttempt(attempt: YpsoBleManager.HistoryReadAttempt) = synchronized(historyRecoveryLock) {
        historyRecoveryAttempt.set(attempt)
        if (historyRecoveryYieldRequested) attempt.requestYield()
    }

    private fun finishHistoryRecovery() = synchronized(historyRecoveryLock) {
        historyRecoveryAttempt.set(null)
        historyRecoveryYieldRequested = false
        historyRecoveryActive.set(false)
    }

    private fun cancelHistoryRecovery() = synchronized(historyRecoveryLock) {
        if (historyRecoveryActive.get()) {
            // The worker can be scheduled before readStableHistory publishes its attempt. Keep the
            // request for this recovery so that publication cannot lose a command's handoff request.
            historyRecoveryYieldRequested = true
            historyRecoveryAttempt.get()?.requestYield()
        }
    }

    /** Give an in-flight selector enough time to reconcile at a safe boundary before therapy or configuration I/O. */
    internal fun yieldHistoryRecovery(timeoutMs: Long = HISTORY_YIELD_GRACE_MS): Boolean {
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
        val epoch = bleManager.epoch()
        val reboot = epoch?.reboot
        if (serial.isBlank() || epoch == null || reboot == null) {
            aapsLogger.warn(LTag.PUMP, "YpsoPump history ingestion blocked: pump identity or session evidence unavailable")
            return YpsoHistoryIngestionResult.Blocked("pump identity or session evidence unavailable")
        }
        val pass = bolus.reconcileHistory(serial, zone, epoch.generation, reboot, snapshot) as? YpsoBolusDelivery.HistoryPass.Proceed
            ?: return YpsoHistoryIngestionResult.Blocked("terminal bolus event is awaiting identity reconciliation", retry = YpsoHistoryIngestionResult.Retry.TRANSIENT)
        val result = historyIngestion.ingest(serial, zone, reboot.toLong(), snapshot) { event -> pass.treatment(event.identity.aapsPumpId) }
        if (result is YpsoHistoryIngestionResult.Blocked) {
            aapsLogger.error(LTag.PUMP, "YpsoPump history ingestion blocked: ${result.reason}")
        }
        return result
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

    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult = onLink { setTempBasalPercentNow(percent, durationInMinutes, profile, enforceNew, tbrType) }

    private fun setTempBasalPercentNow(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        // AAPS schedules 100% as a cancellation; the pump itself treats it as the scheduled rate.
        if (percent == YpsoTbrRequest.STOP_PERCENT) return cancelTempBasalNow(enforceNew)
        val request = runCatching { YpsoTbrRequest(percent.coerceAtMost(YpsoTbrRequest.MAX_PERCENT), durationInMinutes) }
            .getOrElse { return fail(R.string.ypsopump_tbr_invalid, it.message ?: "invalid request") }
        return enactTbr(request, tbrType)
    }

    override fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        // The pump only runs percent TBRs. AAPS records percent TBRs against the profile rate at each
        // instant, and setNewBasalProfile only succeeds when the pump schedule matches that profile.
        val percent = runCatching { YpsoTbrRequest.percentFor(absoluteRate, profile.getBasal()) }
            .getOrElse { return fail(R.string.ypsopump_tbr_invalid, it.message ?: "invalid request") }
        return setTempBasalPercent(percent, durationInMinutes, profile, enforceNew, tbrType)
    }

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult = onLink { cancelTempBasalNow(enforceNew) }

    private fun cancelTempBasalNow(enforceNew: Boolean): PumpEnactResult {
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
        val unmatched = runCatching { tbrController.unmatchedStarts() }.getOrDefault(0)
        if (unmatched != publishedUnmatchedTbrs) {
            publishedUnmatchedTbrs = unmatched
            rxBus.send(EventDismissNotification(Notification.YPSOPUMP_TBR_UNMATCHED))
            if (unmatched > 0) uiInteraction.addNotification(
                Notification.YPSOPUMP_TBR_UNMATCHED, rh.gs(R.string.ypsopump_tbr_unmatched_notification, unmatched), Notification.NORMAL,
            )
        }
        val unresolved = runCatching { tbrController.hasUnresolved() }.getOrDefault(true)
        if (unresolved == publishedUncertainTbr) return
        publishedUncertainTbr = unresolved
        rxBus.send(EventDismissNotification(Notification.YPSOPUMP_TBR_UNCERTAIN))
        if (unresolved) uiInteraction.addNotification(
            Notification.YPSOPUMP_TBR_UNCERTAIN, rh.gs(R.string.ypsopump_tbr_uncertain_notification), Notification.URGENT,
        )
    }

    @Volatile private var publishedUncertainTbr = false
    @Volatile private var publishedUnmatchedTbrs = 0

    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult = onLink { setExtendedBolusNow(insulin, durationInMinutes) }

    private fun setExtendedBolusNow(insulin: Double, durationInMinutes: Int): PumpEnactResult =
        bolusResult(bolus.startExtended(insulin, durationInMinutes, constraintsChecker.getMaxExtendedBolusAllowed().value()))

    override fun cancelExtendedBolus(): PumpEnactResult = onLink { bolusResult(bolus.cancelExtended()) }

    override fun loadTDDs(): PumpEnactResult = notImplemented()

    // ---- identity ----
    override fun manufacturer(): ManufacturerType = ManufacturerType.Ypsomed
    override fun model(): PumpType = PumpType.YPSOPUMP
    override fun serialNumber(): String = pumpState.serialNumber

    override fun onStart() {
        super.onStart()
        bleManager.onBolusNotification = { notification, connection, receivedAt ->
            bolus.onNotification(notification, connection, receivedAt)
        }
        historyRecoveryEnabled = true
        publishedUnresolvedBolusWarning = null
        rxBus.send(EventDismissNotification(Notification.YPSOPUMP_BOLUS_UNCERTAIN))
        bolus.expireStale()
        provisioning.refreshState()
        publishAvailabilityNotification()
        bolus.refreshWarning()
        setKeyReminderEnabled(true)
        keyExpiryTask?.cancel(false)
        keyExpiryTask = keyExpiryExecutor.scheduleWithFixedDelay({
            runCatching { publishKeyExpiryNotification() }.onFailure {
                aapsLogger.error(LTag.PUMP, "YpsoPump key reminder failed: ${it.javaClass.simpleName}")
            }
        }, 0, 30, TimeUnit.SECONDS)
        publishedUncertainTbr = false
        publishedUnmatchedTbrs = 0
        rxBus.send(EventDismissNotification(Notification.YPSOPUMP_TBR_UNCERTAIN))
        rxBus.send(EventDismissNotification(Notification.YPSOPUMP_TBR_UNMATCHED))
        publishTbrWarningIfNeeded()
        appLifecycle.addVisibilityListener(visibilityListener)
        onAppVisibilityChanged(appLifecycle.uiVisible)
    }

    override fun onStop() {
        setKeyReminderEnabled(false)
        keyExpiryTask?.cancel(false)
        keyExpiryTask = null
        appLifecycle.removeVisibilityListener(visibilityListener)
        foregroundConnectionLease.set(false)
        stopHistoryNotifications()
        cancelHistoryRecovery()
        super.onStop()
        dismissAvailabilityNotification()
    }

    @Synchronized
    private fun stopHistoryNotifications() {
        historyRecoveryEnabled = false
        transientHistoryFailures = 0
        uiInteraction.dismissNotification(Notification.YPSOPUMP_HISTORY_INCOMPLETE)
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
            releaseIdleConnection("app left the foreground")
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
            order = 0
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
        val keySettings = KeyReminderPreferences(context, provisioning, ::publishKeyExpiryNotification) {
            aapsLogger.warn(LTag.PUMP, "YpsoPump reminder settings failed", it)
        }
        keySettings.addTo(parent)
        keySettings.addPreference(
            app.aaps.core.validators.preferences.AdaptiveSwitchPreference(
                ctx = context, booleanKey = app.aaps.core.keys.BooleanKey.YpsoShowKeyExpiry,
                title = R.string.ypsopump_key_show_overview
            ).apply { order = 2 }
        )
        val profiles = PreferenceCategory(context).apply {
            key = "ypsopump_basal_configuration"
            order = 2
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
        // The read can outlive its settings screen. Use the application context so reporting its
        // result neither retains nor accesses a closed screen.
        val appContext = context.applicationContext
        val accepted = queueConfigurationRead(action.reason) { outcome ->
            if (outcome.warning) ToastUtils.warnToast(appContext, outcome.message)
            else ToastUtils.okToast(appContext, outcome.message)
        }
        if (accepted) ToastUtils.infoToast(appContext, rh.gs(action.started))
        else ToastUtils.warnToast(appContext, rh.gs(R.string.ypsopump_profile_read_not_queued))
        return true
    }

    internal data class ConfigurationReadResult(val message: String, val warning: Boolean)

    internal inner class ConfigurationReadCallback(
        val reason: String,
        private val onResult: (ConfigurationReadResult) -> Unit,
    ) : app.aaps.core.interfaces.queue.Callback() {
        @Volatile var outcome: ConfigurationReadResult? = null

        override fun run() {
            // Completion belongs to this command, not the last configuration read. A rejected or
            // cancelled queued command never executes and therefore has no successful outcome.
            // Queue status freshness is also not transfer completion: a full read can outlive it.
            onResult(outcome ?: ConfigurationReadResult(rh.gs(R.string.ypsopump_profile_read_incomplete), true))
        }
    }

    internal fun queueConfigurationRead(reason: String, onResult: (ConfigurationReadResult) -> Unit): Boolean =
        commandQueue.readStatus(reason, ConfigurationReadCallback(reason, onResult))

    @Synchronized
    internal fun publishAvailabilityNotification() {
        val availability = provisioning.availability()
        val coreAlarmCoversConnection = config.APS && preferences.get(BooleanKey.AlertPumpUnreachable)
        if (!provisioning.notificationRequired(availability, suppressConnectionCauses = coreAlarmCoversConnection)) {
            dismissAvailabilityNotification()
            return
        }
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

    internal fun setKeyReminderEnabled(enabled: Boolean) = synchronized(keyReminderLock) {
        keyReminderEnabled = enabled
        keyReminderEpoch++
        if (!enabled) uiInteraction.setNotification(Notification.YPSOPUMP_KEY_EXPIRY, null, Notification.NORMAL)
    }

    override val keyExpiryIndicator: Pump.KeyExpiryIndicator?
        get() {
            if (!preferences.get(app.aaps.core.keys.BooleanKey.YpsoShowKeyExpiry)) return null
            val record = provisioning.installed()?.takeIf { it.keyDatesLoaded } ?: return null
            val status = record.keyTiming.status(record.createdAt?.toEpochMilli(), record.importedAt?.toEpochMilli(), System.currentTimeMillis())
            return Pump.KeyExpiryIndicator(status.remainingMs, status.expiryDue, status.reminderDue,
                status.origin == app.aaps.pump.ypsopump.crypto.KeyTiming.Origin.IMPORT_ESTIMATE)
        }

    internal fun publishKeyExpiryNotification() = synchronized(keyObservationLock) observation@ {
        val epoch = synchronized(keyReminderLock) { if (!keyReminderEnabled) return@observation; keyReminderEpoch }
        // No lifecycle, plugin or therapy lock during potentially slow advisory persistence.
        val status = provisioning.observeKeyTiming()
        synchronized(keyReminderLock) {
            if (!keyReminderEnabled || epoch != keyReminderEpoch) return@observation
            keyExpiryNotification.publish(status) { value ->
                if (value == null) uiInteraction.setNotification(Notification.YPSOPUMP_KEY_EXPIRY, null, Notification.NORMAL)
                else {
                    val (deadline, due) = value
                    uiInteraction.setNotification(Notification.YPSOPUMP_KEY_EXPIRY,
                        rh.gs(if (due) R.string.ypsopump_key_due_notification else R.string.ypsopump_key_reminder_notification,
                            deadline?.let(::keyDateLabel) ?: rh.gs(R.string.ypsopump_key_unknown)),
                        if (due) Notification.URGENT else Notification.NORMAL)
                }
            }
        }
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
    override fun pumpSpecificShortStatus(veryShort: Boolean): String =
        pumpStatusPresentation(pumpState, rh).shortStatus

    companion object {
        internal const val FOREGROUND_CONNECTION_REASON = "Ypso foreground connection"
        internal const val LOWER_BOUND_RECOVERY_REASON = "Ypso lower-bound history recovery"
        internal const val PENDING_KEY_CHECK_REASON = "YpsoPump pending key check"
        private const val HISTORY_WARNING_RETRIES = 3
        private const val HISTORY_COMPLETION_GRACE_MS = 2_000L
        private const val HISTORY_RECOVERY_MAX_ROWS = 3000
        private const val HISTORY_RECOVERY_TIMEOUT_MS = 10 * 60 * 1000L
        /**
         * One history selector step, which a yield waits for, costs several session-journal commits.
         * Each commit rotates a Keystore key (up to ~1 s on the bench phone), so a shorter grace made
         * therapy commands fail while history was still stepping aside.
         */
        private const val HISTORY_YIELD_GRACE_MS = 30_000L
        /** A command whose link an idle disconnect closed a moment earlier waits this long to reopen it. */
        private const val RECONNECT_FOR_COMMAND_MS = 20_000L
        /** A stop seen by status is open until status, or the pump's Resume row, ends it. */
        private const val STATUS_STOP_WINDOW_MS = 24 * 60 * 60_000L
        internal const val PROFILE_READ_REASON = "YpsoPump explicit profile read"
        // Leave room for durable selector/read-floor commits on slower journals. Routine TBRs wait;
        // boluses still preempt at selector-safe boundaries rather than waiting for this deadline.
        private const val PROFILE_READ_TIMEOUT_MS = 180_000L
        internal const val ACTIVE_PROGRAM_REASON = "YpsoPump explicit active program check"

        /** The pump reports remaining insulin in centi-units, so a true empty reads as exactly 0. */
        private const val RESERVOIR_EMPTY_UNITS = 0.0

    }
}
