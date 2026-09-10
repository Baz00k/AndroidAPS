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
import app.aaps.core.interfaces.profile.ProfileFunction
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
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.ble.YpsoHistoryEntry
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
import kotlin.math.max
import kotlin.math.min

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
    private val dateUtil: DateUtil,
    private val rxBus: RxBus,
    private val profileFunction: ProfileFunction,
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
    // mode byte. Write-enabled builds treat an empty cartridge as suspended to stop further dose requests.
    override fun isSuspended(): Boolean = !YpsoPumpConst.READ_ONLY_MODE && (pumpState.isSuspended || reservoirEmpty())
    override fun isBusy(): Boolean = false
    override fun isConnected(): Boolean = pumpState.isConnected
    override fun isConnecting(): Boolean = pumpState.connectionState == ConnectionState.CONNECTING
    override fun isHandshakeInProgress(): Boolean =
        pumpState.connectionState == ConnectionState.DISCOVERING || pumpState.connectionState == ConnectionState.READY

    private var writeValidationDone = false
    private var testBolusDone = false
    private var testTbrDone = false
    private var bolusStatusReadDone = false

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
        // These test ops BLOCK the queue-worker thread until done — otherwise AAPS sees the command as
        // finished and disconnects (5s idle) mid-write. The GATT callbacks run on the BLE binder
        // thread, so blocking here is safe. Real dosing (deliverTreatment) must block the same way.
        when {
            YpsoPumpConst.READ_ONLY_MODE                    -> readStatusBlocking()
            // SAFETY-CRITICAL: deliver one real bolus via the canary-gated safe path (no scan, no
            // auto-sync; aborts before the bolus char if the seeded write counter is wrong).
            YpsoPumpConst.RUN_TEST_BOLUS && !testBolusDone -> {
                testBolusDone = true
                val latch = java.util.concurrent.CountDownLatch(1)
                bleManager.testBolusCanary(YpsoPumpConst.TEST_BOLUS_UNITS, YpsoPumpConst.CAPTURED_WRITE_COUNTER) { _, r ->
                    aapsLogger.info(LTag.PUMP, "YpsoPump TEST-BOLUS: $r"); latch.countDown()
                }
                latch.await(5, java.util.concurrent.TimeUnit.MINUTES)
            }
            // Set ONE TBR via the canary-gated safe path (0% = suspend basal, reduces insulin).
            YpsoPumpConst.RUN_TEST_TBR && !testTbrDone -> {
                testTbrDone = true
                val latch = java.util.concurrent.CountDownLatch(1)
                bleManager.testTbrCanary(YpsoPumpConst.TEST_TBR_PERCENT, YpsoPumpConst.TEST_TBR_DURATION_MIN, YpsoPumpConst.CAPTURED_WRITE_COUNTER) { _, r ->
                    aapsLogger.info(LTag.PUMP, "YpsoPump TEST-TBR: $r"); latch.countDown()
                }
                latch.await(5, java.util.concurrent.TimeUnit.MINUTES)
            }
            // READ-ONLY diagnostic: event-count (single-frame key check) -> system status -> bolus
            // status. No writes — safe mid-bolus. Per-frame logging shows exactly what the pump returns.
            YpsoPumpConst.RUN_READ_BOLUS_STATUS && !bolusStatusReadDone -> {
                bolusStatusReadDone = true
                val latch = java.util.concurrent.CountDownLatch(1)
                // STRICTLY chained — the pump's EXTREAD cursor is shared, so multi-frame reads must
                // never overlap (concurrent reads interleave EXTREAD frames and corrupt both).
                bleManager.readEventCount {
                    bleManager.readStatus { success ->
                        if (!success) {
                            latch.countDown()
                            return@readStatus
                        }
                        bleManager.readBolusStatus { st ->
                            aapsLogger.info(
                                LTag.PUMP,
                                "YpsoPump BOLUS-STATUS: state=${st?.bolusStatusCode} injected=${st?.deliveredUnits}U total=${st?.totalProgrammedUnits}U"
                            )
                            latch.countDown()
                        }
                    }
                }
                latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            }
            // ZERO-THERAPY write-transport validation (history index write + entry read).
            YpsoPumpConst.RUN_WRITE_VALIDATION && YpsoPumpConst.CAPTURED_WRITE_COUNTER >= 0 && !writeValidationDone -> {
                writeValidationDone = true
                val latch = java.util.concurrent.CountDownLatch(1)
                bleManager.validateWriteTransport { r ->
                    aapsLogger.info(LTag.PUMP, "YpsoPump WRITE-VALIDATION: $r"); latch.countDown()
                }
                latch.await(20, java.util.concurrent.TimeUnit.MINUTES)   // counter discovery can take minutes
            }

            else                                            -> bleManager.readStatus { if (it) onStatusRead() }
        }
    }

    override val lastDataTime: Long get() = pumpState.lastStatusTime
    override val lastBolusTime: Long? get() = pumpSync.expectedPumpState().bolus?.timestamp
    override val lastBolusAmount: Double? get() = pumpSync.expectedPumpState().bolus?.amount
    // Keep the status viewer's basal at zero so LoopPlugin cannot run against a non-dosing pump. A
    // write-enabled build derives basal from the AAPS profile because the status payload does not expose it.
    override val baseBasalRate: Double get() =
        if (YpsoPumpConst.READ_ONLY_MODE) 0.0 else profileFunction.getProfile()?.getBasal() ?: 0.0
    override val reservoirLevel: Double get() = pumpState.statusSnapshot?.reservoirUnits ?: Double.NaN
    // The pump reports battery as 0–5 bars, not a percentage. AAPS consumers expect percent;
    // see the single canonical mapping at [YpsoPumpState.mappedBatteryPercent].
    override val batteryLevel: Int? get() = pumpState.mappedBatteryPercent

    // ---- dosing (wired to the proven canary-gated BLE writes; AAPS owns the write counter) ----

    /** Block the queue-worker thread until the pump is connected, connecting if needed. */
    private fun ensureConnected(timeoutMs: Long = 40_000): Boolean {
        if (bleManager.isConnected) return true
        if (!configured()) return false
        seedAndConnect()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!bleManager.isConnected && System.currentTimeMillis() < deadline) Thread.sleep(250)
        return bleManager.isConnected
    }

    /** YpsoPump TBR duration must be a 15-minute step (confirmed on-pump: 3-min was rejected 0x82). */
    private fun round15(minutes: Int): Int = (Math.round(minutes / 15.0).toInt() * 15).coerceAtLeast(15)

    private fun fail(stringRes: Int, vararg args: Any): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false).comment(rh.gs(stringRes, *args))

    override fun setNewBasalProfile(profile: Profile): PumpEnactResult =
        if (YpsoPumpConst.READ_ONLY_MODE) {
            fail(R.string.ypsopump_read_only_profile_blocked)
        } else {
        // The YpsoPump's basal profile is programmed ON THE PUMP (mylife / pump UI); this driver does not
        // write it. The loop steers with percent TBRs relative to the pump's basal, so the pump's programmed
        // basal MUST match this AAPS profile — the user keeps them in sync. Report success accordingly.
            pumpEnactResultProvider.get().success(true).enacted(true).comment(rh.gs(R.string.ypsopump_profile_programmed_on_pump))
        }

    // This driver does not verify the profile programmed directly on the pump. Treat it as satisfied so
    // KeepAlive does not repeatedly queue a no-op or, in status-only mode, a blocked profile write.
    override fun isThisProfileSet(profile: Profile): Boolean = true

    // Bolus delivery is CONFIRM-BY-READ: we never trust the write-accept callback alone (a dropped BLE ack
    // can mean the pump ALREADY delivered — the 2026-07-05 IOB-desync incident). After the START write we
    // poll the pump's own bolus status to the true delivered amount, drive the progress dialog from it, and
    // record THAT. See report + [[ypsopump-bolus-hang]].
    private val bolusConfirmTimeoutMs = 5 * 60 * 1000L
    private val bolusPollFastMs = 500L
    private val bolusPollSlowMs = 2000L
    private val bolusStepU = 0.05
    @Volatile private var bolusCancelRequested = false

    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        if (YpsoPumpConst.READ_ONLY_MODE) return fail(R.string.ypsopump_read_only_bolus_blocked)
        val requested = detailedBolusInfo.insulin
        if (requested <= 0.0) return fail(R.string.ypsopump_bolus_non_positive)
        if (!ensureConnected()) return fail(R.string.ypsopump_not_connected)
        bolusCancelRequested = false

        // PRE-FLIGHT on a FRESH read, not on whatever the last status happened to say. A pump that is
        // stopped (user Stop, occlusion, or an empty-reservoir auto-stop) accepts nothing, so without
        // this the bolus used to sit at 0% for the full five-minute confirm window before failing.
        // Refusing here costs one status read and turns that into an immediate, explainable failure.
        if (!readStatusBlocking()) return fail(R.string.ypsopump_fresh_status_unavailable)
        if (pumpState.isSuspended) return fail(R.string.ypsopump_stopped)
        if (reservoirEmpty()) return fail(R.string.ypsopump_reservoir_empty)

        // Baseline (prior-bolus 'injected' the pump still reports) — logged so validation can confirm whether
        // the pump RESETS deliveredUnits per bolus. Attribution only trusts values AFTER status goes
        // 'delivering', so a lingering prior reading alone can never be mistaken for this dose.
        val baseInjected = readBolusStatusBlocking()?.deliveredUnits ?: 0.0

        // 1) START via the canary-gated write. The ACK is droppable while the pump still delivers, so it is
        //    NEVER used to decide what to record — the pump status is truth. What the outcome DOES decide is
        //    how hard to look: only [BolusStart.NOT_SENT] proves the bolus characteristic was never written.
        var startOutcome = YpsoBleManager.BolusStart.NOT_SENT; var startMsg = "no response from pump"
        val startLatch = java.util.concurrent.CountDownLatch(1)
        bleManager.startBolus(requested, bleManager.writeCounter) { o, m -> startOutcome = o; startMsg = m; startLatch.countDown() }
        if (!startLatch.await(2, java.util.concurrent.TimeUnit.MINUTES)) {
            // No callback at all — the write may still have landed. Treat as uncertain, never as "no bolus".
            startOutcome = YpsoBleManager.BolusStart.UNCERTAIN
            startMsg = "no start response within 2 min — confirming against the pump"
        }
        val started = startOutcome == YpsoBleManager.BolusStart.SENT
        if (startOutcome == YpsoBleManager.BolusStart.NOT_SENT) {
            // Certain no-op: the bolus char was never written, so there is nothing to confirm and nothing
            // to record. Return NOW rather than polling a pump that isn't delivering.
            rxBus.send(EventOverviewBolusProgress(rh, percent = 100, id = detailedBolusInfo.id))
            aapsLogger.warn(LTag.PUMP, "YpsoPump bolus NOT SENT: $startMsg")
            return if (pumpState.isSuspended) fail(R.string.ypsopump_stopped) else fail(R.string.ypsopump_bolus_not_sent)
        }

        // 2) CONFIRM-BY-READ: poll the pump's status until delivery finishes (or timeout / cancel / disconnect),
        //    tracking the actual delivered units and driving the progress bar (fixes the stuck-at-0%).
        var sawDelivering = false
        var delivered = 0.0
        var total = requested
        var pollMs = bolusPollFastMs
        val deadline = dateUtil.now() + bolusConfirmTimeoutMs
        while (dateUtil.now() < deadline) {
            val st = readBolusStatusBlocking()
            if (st != null) {
                // A 'completed' frame is also proof the pump delivered — capture it so a bolus that races
                // 1→4 between polls still counts as seen (status decode fixed in BolusCommand.isDelivering).
                if (st.isDelivering || st.isCompleted) sawDelivering = true
                if (sawDelivering) delivered = max(delivered, st.deliveredUnits)
                if (st.totalProgrammedUnits > 0.0) total = st.totalProgrammedUnits
                val pct = if (total > 0.0) ((delivered / total) * 100).toInt().coerceIn(0, 99) else 0
                rxBus.send(EventOverviewBolusProgress(rh, percent = pct, id = detailedBolusInfo.id))
                aapsLogger.info(LTag.PUMP, "YpsoPump bolus poll: status=${st.bolusStatusCode} delivering=${st.isDelivering} injected=${st.deliveredUnits} total=${st.totalProgrammedUnits} | base=$baseInjected saw=$sawDelivering tracked=$delivered req=$requested")
                if (sawDelivering && !st.isDelivering) break                 // our bolus completed
            } else if (sawDelivering && !bleManager.isConnected) {
                aapsLogger.warn(LTag.PUMP, "YpsoPump bolus: lost connection mid-delivery; recording confirmed $delivered U"); break
            }
            if (bolusCancelRequested) {
                val cl = java.util.concurrent.CountDownLatch(1)
                bleManager.cancelBolus(bleManager.writeCounter, extended = false) { _, cm -> aapsLogger.info(LTag.PUMP, "YpsoPump bolus cancel: $cm"); cl.countDown() }
                cl.await(30, java.util.concurrent.TimeUnit.SECONDS)
                readBolusStatusBlocking()?.let { if (it.isDelivering || sawDelivering) delivered = max(delivered, it.deliveredUnits) }
                aapsLogger.info(LTag.PUMP, "YpsoPump bolus CANCELLED by user; delivered so far=$delivered")
                break
            }
            Thread.sleep(pollMs)
            pollMs = min(pollMs * 3 / 2, bolusPollSlowMs)                    // ramp 0.5s -> 2s
        }
        rxBus.send(EventOverviewBolusProgress(rh, percent = 100, id = detailedBolusInfo.id))

        // 2b) RECONCILE against the pump's OWN bolus history when the live poll did NOT confirm the full
        //     requested dose. A dropped write-ack / BLE blip can leave `delivered` short of (or at zero,
        //     while) what the pump actually pushed — the exact failure that let a 12.4U bolus record as
        //     nothing. The pump logs every fast bolus (completed/cancelled) with the delivered units; that
        //     is ground truth. ADDITIVE ONLY: we adopt the pump's figure solely when it exceeds what we saw
        //     and is plausibly this bolus (<= requested + one step). A failed/implausible read changes
        //     nothing, so this can only rescue an under-count, never invent or reduce a dose.
        if (delivered + bolusStepU < requested) {
            val hist = readLastFastBolusEventBlocking()
            if (hist != null && hist.v1Units > delivered && hist.v1Units <= requested + bolusStepU) {
                aapsLogger.warn(
                    LTag.PUMP,
                    "YpsoPump bolus reconcile: pump history type=${hist.eventType} delivered=${hist.v1Units}U (poll saw $delivered U of $requested U) — recording history; dropped-confirm rescued"
                )
                delivered = hist.v1Units
                sawDelivering = true
            } else {
                aapsLogger.info(LTag.PUMP, "YpsoPump bolus reconcile: no usable history (hist=${hist?.eventType}/${hist?.v1Units}); keeping polled $delivered U")
            }
        }

        // 2c) Nothing confirmed? Then find out WHY before deciding what to record. The pre-flight said the
        //     pump was running with insulin in it, and only a fresh read can tell us whether it stopped or
        //     ran dry during the delivery — which is exactly the case where banking the requested dose
        //     would invent IOB. This also raises the empty-reservoir alarm at the moment it happens.
        if (!(sawDelivering && delivered > 0.0)) readStatusBlocking()

        // 3) RECORD the pump's TRUTH — never gated on the droppable start ack.
        return when {
            sawDelivering && delivered > 0.0 -> {                            // confirmed (possibly partial)
                syncBolus(detailedBolusInfo, delivered)
                val partial = delivered + bolusStepU < requested
                pumpEnactResultProvider.get().success(true).enacted(true).bolusDelivered(delivered)
                    .comment(
                        if (partial) rh.gs(R.string.ypsopump_bolus_partially_delivered, delivered, requested)
                        else rh.gs(R.string.ypsopump_bolus_delivered, delivered)
                    )
            }
            // The pump told us it is not delivering. Recording the request here is what manufactured
            // phantom IOB the day the reservoir ran dry: every "unconfirmed" dose was banked as real
            // while nothing went in, and the loop then under-dosed against an IOB that did not exist.
            // A stopped or empty pump is not an ambiguous read — it is a known no-delivery state.
            pumpState.isSuspended || reservoirEmpty() -> {
                aapsLogger.error(LTag.PUMP, "YpsoPump bolus: pump ${if (pumpState.isSuspended) "stopped" else "reservoir empty"} and nothing confirmed — recording NOTHING (start=$startMsg)")
                notifyNoDelivery()
                fail(if (pumpState.isSuspended) R.string.ypsopump_stopped else R.string.ypsopump_reservoir_empty)
            }
            started                          -> {                            // ack OK but read never confirmed:
                // FAIL SAFE for the loop — record the requested dose so IOB is if anything OVER-stated (loop
                // then UNDER-doses) rather than the dangerous under-count that over-doses. Warn to verify.
                syncBolus(detailedBolusInfo, requested)
                uiInteraction.addNotification(
                    Notification.PUMP_SYNC_ERROR,
                    rh.gs(R.string.ypsopump_bolus_unconfirmed_notification, requested),
                    Notification.URGENT
                )
                pumpEnactResultProvider.get().success(true).enacted(true).bolusDelivered(requested)
                    .comment(rh.gs(R.string.ypsopump_bolus_unconfirmed_result, requested))
            }
            else                             -> fail(R.string.ypsopump_bolus_not_delivered)
        }
    }

    private fun syncBolus(info: DetailedBolusInfo, amount: Double) {
        // Anchor the record to delivery-CONFIRMATION time (now), NOT the bolus start (info.timestamp). A big
        // bolus takes minutes to deliver, so by the time confirm-by-read finishes, info.timestamp can already
        // be older than AAPS's 1-minute freshness gate (see YpsoBleManager.connect serial seed) -> the sync
        // gets silently rejected and the delivered dose is lost. Recording at confirmation time is at most a
        // couple of minutes later than the true start (negligible for IOB) and can NEVER be dropped as stale.
        pumpSync.syncBolusWithPumpId(
            timestamp = dateUtil.now(), amount = amount, type = info.bolusType,
            pumpId = dateUtil.now(), pumpType = PumpType.YPSOPUMP, pumpSerial = serialNumber()
        )
    }

    /** One bolus-status read, blocking the caller (queue-worker) thread until the BLE callback returns. */
    private fun readBolusStatusBlocking(timeoutMs: Long = 8000): app.aaps.pump.ypsopump.comm.commands.BolusCommand? {
        var out: app.aaps.pump.ypsopump.comm.commands.BolusCommand? = null
        val l = java.util.concurrent.CountDownLatch(1)
        bleManager.readBolusStatus { st -> out = st; l.countDown() }
        l.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return out
    }

    /**
     * Blocking reconciliation read of the pump's last fast-bolus history event (multi-step: count read +
     * index write + value read — hence a longer timeout than a single status read). Null on any failure.
     */
    private fun readLastFastBolusEventBlocking(timeoutMs: Long = 15000): YpsoHistoryEntry? {
        var out: YpsoHistoryEntry? = null
        val l = java.util.concurrent.CountDownLatch(1)
        bleManager.readLastFastBolusEvent { e -> out = e; l.countDown() }
        l.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return out
    }

    // Real cancel now that confirm-by-read tracks the partial: the Stop button flags a cancel; the poll loop
    // sends the pump's all-zero START_STOP cancel and records what actually went in.
    override fun stopBolusDelivering() { bolusCancelRequested = true }

    // --- pump-side suspend reflection ---
    // A loop/app suspend already zeroes basal via a recorded 0% TBR. But a suspend initiated ON THE PUMP
    // (the pump's "Stop", or an occlusion/empty auto-suspend) is only learned from a status read: the BLE
    // layer sets pumpState.isSuspended, but nothing tells AAPS the basal stopped — so the graph keeps drawing
    // and IOB keeps ACCRUING the profile basal that isn't being delivered (over-stated IOB). Mirror the pump's
    // real state as a 0-rate PUMP_SUSPEND temp basal (Medtrum/Combo pattern) so the graph + IOB read 0 while
    // stopped. Rolling window refreshed each status read; ended on resume. Called from readStatus's onDone.
    private val suspendTbrWindowMin = 30L
    private var suspendTbrStartMs = 0L

    /** Everything that must happen after a status read lands: mirror a pump-side stop, then check supplies. */
    private fun onStatusRead() {
        if (!YpsoPumpConst.READ_ONLY_MODE) reconcileSuspendTbr()
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
        // Thresholds come from the app's OWN reservoir preferences (Overview → status lights), which
        // already exist, are already translated and are already on a settings screen. They were left
        // unread when the redesign dropped the status-lights row; this puts them back to work rather
        // than inventing a second set of numbers nobody can find.
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

    /** Raise the alarm for a dose the pump could not take, so a refusal is never silent. */
    private fun notifyNoDelivery() {
        if (reservoirEmpty()) checkReservoir()
        else uiInteraction.addNotificationWithSound(
            Notification.PUMP_SUSPENDED, rh.gs(R.string.ypsopump_stopped), Notification.URGENT, app.aaps.core.ui.R.raw.boluserror
        )
    }

    private fun reconcileSuspendTbr() {
        val now = dateUtil.now()
        // An EMPTY reservoir is a not-delivering state exactly like a stop, whether or not the pump has
        // already flagged itself stopped. Without this the basal IOB kept accruing against insulin that
        // was never pushed — half of the IOB error after the cartridge ran dry.
        val notDelivering = pumpState.isSuspended || reservoirEmpty()
        when {
            notDelivering && suspendTbrStartMs == 0L -> {                 // pump just stopped
                suspendTbrStartMs = now
                pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = now, rate = 0.0, duration = T.mins(suspendTbrWindowMin).msecs(),
                    isAbsolute = true, type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
                    pumpId = now, pumpType = PumpType.YPSOPUMP, pumpSerial = serialNumber()
                )
                aapsLogger.info(LTag.PUMP, "YpsoPump: pump suspended -> recorded PUMP_SUSPEND 0-TBR")
            }
            notDelivering && suspendTbrStartMs != 0L -> {                 // still stopped: extend window
                pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = suspendTbrStartMs, rate = 0.0,
                    duration = (now - suspendTbrStartMs) + T.mins(suspendTbrWindowMin).msecs(),
                    isAbsolute = true, type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
                    pumpId = suspendTbrStartMs, pumpType = PumpType.YPSOPUMP, pumpSerial = serialNumber()
                )
            }
            !notDelivering && suspendTbrStartMs != 0L -> {                // resumed: end it
                pumpSync.syncStopTemporaryBasalWithPumpId(now, now, PumpType.YPSOPUMP, serialNumber())
                aapsLogger.info(LTag.PUMP, "YpsoPump: pump resumed -> ended PUMP_SUSPEND 0-TBR")
                suspendTbrStartMs = 0L
            }
        }
    }

    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        if (YpsoPumpConst.READ_ONLY_MODE) return fail(R.string.ypsopump_read_only_tbr_blocked)
        val dur = round15(durationInMinutes)
        if (!ensureConnected()) return fail(R.string.ypsopump_not_connected)
        if (!readStatusBlocking()) return fail(R.string.ypsopump_fresh_status_unavailable)
        // A stopped or empty pump delivers nothing, and recording a TBR against it would overwrite the
        // 0-rate PUMP_SUSPEND window [reconcileSuspendTbr] keeps — re-inflating IOB with insulin that
        // never left the cartridge. Refuse instead; the loop switches to SUSPENDED_BY_PUMP on its own.
        if (pumpState.isSuspended) return fail(R.string.ypsopump_stopped)
        if (reservoirEmpty()) return fail(R.string.ypsopump_reservoir_empty)
        var accepted = false; var msg = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        bleManager.testTbrCanary(percent, dur, bleManager.writeCounter) { ok, m -> accepted = ok; msg = m; latch.countDown() }
        latch.await(3, java.util.concurrent.TimeUnit.MINUTES)
        if (!accepted) {
            aapsLogger.error(LTag.PUMP, "YpsoPump TBR failed: $msg")
            return fail(R.string.ypsopump_tbr_failed)
        }
        pumpSync.syncTemporaryBasalWithPumpId(
            timestamp = dateUtil.now(),
            rate = percent.toDouble(),
            duration = T.mins(dur.toLong()).msecs(),
            isAbsolute = false,
            type = tbrType,
            pumpId = dateUtil.now(),
            pumpType = PumpType.YPSOPUMP,
            pumpSerial = serialNumber()
        )
        val result = pumpEnactResultProvider.get().success(true).enacted(true).comment(rh.gs(R.string.ypsopump_tbr_result, percent, dur))
        result.isPercent = true; result.percent = percent; result.duration = dur
        return result
    }

    override fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        if (YpsoPumpConst.READ_ONLY_MODE) return fail(R.string.ypsopump_read_only_tbr_blocked)
        val base = profile.getBasal()
        val percent = if (base > 0) Math.round(absoluteRate / base * 100.0).toInt() else 100
        return setTempBasalPercent(percent, durationInMinutes, profile, enforceNew, tbrType)
    }

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        if (YpsoPumpConst.READ_ONLY_MODE) {
            return fail(R.string.ypsopump_read_only_tbr_cancel_blocked)
        }
        // No dedicated stop-TBR command RE'd yet; setting 100% for a 15-min step overrides any active
        // override back to the normal (pump-programmed) basal.
        if (!ensureConnected()) return fail(R.string.ypsopump_not_connected)
        var accepted = false; var msg = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        bleManager.testTbrCanary(100, 15, bleManager.writeCounter) { ok, m -> accepted = ok; msg = m; latch.countDown() }
        latch.await(3, java.util.concurrent.TimeUnit.MINUTES)
        if (!accepted) {
            aapsLogger.error(LTag.PUMP, "YpsoPump TBR cancellation failed: $msg")
            return fail(R.string.ypsopump_tbr_cancel_failed)
        }
        pumpSync.syncStopTemporaryBasalWithPumpId(
            timestamp = dateUtil.now(),
            endPumpId = dateUtil.now(),
            pumpType = PumpType.YPSOPUMP,
            pumpSerial = serialNumber()
        )
        val result = pumpEnactResultProvider.get().success(true).enacted(true).comment(rh.gs(R.string.ypsopump_tbr_cancel_result))
        result.isTempCancel = true
        return result
    }

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
                summary = provisioning.installed()?.let { rh.gs(R.string.ypsopump_configured_summary, it.serial, it.mac, it.keyFingerprint) }
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
