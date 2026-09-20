package app.aaps.pump.ypsopump

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
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
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.history.YpsoHistoryStateFileStore
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
    /** Last published mismatch text, or null when no mismatch is currently published. */
    private var publishedProfileMismatch: String? = null
    private var publishedUnresolvedBolusWarning: String? = null
    private val historyIngestion by lazy {
        YpsoHistoryIngestion(
            YpsoHistoryStateFileStore(java.io.File(bleManager.noBackupDirectory(), "ypsopump-history-state.json")),
            pumpSync,
        )
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
    @Volatile private var historyRecoveryEnabled = true

    init {
        provisioning.availabilityChanged = { publishAvailabilityNotification() }
    }

    override val pumpDescription: PumpDescription = PumpDescription().fillFor(PumpType.YPSOPUMP).apply {
        isBolusCapable = !YpsoPumpConst.READ_ONLY_MODE
        // Basal writes and TBR remain unsupported independently of bolus therapy.
        isExtendedBolusCapable = !YpsoPumpConst.READ_ONLY_MODE
        extendedBolusDurationStep = 15.0
        extendedBolusMaxDuration = 12.0 * 60.0
        isTempBasalCapable = false
        isSetBasalProfileCapable = false
        supportsTDDs = false
        needsManualTDDLoad = false
    }

    private fun notImplemented(): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false).comment(rh.gs(R.string.ypsopump_not_implemented))

    // ---- state (read-only) ----
    override fun isInitialized(): Boolean = pumpState.hasVerifiedStatus
    // A status-only build must not drive AAPS running-mode transitions from the still-unverified delivery
    // mode byte. The status-only artifact exposes this state without enabling dose requests.
    override fun isSuspended(): Boolean = !YpsoPumpConst.READ_ONLY_MODE && (pumpState.isSuspended || reservoirEmpty())
    // Background accounting is abandonable and must never hold the serialized therapy queue.
    override fun isBusy(): Boolean = bolusController.isBusy
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
        if (reason == "Connection needed" &&
            (commandQueue.bolusInQueue() || commandQueue.extendedBolusInQueue())
        ) {
            provisioning.requestTherapyConnectionAttempt()
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
        if (statusRead) scheduleHistoryRecovery(reason)
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
     * configuration alone. See issue #14 and the Ypsopump README.
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
        if (!bolusController.beginDelivery()) return fail(R.string.ypsopump_bolus_failed, "another bolus is active")
        try {
        return runCatching {
        if (detailedBolusInfo.carbs != 0.0) return fail(R.string.ypsopump_bolus_invalid, "carbohydrates are not stored on this pump")
        val constrainedMaximum = constraintsChecker.getMaxBolusAllowed().value()
        val request = runCatching {
            YpsoBolusRequestValidator.validate(
                detailedBolusInfo.insulin,
                detailedBolusInfo.bolusType.toYpsoTreatment(),
                constrainedMaximum,
            )
        }.getOrElse { return fail(R.string.ypsopump_bolus_invalid, it.message ?: "invalid request") }
        if (!readStatusBlocking(stopWhen = { bolusController.cancellationRequested })) {
            return fail(R.string.ypsopump_bolus_failed, if (bolusController.cancellationRequested) "bolus cancelled before dispatch" else "fresh pump status is unavailable")
        }
        if (pumpState.isSuspended || reservoirEmpty()) return fail(R.string.ypsopump_bolus_failed, "pump is stopped or reservoir is empty")
        val reboot = bleManager.session?.snapshot()?.reboot
            ?: return fail(R.string.ypsopump_bolus_failed, "pump reboot epoch is unavailable")
        historyIngestion.bolusReadiness(serialNumber(), reboot.toLong())?.let {
            return fail(R.string.ypsopump_bolus_failed, it)
        }
        when (val result = bolusController.deliver(request)) {
            is YpsoImmediateBolusController.DeliveryResult.Started ->
                awaitBolusTerminal(result.attempt.requestId, detailedBolusInfo.id, result.observedDeliveredUnits)
            is YpsoImmediateBolusController.DeliveryResult.NotSent ->
                fail(R.string.ypsopump_bolus_failed, result.detail)
            is YpsoImmediateBolusController.DeliveryResult.Uncertain ->
                pumpEnactResultProvider.get()
                    .success(false)
                    .enacted(true)
                    .bolusDelivered(0.0)
                    .comment(rh.gs(R.string.ypsopump_bolus_uncertain, result.detail))
        }
        }.getOrElse {
            aapsLogger.error(LTag.PUMP, "YpsoPump bolus lifecycle failed: ${it.message}")
            fail(R.string.ypsopump_bolus_failed, it.message ?: "internal bolus failure")
        }
        } finally {
            bolusController.finishDelivery()
        }
    }

    override fun stopBolusDelivering() {
        cancelHistoryRecovery()
        if (!YpsoPumpConst.READ_ONLY_MODE) runCatching { bolusController.requestStop() }
            .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump stop bolus failed: ${it.message}") }
    }

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
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (bolusController.cancellationRequested && !cancellationSignalled) {
                // Stop is a request, not terminal evidence. Signal it promptly once, then continue
                // observing same-command status and authoritative type-2 history for partial delivery.
                cancellationSignalled = bolusController.requestStop() ==
                    YpsoImmediateBolusController.StopResult.DISPATCHED_OR_PENDING
            }
            val attempt = bolusController.currentAttempt()
            if (attempt?.requestId != requestId) {
                bolusController.markUnresolved("durable bolus identity changed after dispatch")
                publishUnresolvedBolusWarningIfNeeded()
                return pumpEnactResultProvider.get()
                    .success(false)
                    .enacted(true)
                    .bolusDelivered(0.0)
                    .comment(rh.gs(R.string.ypsopump_bolus_uncertain, "durable bolus identity changed after dispatch"))
            }
            if (attempt.confirmedCentiUnits != null) {
                val pumpHistoryId = attempt.pumpHistoryId
                if (pumpHistoryId == null || !historyIngestion.isAccounted(pumpHistoryId)) {
                    val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                    if (remaining > 0) readHistoryBlocking(
                        timeoutMs = minOf(20_000L, remaining),
                        stopWhen = bolusController::consumeHistoryYield,
                    )?.let(::ingestHistory)
                    Thread.sleep(250L)
                    continue
                }
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
            }
            if (status?.bolusStatusCode == BolusCommand.STATUS_IDLE) {
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining > 0) readHistoryBlocking(
                    timeoutMs = minOf(20_000L, remaining),
                    stopWhen = bolusController::consumeHistoryYield,
                )?.let(::ingestHistory)
            }
            Thread.sleep(250L)
        }
        bolusController.markUnresolved("terminal bolus status/history was not confirmed before timeout")
        publishUnresolvedBolusWarningIfNeeded()
        return pumpEnactResultProvider.get()
            .success(false)
            .enacted(true)
            .bolusDelivered(0.0)
            .comment(rh.gs(R.string.ypsopump_bolus_uncertain, "terminal delivery was not confirmed"))
    }

    private fun deliverExtended(request: YpsoValidatedBolusRequest): PumpEnactResult {
        if (YpsoPumpConst.READ_ONLY_MODE) return fail(R.string.ypsopump_read_only_bolus_blocked)
        if (!bolusController.beginDelivery()) return fail(R.string.ypsopump_bolus_failed, "another bolus is active")
        try {
            if (!readStatusBlocking()) return fail(R.string.ypsopump_bolus_failed, "fresh pump status is unavailable")
            if (pumpState.isSuspended || reservoirEmpty()) return fail(R.string.ypsopump_bolus_failed, "pump is stopped or reservoir is empty")
            val reboot = bleManager.session?.snapshot()?.reboot
                ?: return fail(R.string.ypsopump_bolus_failed, "pump reboot epoch is unavailable")
            historyIngestion.bolusReadiness(serialNumber(), reboot.toLong())?.let {
                return fail(R.string.ypsopump_bolus_failed, it)
            }
            return when (val result = bolusController.deliver(request)) {
                is YpsoImmediateBolusController.DeliveryResult.Started -> {
                    val attempt = result.attempt
                    val start = requireNotNull(attempt.dispatchedAt)
                    val pumpId = bolusHistoryPumpId(attempt.baseline.historyPumpId, requireNotNull(attempt.pumpSlowSequence))
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
                            .comment(rh.gs(R.string.ypsopump_bolus_uncertain, "extended bolus accounting failed"))
                    }
                    pumpEnactResultProvider.get().success(true).enacted(true)
                        .duration(request.durationMinutes)
                        .absolute(request.units * 60.0 / request.durationMinutes)
                        .isPercent(false).isTempCancel(false)
                        .comment(rh.gs(R.string.ypsopump_bolus_started))
                }
                is YpsoImmediateBolusController.DeliveryResult.NotSent -> fail(R.string.ypsopump_bolus_failed, result.detail)
                is YpsoImmediateBolusController.DeliveryResult.Uncertain ->
                    pumpEnactResultProvider.get().success(false).enacted(true)
                        .comment(rh.gs(R.string.ypsopump_bolus_uncertain, result.detail))
            }
        } finally {
            bolusController.finishDelivery()
        }
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
            if (stopWhen()) { yielded = true; break }
            if (android.os.SystemClock.elapsedRealtime() >= deadline) break
        }
        if (latch.count == 0L) return snapshot
        if (!attempt.cancel()) {
            latch.await()
            return snapshot
        }
        if (yielded) return null
        aapsLogger.error(LTag.PUMP, "YpsoPump history read timed out after ${timeoutMs}ms")
        bleManager.disconnect()
        return null
    }

    /**
     * Runs history/accounting independently of AAPS' serialized command execution. The operation is
     * finite (at most the pump's complete 3000-row ring), and immediately yields to any queued pump
     * command. Empty stores use a one-row anchor and therefore never import pre-install insulin.
     */
    private fun scheduleHistoryRecovery(reason: String) {
        if (!historyRecoveryEnabled || !bleManager.isConnected || !bleManager.canReadHistory) return
        if (!historyRecoveryActive.compareAndSet(false, true)) return
        dispatchHistoryRecovery {
            try {
                val cursor = historyIngestion.currentCursor()
                val maxRows = if (cursor == null) 1 else HISTORY_RECOVERY_MAX_ROWS
                val snapshot = readHistoryBlocking(
                    timeoutMs = HISTORY_RECOVERY_TIMEOUT_MS,
                    maxRows = maxRows,
                    stopWhen = ::historyRecoveryMustYield,
                    onAttempt = historyRecoveryAttempt::set,
                )
                if (snapshot != null && !historyRecoveryMustYield()) ingestHistory(snapshot)
            } catch (exception: RuntimeException) {
                aapsLogger.error(LTag.PUMP, "YpsoPump history recovery failed after $reason: ${exception.message}")
            } finally {
                historyRecoveryAttempt.set(null)
                historyRecoveryActive.set(false)
            }
        }
    }

    private fun historyRecoveryMustYield(): Boolean =
        !historyRecoveryEnabled || bolusController.isBusy ||
            commandQueue.size() > 0 || commandQueue.bolusInQueue()

    private fun cancelHistoryRecovery() {
        historyRecoveryAttempt.get()?.cancel()
    }

    private fun ingestHistory(snapshot: YpsoHistorySnapshot): YpsoHistoryIngestionResult {
        val serial = serialNumber()
        val zone = pumpState.historyZone
        val reboot = bleManager.session?.snapshot()?.reboot
        if (serial.isBlank() || reboot == null || bleManager.session?.activeRecord()?.generation == null) {
            aapsLogger.warn(LTag.PUMP, "YpsoPump history ingestion blocked: pump identity or session evidence unavailable")
            return YpsoHistoryIngestionResult.Blocked("pump identity or session evidence unavailable")
        }
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
        if (attempt?.holdsTerminalRow == true && terminalSequence != null && snapshot.rowsNewestFirst.any {
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
            when (val resolution = YpsoExtendedBolusReconciler.reconcile(attempt, stable.newEventsOldestFirst)) {
                is YpsoExtendedBolusReconciliation.AttemptCompleted -> {
                    val timestamp = (YpsoPumpLocalTime.resolve(resolution.event.entry.factorySeconds, zone) as? YpsoPumpLocalTime.Resolution.Resolved)
                        ?.instant?.toEpochMilli() ?: return
                    val completed = resolution.amountCentiUnits == attempt.requestedCentiUnits && attempt.cancelRequestId == null
                    val start = if (completed) timestamp - attempt.durationMinutes * 60_000L
                    else requireNotNull(attempt.dispatchedAt)
                    val actualDuration = (timestamp - start).coerceAtLeast(1L)
                    val synced = pumpSync.syncExtendedBolusWithPumpId(
                        start,
                        resolution.amountCentiUnits / 100.0,
                        actualDuration,
                        false,
                        resolution.event.identity.aapsPumpId,
                        PumpType.YPSOPUMP,
                        serial,
                    )
                    if (!synced && !extendedAccountingMatches(
                            resolution.event.identity.aapsPumpId,
                            start,
                            resolution.amountCentiUnits / 100.0,
                            actualDuration,
                            serial,
                        )) {
                        bolusController.markUnresolved("terminal extended bolus accounting was rejected")
                        publishUnresolvedBolusWarningIfNeeded()
                        return
                    }
                    bolusController.confirmTerminal(
                        resolution.amountCentiUnits,
                        timestamp,
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
        when (val resolution = YpsoImmediateBolusReconciler.reconcile(attempt, status, stable.newEventsOldestFirst)) {
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
            is YpsoImmediateBolusReconciliation.Unresolved ->
                if (resolution.reason != YpsoImmediateBolusReconciliation.Reason.NO_COMPATIBLE_HISTORY)
                    bolusController.markUnresolved("history reconciliation: ${resolution.reason}")
            is YpsoImmediateBolusReconciliation.ConfirmedInsulin -> Unit
        }
    }

    private fun readBolusStatusBlocking(timeoutMs: Long = 15_000): BolusCommand? {
        var status: BolusCommand? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        bleManager.readBolusStatus { status = it; latch.countDown() }
        return if (latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) status else null
    }

    private fun bolusHistoryPumpId(baselinePumpId: Long, sequence: Long): Long {
        var generation = baselinePumpId ushr 32
        val baselineSequence = baselinePumpId and 0xffffffffL
        if (sequence < baselineSequence) generation++
        return (generation shl 32) or sequence
    }

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
        val pumpId = bolusHistoryPumpId(attempt.baseline.historyPumpId, requireNotNull(attempt.pumpSlowSequence))
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

    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult =
        fail(R.string.ypsopump_read_only_tbr_blocked)

    override fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult =
        fail(R.string.ypsopump_read_only_tbr_blocked)

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult =
        fail(R.string.ypsopump_read_only_tbr_cancel_blocked)

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
        val attempt = bolusController.currentAttempt()
        if (attempt == null || attempt.shape == YpsoBolusShape.IMMEDIATE || !attempt.awaitsReconciliation) {
            if (pumpSync.expectedPumpState().extendedBolus != null) {
                return fail(R.string.ypsopump_bolus_failed, "active extended bolus has no durable cancellation identity; stop it on the pump")
            }
            return pumpEnactResultProvider.get().success(true).enacted(false).isTempCancel(true)
        }
        if (!bolusController.beginDelivery()) return fail(R.string.ypsopump_bolus_failed, "another bolus operation is active")
        try {
            bolusController.requestStop()
            val deadline = android.os.SystemClock.elapsedRealtime() + 90_000L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                bolusController.requestStop()
                val current = bolusController.currentAttempt()
                    ?: return fail(R.string.ypsopump_bolus_failed, "durable extended bolus identity was lost")
                if (current.confirmedCentiUnits != null) {
                    return pumpEnactResultProvider.get().success(true).enacted(true).isTempCancel(true)
                        .comment(rh.gs(R.string.ypsopump_bolus_completed, current.confirmedUnits ?: 0.0))
                }
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining > 0) readHistoryBlocking(minOf(20_000L, remaining))?.let(::ingestHistory)
                Thread.sleep(250L)
            }
            bolusController.markUnresolved("extended bolus cancellation was not confirmed by terminal history")
            publishUnresolvedBolusWarningIfNeeded()
            return pumpEnactResultProvider.get().success(false).enacted(true).isTempCancel(true)
                .comment(rh.gs(R.string.ypsopump_bolus_uncertain, "extended bolus cancellation was not confirmed"))
        } finally {
            bolusController.finishDelivery()
        }
    }
    override fun loadTDDs(): PumpEnactResult = notImplemented()

    // ---- identity ----
    override fun manufacturer(): ManufacturerType = ManufacturerType.Ypsomed
    override fun model(): PumpType = PumpType.YPSOPUMP
    override fun serialNumber(): String = pumpState.serialNumber

    override fun onStart() {
        super.onStart()
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
    }

    override fun onStop() {
        historyRecoveryEnabled = false
        cancelHistoryRecovery()
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
        private const val HISTORY_RECOVERY_MAX_ROWS = 3000
        private const val HISTORY_RECOVERY_TIMEOUT_MS = 10 * 60 * 1000L
        internal const val PROFILE_READ_REASON = "YpsoPump explicit profile read"
        internal const val ACTIVE_PROGRAM_REASON = "YpsoPump explicit active program check"

        /** The pump reports remaining insulin in centi-units, so a true empty reads as exactly 0. */
        private const val RESERVOIR_EMPTY_UNITS = 0.0

    }
}
