package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.lifecycle.AppLifecycle
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.IntKey
import app.aaps.implementation.pump.PumpEnactResultObject
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.data.YpsoBasalSchedule
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.core.interfaces.profile.Profile.ProfileValue
import java.time.ZoneId
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.crypto.PumpSession
import java.time.Instant
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import javax.inject.Provider

class YpsoPumpPluginTest {
    private val rh: ResourceHelper = mock {
        on { gs(any()) } doReturn "localized message"
        on { gs(any(), anyVararg()) } doReturn "localized message"
    }
    private val state = YpsoPumpState()
    private val manager: YpsoBleManager = mock()
    private val sync: PumpSync = mock()
    private val ui: UiInteraction = mock()
    private val rxBus: RxBus = mock()
    private val preferences: Preferences = mock()
    private val provisioning: YpsoProvisioningService = mock()
    private val commandQueue: CommandQueue = mock()
    private val installed = YpsoProvisioningService.InstalledSession(
        "10000001", "12:34:56:78:9A:BC", "fingerprint", null, Instant.EPOCH, emptyMap(), null,
        PumpSession.Availability(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE))
    )
    private val profileFunction: ProfileFunction = mock()
    private val maxBolusConstraint: Constraint<Double> = mock {
        onGeneric { value() } doReturn 30.0
    }
    private val constraintsChecker: ConstraintsChecker = mock {
        on { getMaxBolusAllowed() } doReturn maxBolusConstraint
        on { getMaxExtendedBolusAllowed() } doReturn maxBolusConstraint
    }
    private val appLifecycle: AppLifecycle = mock()
    private val plugin = YpsoPumpPlugin(
        AAPSLoggerTest(), rh, preferences, commandQueue, state, manager, sync, rxBus, ui,
        Provider { PumpEnactResultObject(rh).success(true).enacted(true) }, provisioning, profileFunction, constraintsChecker, appLifecycle
    )

    @Test
    fun `direct Pump requests return non enacted outcomes with a verified status`() {
        state.publishStatus(80.0, 90, false, 100, 4000L)
        // With therapy enabled every request reads fresh status first; an unreadable pump must fail closed.
        whenever(manager.readStatus(any())).thenAnswer {
            it.getArgument<(Boolean) -> Unit>(0)(false)
            YpsoBleManager.StatusReadAttempt()
        }
        val profile: Profile = mock { on { getBasalValues() } doReturn arrayOf(ProfileValue(0, 0.5)) }
        val results = listOf(
            plugin.deliverTreatment(DetailedBolusInfo().apply { insulin = 1.25 }),
            plugin.setNewBasalProfile(profile),
            plugin.setTempBasalAbsolute(1.2, 30, profile, true, PumpSync.TemporaryBasalType.NORMAL),
            plugin.setTempBasalPercent(150, 30, profile, true, PumpSync.TemporaryBasalType.NORMAL),
            plugin.cancelTempBasal(true), plugin.loadTDDs()
        )
        results.forEach { assertFalse(it.success); assertFalse(it.enacted); assertEquals(0.0, it.bolusDelivered) }
        assertEquals(0.0, plugin.baseBasalRate)
        assertEquals(!YpsoPumpConst.READ_ONLY_MODE, plugin.pumpDescription.isTempBasalCapable)
        verifyNoInteractions(sync)
        if (YpsoPumpConst.READ_ONLY_MODE) verifyNoInteractions(manager)
    }

    @Test
    fun `profile coherence fails closed even when status basal matches the requested current rate`() {
        val profile: Profile = mock {
            on { getBasal() } doReturn 0.6
            on { getBasalValues() } doReturn arrayOf(ProfileValue(0, 0.6))
        }
        state.publishStatus(
            reservoirUnits = 80.0,
            batteryPercent = 90,
            isSuspended = false,
            activeTbrPercent = 100,
            timestamp = 4_000L,
            activeBasalRate = 0.6,
        )

        assertFalse(plugin.isThisProfileSet(profile))
    }

    @Test
    fun `profile comparison uses complete retained schedule without timer expiry`() {
        var elapsed = 2_001L
        state.elapsedRealtime = { elapsed }
        state.currentZone = { ZoneId.of("Europe/Warsaw") }
        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        val matching: Profile = mock {
            on { getBasalValues() } doReturn arrayOf(ProfileValue(0, 0.5))
        }
        val mismatch: Profile = mock {
            on { getBasalValues() } doReturn arrayOf(ProfileValue(0, 0.5), ProfileValue(23 * 3600, 0.51))
        }

        assertTrue(plugin.isThisProfileSet(matching))
        assertEquals(0.5, plugin.baseBasalRate)
        assertFalse(plugin.isThisProfileSet(mismatch))
        elapsed = 2_000L + YpsoPumpState.PROFILE_MAX_AGE_MS
        assertTrue(plugin.isThisProfileSet(matching))
        assertEquals(0.5, plugin.baseBasalRate)
        // A verified match confirms the profile, but this driver never writes a schedule.
        assertFalse(plugin.setNewBasalProfile(matching).enacted)
        assertTrue(plugin.setNewBasalProfile(matching).success)
    }

    @Test
    fun `Pump values follow acquisition and monotonic expiry together`() {
        var elapsed = 0L
        state.elapsedRealtime = { elapsed }
        assertTrue(plugin.reservoirLevel.isNaN())
        assertNull(plugin.batteryLevel)
        assertEquals(0L, plugin.lastDataTime)
        state.publishStatus(0.0, 12, false, 100, 2000L)
        assertEquals(0.0, plugin.reservoirLevel)
        assertEquals(12, plugin.batteryLevel)
        assertEquals(2000L, plugin.lastDataTime)
        elapsed = 300_000L
        assertTrue(plugin.reservoirLevel.isNaN())
        assertNull(plugin.batteryLevel)
        assertEquals(0L, plugin.lastDataTime)
        assertFalse(plugin.isInitialized())
    }

    @Test
    fun `completed setup remains initialized while disconnected and status is stale`() {
        whenever(provisioning.installed()).thenReturn(installed.copy(verifiedAt = Instant.EPOCH))

        assertTrue(plugin.isInitialized())
        assertFalse(plugin.isConnected())
        assertFalse(state.hasVerifiedStatus)
    }

    @Test
    fun `polling alarms use verified measurements and failed reads cannot fabricate empty reservoir`() {
        whenever(provisioning.installed()).thenReturn(installed)
        whenever(provisioning.isConfigured()).thenReturn(true)
        whenever(provisioning.retryAllowed(any())).thenReturn(true)
        whenever(manager.installedPumpMac()).thenReturn("12:34:56:78:9A:BC")
        whenever(manager.isConnected).thenReturn(true)
        whenever(preferences.get(IntKey.OverviewResCritical)).thenReturn(10)
        var sample: Double? = 0.0
        whenever(manager.readStatus(any())).thenAnswer {
            val units = sample
            if (units != null) state.publishStatus(units, 70, false, 100, 7000L)
            it.getArgument<(Boolean) -> Unit>(0)(units != null)
            YpsoBleManager.StatusReadAttempt()
        }
        plugin.getPumpStatus("poll")
        verify(ui).addNotificationWithSound(eq(Notification.PUMP_RESERVOIR_EMPTY), any(), eq(Notification.URGENT), any())
        assertEquals(0.0, plugin.reservoirLevel)
        sample = null
        plugin.getPumpStatus("failed poll")
        assertTrue(plugin.reservoirLevel.isNaN())
        assertNull(plugin.batteryLevel)
        assertEquals(0L, plugin.lastDataTime)
        sample = 8.0
        plugin.getPumpStatus("recovered poll")
        verify(ui).addNotification(eq(Notification.PUMP_RESERVOIR_LOW), any(), eq(Notification.URGENT))
        verify(ui, times(1)).addNotificationWithSound(eq(Notification.PUMP_RESERVOIR_EMPTY), any(), eq(Notification.URGENT), any())
        assertEquals(8.0, plugin.reservoirLevel)
        verifyNoInteractions(sync)
    }

    @Test
    fun `status polling never acquires configuration even with durable selectors ready`() {
        whenever(provisioning.installed()).thenReturn(installed)
        whenever(provisioning.isConfigured()).thenReturn(true)
        whenever(provisioning.retryAllowed(any())).thenReturn(true)
        whenever(manager.installedPumpMac()).thenReturn("12:34:56:78:9A:BC")
        whenever(manager.isConnected).thenReturn(true)
        whenever(manager.canReadProfile).thenReturn(true)
        whenever(manager.readStatus(any())).thenAnswer {
            it.getArgument<(Boolean) -> Unit>(0)(true)
            YpsoBleManager.StatusReadAttempt()
        }
        whenever(manager.readProfile(any())).thenAnswer {
            it.getArgument<(Boolean) -> Unit>(0)(false)
            YpsoBleManager.ProfileReadAttempt()
        }

        plugin.getPumpStatus("profile poll")

        verify(manager, never()).readProfile(any())
        verify(manager, never()).readProfileConfiguration(any(), any(), any())
        val profile: Profile = mock { on { getBasalValues() } doReturn arrayOf(ProfileValue(0, 0.5)) }
        assertFalse(plugin.isThisProfileSet(profile))
    }

    @Test
    fun `explicit configuration actions select the requested read mode`() {
        whenever(provisioning.installed()).thenReturn(installed)
        whenever(provisioning.isConfigured()).thenReturn(true)
        whenever(manager.installedPumpMac()).thenReturn("12:34:56:78:9A:BC")
        whenever(manager.isConnected).thenReturn(true)
        whenever(manager.canReadProfile).thenReturn(true)
        whenever(manager.readStatus(any())).thenAnswer {
            it.getArgument<(Boolean) -> Unit>(0)(true)
            YpsoBleManager.StatusReadAttempt()
        }
        val modes = mutableListOf<Boolean>()
        whenever(manager.readProfileConfiguration(any(), any(), any())).thenAnswer {
            modes.add(it.getArgument(0))
            it.getArgument<(Boolean) -> Unit>(2)(true)
            YpsoBleManager.ProfileReadAttempt()
        }
        plugin.getPumpStatus(YpsoPumpPlugin.PROFILE_READ_REASON)
        plugin.getPumpStatus(YpsoPumpPlugin.ACTIVE_PROGRAM_REASON)
        assertEquals(listOf(false, true), modes)
    }

    @Test
    fun `status polling reuses fresh profile evidence without consuming fifty selectors again`() {
        state.elapsedRealtime = { 2_001L }
        state.currentZone = { ZoneId.of("Europe/Warsaw") }
        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        whenever(provisioning.installed()).thenReturn(installed)
        whenever(provisioning.isConfigured()).thenReturn(true)
        whenever(manager.installedPumpMac()).thenReturn("12:34:56:78:9A:BC")
        whenever(manager.isConnected).thenReturn(true)
        whenever(manager.canReadProfile).thenReturn(true)
        whenever(manager.readStatus(any())).thenAnswer {
            it.getArgument<(Boolean) -> Unit>(0)(true)
            YpsoBleManager.StatusReadAttempt()
        }


        plugin.getPumpStatus("profile still fresh")

        verify(manager, never()).readProfile(any())
        assertTrue(state.hasFreshProfileEvidence)
    }

    @Test
    fun `status polling only schedules history recovery and never blocks on it inline`() {
        state.elapsedRealtime = { 2_001L }
        state.currentZone = { ZoneId.of("Europe/Warsaw") }
        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        whenever(provisioning.installed()).thenReturn(installed)
        whenever(provisioning.isConfigured()).thenReturn(true)
        whenever(manager.installedPumpMac()).thenReturn("12:34:56:78:9A:BC")
        whenever(manager.isConnected).thenReturn(true)
        whenever(manager.canReadProfile).thenReturn(true)
        whenever(manager.canReadHistory).thenReturn(true)
        whenever(manager.readStatus(any())).thenAnswer {
            it.getArgument<(Boolean) -> Unit>(0)(true)
            YpsoBleManager.StatusReadAttempt()
        }
        var recovery: (() -> Unit)? = null
        plugin.dispatchHistoryRecovery = { recovery = it }
        whenever(manager.readProfile(any())).thenAnswer {
            assertFalse(state.hasFreshProfileEvidence)
            it.getArgument<(Boolean) -> Unit>(0)(false)
            YpsoBleManager.ProfileReadAttempt()
        }
        plugin.getPumpStatus("manual change")

        verify(manager, never()).readProfile(any())
        verify(manager, never()).readProfileConfiguration(any(), any(), any())
        verify(manager, never()).readStableHistory(any(), any(), any())
        assertNotNull(recovery, "status completion should schedule independent recovery")
        assertTrue(state.hasFreshProfileEvidence)
    }

    @Test
    fun `queue empty does not disconnect an active background history scan`() {
        val active = plugin.javaClass.getDeclaredField("historyRecoveryActive").apply { isAccessible = true }
            .get(plugin) as java.util.concurrent.atomic.AtomicBoolean
        active.set(true)

        plugin.disconnect("Queue empty")

        verify(manager, never()).disconnect(any())
    }

    @Test
    fun `queue empty never disconnects while a pump command is running`() {
        whenever(commandQueue.performing()).thenReturn(mock())

        plugin.disconnect("Queue empty")

        verify(manager, never()).disconnect(any())
    }

    @Test
    fun `history finishing never disconnects a command that started meanwhile`() {
        val active = plugin.javaClass.getDeclaredField("historyRecoveryActive").apply { isAccessible = true }
            .get(plugin) as java.util.concurrent.atomic.AtomicBoolean
        active.set(true)
        plugin.disconnect("Queue empty")
        active.set(false)
        whenever(commandQueue.size()).thenReturn(1)

        plugin.javaClass.getDeclaredMethod("releaseIdleConnection", String::class.java).apply { isAccessible = true }
            .invoke(plugin, "history recovery")

        verify(manager, never()).disconnect(any())
    }

    @Test
    fun `status showing the pump stopped records zero basal at once, and only once`() {
        val stored = mutableListOf<app.aaps.core.data.model.TB>()
        val persistence: app.aaps.core.interfaces.db.PersistenceLayer = mock {
            on { getTemporaryBasalsActiveBetweenTimeAndTime(any(), any()) } doAnswer { stored.toList() }
        }
        plugin.persistenceLayer = persistence
        state.serialNumber = "10054912"
        whenever(sync.addTemporaryBasalWithTempId(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer {
            stored += app.aaps.core.data.model.TB(
                timestamp = it.getArgument(0), rate = 0.0, duration = it.getArgument(2), isAbsolute = true,
                type = app.aaps.core.data.model.TB.Type.PUMP_SUSPEND,
                ids = app.aaps.core.data.model.IDs(pumpType = app.aaps.core.data.pump.defs.PumpType.YPSOPUMP, pumpSerial = "10054912", temporaryId = it.getArgument(4)),
            ).apply { id = 1L }
            true
        }
        val records = plugin.javaClass.getDeclaredField("tbrRecords").apply { isAccessible = true }
            .get(plugin) as app.aaps.pump.ypsopump.tbr.YpsoTbrRecords

        records.reconcileWith(app.aaps.pump.ypsopump.tbr.YpsoTbrObservation(false, 100, 0, 5_000L))
        records.reconcileWith(app.aaps.pump.ypsopump.tbr.YpsoTbrObservation(false, 100, 0, 65_000L))

        verify(sync).addTemporaryBasalWithTempId(
            eq(5_000L), eq(0.0), any(), eq(true), any(), eq(PumpSync.TemporaryBasalType.PUMP_SUSPEND), any(), eq("10054912"),
        )
        assertEquals(1, stored.size)
    }

    @Test
    fun `a stopped pump ends a just-started TBR record before recording the stop`() {
        val stored = mutableListOf<app.aaps.core.data.model.TB>()
        val persistence: app.aaps.core.interfaces.db.PersistenceLayer = mock {
            on { getTemporaryBasalsActiveBetweenTimeAndTime(any(), any()) } doAnswer { inv ->
                val at = inv.getArgument<Long>(0)
                stored.filter { it.timestamp <= at && it.timestamp + it.duration > at }
            }
        }
        plugin.persistenceLayer = persistence
        state.serialNumber = "10054912"
        stored += app.aaps.core.data.model.TB(
            timestamp = 1_000L, rate = 0.0, duration = 30 * 60_000L, isAbsolute = false,
            type = app.aaps.core.data.model.TB.Type.NORMAL,
            ids = app.aaps.core.data.model.IDs(pumpType = app.aaps.core.data.pump.defs.PumpType.YPSOPUMP, pumpSerial = "10054912", temporaryId = 9L),
        ).apply { id = 2L }
        whenever(sync.syncTemporaryBasalWithTempId(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())).thenAnswer {
            val index = stored.indexOfFirst { tb -> tb.ids.temporaryId == it.getArgument<Long>(4) }
            stored[index] = stored[index].copy(duration = it.getArgument(2)).apply { id = stored[index].id }
            true
        }
        whenever(sync.addTemporaryBasalWithTempId(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer {
            stored += app.aaps.core.data.model.TB(
                timestamp = it.getArgument(0), rate = 0.0, duration = it.getArgument(2), isAbsolute = true,
                type = app.aaps.core.data.model.TB.Type.PUMP_SUSPEND,
                ids = app.aaps.core.data.model.IDs(pumpType = app.aaps.core.data.pump.defs.PumpType.YPSOPUMP, pumpSerial = "10054912", temporaryId = it.getArgument(4)),
            ).apply { id = 3L }
            true
        }
        val records = plugin.javaClass.getDeclaredField("tbrRecords").apply { isAccessible = true }
            .get(plugin) as app.aaps.pump.ypsopump.tbr.YpsoTbrRecords

        records.reconcileWith(app.aaps.pump.ypsopump.tbr.YpsoTbrObservation(false, 100, 0, 30_000L))

        assertEquals(listOf(app.aaps.core.data.model.TB.Type.PUMP_SUSPEND), stored.filter { it.timestamp <= 30_000L && it.timestamp + it.duration > 30_000L }.map { it.type })
    }

    @Test
    fun `an idle disconnect never closes the link under a running command`() {
        val lock = plugin.javaClass.getDeclaredField("linkUse").apply { isAccessible = true }
            .get(plugin) as java.util.concurrent.locks.ReentrantLock
        val holder = Thread { lock.lock(); Thread.sleep(300); lock.unlock() }.apply { start() }
        Thread.sleep(50)

        plugin.disconnect("Queue empty")
        holder.join()

        verify(manager, never()).disconnect(any())
    }

    @Test
    fun `an idle queue releases the connection`() {
        plugin.disconnect("Queue empty")

        verify(manager).disconnect(preserveStatus = true)
    }

    @Test
    fun `therapy waits until requested history yield releases operation ownership`() {
        val attempt = YpsoBleManager.HistoryReadAttempt()
        val active = plugin.javaClass.getDeclaredField("historyRecoveryActive").apply { isAccessible = true }
            .get(plugin) as java.util.concurrent.atomic.AtomicBoolean
        val attemptRef = plugin.javaClass.getDeclaredField("historyRecoveryAttempt").apply { isAccessible = true }
            .get(plugin) as java.util.concurrent.atomic.AtomicReference<YpsoBleManager.HistoryReadAttempt?>
        active.set(true)
        attemptRef.set(attempt)
        val release = Thread {
            while (!attempt.shouldYield) Thread.yield()
            active.set(false)
        }.apply { start() }

        assertTrue(plugin.yieldHistoryRecoveryForTherapy(1_000L))

        release.join()
        assertTrue(attempt.shouldYield)
    }

    @Test
    fun `bolus status preflight starts only after background history releases ownership`() {
        val attempt = YpsoBleManager.HistoryReadAttempt()
        val active = plugin.javaClass.getDeclaredField("historyRecoveryActive").apply { isAccessible = true }
            .get(plugin) as java.util.concurrent.atomic.AtomicBoolean
        val attemptRef = plugin.javaClass.getDeclaredField("historyRecoveryAttempt").apply { isAccessible = true }
            .get(plugin) as java.util.concurrent.atomic.AtomicReference<YpsoBleManager.HistoryReadAttempt?>
        active.set(true)
        attemptRef.set(attempt)
        whenever(manager.readStatus(any())).thenAnswer {
            assertFalse(active.get(), "fresh status must not race history operation ownership")
            it.getArgument<(Boolean) -> Unit>(0)(true)
            YpsoBleManager.StatusReadAttempt()
        }
        val release = Thread {
            while (!attempt.shouldYield) Thread.yield()
            active.set(false)
        }.apply { start() }

        val result = plugin.readTherapyStatus(historyYieldTimeoutMs = 1_000L, statusTimeoutMs = 1_000L)

        release.join()
        assertEquals(YpsoPumpPlugin.TherapyStatusReadiness.READY, result)
        verify(manager).readStatus(any())
    }

    @Test
    fun `bolus status preflight does not race history when safe yield times out`() {
        val attempt = YpsoBleManager.HistoryReadAttempt()
        val active = plugin.javaClass.getDeclaredField("historyRecoveryActive").apply { isAccessible = true }
            .get(plugin) as java.util.concurrent.atomic.AtomicBoolean
        val attemptRef = plugin.javaClass.getDeclaredField("historyRecoveryAttempt").apply { isAccessible = true }
            .get(plugin) as java.util.concurrent.atomic.AtomicReference<YpsoBleManager.HistoryReadAttempt?>
        active.set(true)
        attemptRef.set(attempt)

        val result = plugin.readTherapyStatus(historyYieldTimeoutMs = 1L, statusTimeoutMs = 1_000L)

        assertEquals(YpsoPumpPlugin.TherapyStatusReadiness.HISTORY_BUSY, result)
        assertTrue(attempt.shouldYield)
        verify(manager, never()).readStatus(any())
        active.set(false)
    }

    @Test
    fun `foreground app requests status immediately and retains idle connection`() {
        whenever(provisioning.isConfigured()).thenReturn(true)
        whenever(provisioning.retryAllowed(any())).thenReturn(true)
        whenever(appLifecycle.uiVisible).thenReturn(true)
        whenever(manager.installedPumpMac()).thenReturn("12:34:56:78:9A:BC")
        whenever(manager.configureInstalledSession()).thenReturn(true)
        whenever(commandQueue.readStatus(YpsoPumpPlugin.FOREGROUND_CONNECTION_REASON, null)).thenReturn(true)

        plugin.onAppVisibilityChanged(true)
        plugin.disconnect("Queue empty")

        verify(commandQueue).readStatus(YpsoPumpPlugin.FOREGROUND_CONNECTION_REASON, null)
        verify(manager).configureInstalledSession()
        verify(manager).connectConfiguredSession()
        verify(manager, never()).disconnect(any())
    }

    @Test
    fun `authoritative visible lifecycle suppresses queue teardown after callback race`() {
        whenever(appLifecycle.uiVisible).thenReturn(true)

        plugin.disconnect("Queue empty")

        verify(manager, never()).disconnect(any())
    }

    @Test
    fun `background app releases an idle foreground connection`() {
        whenever(provisioning.isConfigured()).thenReturn(true)
        whenever(provisioning.retryAllowed(any())).thenReturn(true)
        whenever(manager.installedPumpMac()).thenReturn("12:34:56:78:9A:BC")
        whenever(manager.configureInstalledSession()).thenReturn(true)
        whenever(commandQueue.readStatus(YpsoPumpPlugin.FOREGROUND_CONNECTION_REASON, null)).thenReturn(true)
        whenever(commandQueue.performing()).thenReturn(null)
        whenever(commandQueue.size()).thenReturn(0)

        plugin.onAppVisibilityChanged(true)
        plugin.onAppVisibilityChanged(false)

        verify(manager).disconnect(preserveStatus = true)
    }

    @Test
    fun `foreground app with unavailable session reports setup without queue timeout`() {
        whenever(provisioning.isConfigured()).thenReturn(false)

        plugin.onAppVisibilityChanged(true)

        verify(commandQueue, never()).readStatus(any(), anyOrNull())
        verify(provisioning).refreshState()
    }

    @Test
    fun `lower bound recovery request is one shot and requires recovery state`() {
        val owner: PumpSession = mock()
        whenever(provisioning.owner).thenReturn(owner)
        whenever(owner.committedRecord()).thenReturn(
            PumpSession.Record(
                pump = "12:34:56:78:9A:BC",
                keyId = "ab".repeat(32),
                generation = "generation",
                reboot = 21,
                read = 100,
                write = 9_035,
                writeBootstrapState = PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND,
                lowerBoundRecoveryReboot = 21,
            ),
        )

        assertTrue(plugin.requestLowerBoundHistoryRecovery { true })
        assertFalse(plugin.requestLowerBoundHistoryRecovery { true })
    }

    @Test
    fun `established session cannot request lower bound recovery`() {
        val owner: PumpSession = mock()
        whenever(provisioning.owner).thenReturn(owner)
        whenever(owner.committedRecord()).thenReturn(
            PumpSession.Record(
                pump = "12:34:56:78:9A:BC",
                keyId = "ab".repeat(32),
                generation = "generation",
                reboot = 21,
                read = 100,
                write = 9_035,
                writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
            ),
        )

        assertFalse(plugin.requestLowerBoundHistoryRecovery { true })
    }

    @Test
    fun `failed lower bound recovery enqueue disarms the one shot request`() {
        val owner: PumpSession = mock()
        whenever(provisioning.owner).thenReturn(owner)
        whenever(owner.committedRecord()).thenReturn(
            PumpSession.Record(
                pump = "12:34:56:78:9A:BC",
                keyId = "ab".repeat(32),
                generation = "generation",
                reboot = 21,
                read = 100,
                write = 9_035,
                writeBootstrapState = PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND,
                lowerBoundRecoveryReboot = 21,
            ),
        )

        assertFalse(plugin.requestLowerBoundHistoryRecovery { false })
        assertTrue(plugin.requestLowerBoundHistoryRecovery { true })
    }

    @Test
    fun `a pump side program switch raises a mismatch alert and clears it when it agrees again`() {
        state.elapsedRealtime = { 2_001L }
        state.currentZone = { ZoneId.of("Europe/Warsaw") }
        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        val matching: Profile = mock { on { getBasalValues() } doReturn arrayOf(ProfileValue(0, 0.5)) }
        val switched: Profile = mock { on { getBasalValues() } doReturn arrayOf(ProfileValue(0, 0.9)) }

        assertTrue(plugin.isThisProfileSet(matching))
        verify(ui, never()).addNotification(eq(Notification.YPSOPUMP_PROFILE_MISMATCH), any(), any())

        assertFalse(plugin.isThisProfileSet(switched))
        verify(ui).addNotification(eq(Notification.YPSOPUMP_PROFILE_MISMATCH), any(), eq(Notification.URGENT))
        // Repeating the same verdict must not re-raise the alert.
        assertFalse(plugin.isThisProfileSet(switched))
        verify(ui, times(1)).addNotification(eq(Notification.YPSOPUMP_PROFILE_MISMATCH), any(), eq(Notification.URGENT))

        assertTrue(plugin.isThisProfileSet(matching))
        verify(rxBus, atLeastOnce()).send(check<EventDismissNotification> { assertEquals(Notification.YPSOPUMP_PROFILE_MISMATCH, it.id) })
    }

    @Test
    fun `a mismatch that moves to another program replaces the text naming the old one`() {
        state.elapsedRealtime = { 2_001L }
        state.currentZone = { ZoneId.of("Europe/Warsaw") }
        whenever(rh.gs(eq(R.string.ypsopump_profile_mismatch_notification), anyVararg()))
            .thenAnswer { "pump profile ${it.getArgument<Any>(1)} differs" }
        val switched: Profile = mock { on { getBasalValues() } doReturn arrayOf(ProfileValue(0, 0.9)) }

        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        plugin.isThisProfileSet(switched)
        // A later read finds a different program still disagreeing; the verdict is unchanged.
        state.publishProfileEvidence(YpsoProfileReadbackTest.verified(YpsoBasalSchedule.Program.B))
        plugin.isThisProfileSet(switched)

        val published = argumentCaptor<String>()
        verify(ui, times(2)).addNotification(eq(Notification.YPSOPUMP_PROFILE_MISMATCH), published.capture(), eq(Notification.URGENT))
        assertEquals(listOf("pump profile A differs", "pump profile B differs"), published.allValues)
    }

    @Test
    fun `a verified matching pump schedule confirms the profile without ever enacting a write`() {
        state.elapsedRealtime = { 2_001L }
        state.currentZone = { ZoneId.of("Europe/Warsaw") }
        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        val matching: Profile = mock { on { getBasalValues() } doReturn arrayOf(ProfileValue(0, 0.5)) }

        val result = plugin.setNewBasalProfile(matching)

        // Success lets AAPS record the effective profile switch, so the loop has a running profile
        // and the keepalive stops re-raising the failed-basal-update alarm every five minutes.
        assertTrue(result.success)
        // Nothing is ever written to this pump; the schedule was programmed by hand.
        assertFalse(result.enacted)
        verifyNoInteractions(manager)
        verify(ui, never()).addNotification(eq(Notification.YPSOPUMP_PROFILE_MISMATCH), any(), any())
    }

    @Test
    fun `an unread or divergent configuration never confirms a profile AAPS cannot prove`() {
        state.elapsedRealtime = { 2_001L }
        state.currentZone = { ZoneId.of("Europe/Warsaw") }
        val matching: Profile = mock { on { getBasalValues() } doReturn arrayOf(ProfileValue(0, 0.5)) }
        val switched: Profile = mock { on { getBasalValues() } doReturn arrayOf(ProfileValue(0, 0.9)) }

        assertFalse(plugin.setNewBasalProfile(matching).success, "nothing was read from the pump")

        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        assertFalse(plugin.setNewBasalProfile(switched).success, "the pump holds a different schedule")
        assertTrue(plugin.setNewBasalProfile(matching).success)
    }

    @Test
    fun `a rejected profile update explains the divergence instead of claiming nothing is verified`() {
        state.elapsedRealtime = { 2_001L }
        state.currentZone = { ZoneId.of("Europe/Warsaw") }
        val switched: Profile = mock { on { getBasalValues() } doReturn arrayOf(ProfileValue(0, 0.9)) }
        whenever(rh.gs(eq(R.string.ypsopump_profile_unread_action), anyVararg())).thenReturn("not read yet")
        whenever(rh.gs(eq(R.string.ypsopump_profile_mismatch_action), anyVararg())).thenReturn("pump profile A differs")

        assertEquals("not read yet", plugin.setNewBasalProfile(switched).comment)

        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        val result = plugin.setNewBasalProfile(switched)

        assertFalse(result.success)
        assertFalse(result.enacted)
        assertEquals("pump profile A differs", result.comment)
        verify(ui).addNotification(eq(Notification.YPSOPUMP_PROFILE_MISMATCH), any(), eq(Notification.URGENT))
    }

    @Test
    fun `an explicit active program check reports a divergence without waiting for the next keepalive`() {
        state.elapsedRealtime = { 2_001L }
        state.currentZone = { ZoneId.of("Europe/Warsaw") }
        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        whenever(provisioning.installed()).thenReturn(installed)
        whenever(provisioning.isConfigured()).thenReturn(true)
        whenever(manager.installedPumpMac()).thenReturn("12:34:56:78:9A:BC")
        whenever(manager.isConnected).thenReturn(true)
        whenever(manager.canReadProfile).thenReturn(true)
        whenever(manager.readStatus(any())).thenAnswer {
            state.publishStatus(80.0, 90, false, 100, 7_000L)
            it.getArgument<(Boolean) -> Unit>(0)(true)
            YpsoBleManager.StatusReadAttempt()
        }
        whenever(manager.readProfileConfiguration(any(), any(), any())).thenAnswer {
            it.getArgument<(Boolean) -> Unit>(2)(true)
            YpsoBleManager.ProfileReadAttempt()
        }
        // The loop is dosing a profile the pump's retained schedule does not deliver.
        val loopProfile: Profile = mock { on { getBasalValues() } doReturn arrayOf(ProfileValue(0, 0.9)) }
        whenever(profileFunction.getProfile()).thenReturn(loopProfile)
        whenever(rh.gs(eq(R.string.ypsopump_profile_read_mismatch), anyVararg())).thenReturn("does not match")

        plugin.getPumpStatus(YpsoPumpPlugin.ACTIVE_PROGRAM_REASON)

        assertEquals(YpsoPumpState.ProfileComparison.MISMATCH, state.profileComparison)
        verify(ui).addNotification(eq(Notification.YPSOPUMP_PROFILE_MISMATCH), any(), eq(Notification.URGENT))
        assertEquals("does not match", state.profileReadMessage)
    }

    @Test
    fun `availability notification replaces stale or changed text but does not churn identical state`() {
        whenever(provisioning.notificationRequired()).thenReturn(true)
        whenever(provisioning.installed()).thenReturn(installed)
        whenever(provisioning.availability()).thenReturn(
            PumpSession.Availability(setOf(PumpSession.AvailabilityCause.TRANSPORT)),
            PumpSession.Availability(setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED)),
        )

        plugin.publishAvailabilityNotification() // first publication replaces a possible pre-start notification
        plugin.publishAvailabilityNotification() // changed presentation replaces the first text
        plugin.publishAvailabilityNotification() // unchanged presentation is a no-op

        inOrder(ui, rxBus) {
            verify(ui).dismissNotification(Notification.YPSOPUMP_UNAVAILABLE)
            verify(ui).addNotification(eq(Notification.YPSOPUMP_UNAVAILABLE), any(), eq(Notification.URGENT))
            verify(ui).dismissNotification(Notification.YPSOPUMP_UNAVAILABLE)
            verify(ui).addNotification(eq(Notification.YPSOPUMP_UNAVAILABLE), any(), eq(Notification.URGENT))
        }
        verifyNoMoreInteractions(rxBus)
        verifyNoMoreInteractions(ui)
    }

    @Test
    fun `repeated unavailable absence dismisses a possible stale notification once`() {
        whenever(provisioning.notificationRequired()).thenReturn(false)

        plugin.publishAvailabilityNotification()
        plugin.publishAvailabilityNotification()

        verify(ui, times(1)).dismissNotification(Notification.YPSOPUMP_UNAVAILABLE)
        verifyNoMoreInteractions(ui)
    }

    @Test
    fun `plugin stop dismisses a published availability notification once`() {
        whenever(provisioning.notificationRequired()).thenReturn(true)
        whenever(provisioning.installed()).thenReturn(installed)
        whenever(provisioning.availability()).thenReturn(
            PumpSession.Availability(setOf(PumpSession.AvailabilityCause.TRANSPORT))
        )

        plugin.publishAvailabilityNotification()
        plugin.onStop()
        plugin.onStop()

        verify(ui, times(2)).dismissNotification(Notification.YPSOPUMP_UNAVAILABLE)
        verify(ui, times(1)).addNotification(eq(Notification.YPSOPUMP_UNAVAILABLE), any(), eq(Notification.URGENT))
    }

    @Test
    fun `a dispatched but unproven cancellation never shortens extended accounting`() {
        // The cancel frames left the phone, but the pump never announced that delivery stopped, so
        // insulin may still be running. Reducing the record here would hide it from IOB.
        val attempt = YpsoBolusAttemptFixtures.extended(baselinePumpId = 100L, slowSequence = 101L).copy(
            outcome = app.aaps.pump.ypsopump.bolus.YpsoBolusOutcome.CANCEL_PENDING,
            cancelRequestId = "cancel",
            cancelCounter = 2,
            cancelBlock = app.aaps.pump.ypsopump.bolus.YpsoBolusBlock.SLOW,
            cancelDispatchedAt = 200_000L,
        )

        val result = finishUnprovenCancellation(attempt)

        assertFalse(result.success)
        verify(sync, never()).correctExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())
        verify(sync, never()).syncExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `a pump announced stop closes extended accounting at the proven instant`() {
        val attempt = YpsoBolusAttemptFixtures.extended(baselinePumpId = 100L, slowSequence = 101L).copy(
            outcome = app.aaps.pump.ypsopump.bolus.YpsoBolusOutcome.CANCEL_PENDING,
            cancelRequestId = "cancel",
            cancelCounter = 2,
            cancelBlock = app.aaps.pump.ypsopump.bolus.YpsoBolusBlock.SLOW,
            cancelDispatchedAt = 200_000L,
            // dispatchedAt is 1_200, so three minutes of the fifteen-minute schedule elapsed.
            blockTerminalAt = 181_200L,
        )
        whenever(sync.getExtendedBolusWithPumpId(any(), any(), any())).thenAnswer {
            app.aaps.core.data.model.EB(
                timestamp = 1_200L, amount = 0.1, duration = 180_000L,
                ids = app.aaps.core.data.model.IDs(
                    pumpId = it.getArgument(0), pumpType = app.aaps.core.data.pump.defs.PumpType.YPSOPUMP,
                    pumpSerial = plugin.serialNumber(),
                ),
            )
        }

        val result = finishUnprovenCancellation(attempt)

        assertTrue(result.success)
        verify(sync).correctExtendedBolusWithPumpId(eq(1_200L), eq(0.1), eq(180_000L), any(), any(), any(), any())
    }

    @Test
    fun `a dose that never proved its identity is merged onto its confirmed history row`() {
        // Delivery happened, but the link dropped before any status proved the block identity, so
        // the provisional record has no pump id. History is importing the same physical insulin.
        val attempt = immediateAttempt(requestedCentiUnits = 200)

        bindUnprovenProvisional(attempt, confirmedCentiUnits = 54, sequence = 48_134)

        verify(sync).syncBolusWithTempId(any(), eq(0.54), any(), anyOrNull(), eq(48_134L), any(), any())
    }

    @Test
    fun `a larger history row is never merged onto a smaller dose`() {
        // The pump cannot deliver more than this command programmed, so the row is another dose.
        // Merging would replace 2.0 U of real insulin with 0.54 U and erase insulin from IOB.
        val attempt = immediateAttempt(requestedCentiUnits = 50)

        bindUnprovenProvisional(attempt, confirmedCentiUnits = 200, sequence = 48_134)

        verify(sync, never()).syncBolusWithTempId(any(), any(), any(), anyOrNull(), any(), any(), any())
    }

    private fun immediateAttempt(requestedCentiUnits: Int) = app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt(
        requestId = "request",
        pumpSerial = PUMP_SERIAL,
        sessionGeneration = "generation",
        treatment = app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment.NORMAL,
        requestedCentiUnits = requestedCentiUnits,
        payloadHash = "ab".repeat(32),
        baseline = app.aaps.pump.ypsopump.bolus.YpsoBolusBaseline(48_000, 20, 48_000, 1, 2, 21, 1_000),
        createdAt = 1_100,
        outcome = app.aaps.pump.ypsopump.bolus.YpsoBolusOutcome.UNRESOLVED,
        dispatchCounter = 1,
        dispatchedAt = 1_200,
    )

    private fun bindUnprovenProvisional(
        attempt: app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt,
        confirmedCentiUnits: Int,
        sequence: Long,
    ) {
        val entry = app.aaps.pump.ypsopump.history.YpsoHistoryEntry(1, 2, confirmedCentiUnits, 0, 0, sequence, 0)
        val event = app.aaps.pump.ypsopump.history.YpsoHistoryEvent(
            app.aaps.pump.ypsopump.history.YpsoEventIdentity(PUMP_SERIAL, 0, sequence), entry,
        )
        val confirmed = app.aaps.pump.ypsopump.bolus.YpsoImmediateBolusReconciliation
            .ConfirmedInsulin(event, confirmedCentiUnits)
        YpsoPumpPlugin::class.java.getDeclaredMethod(
            "bindUnprovenProvisionalBolus",
            String::class.java,
            app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt::class.java,
            app.aaps.pump.ypsopump.bolus.YpsoImmediateBolusReconciliation.ConfirmedInsulin::class.java,
            ZoneId::class.java,
        ).apply { isAccessible = true }.invoke(plugin, PUMP_SERIAL, attempt, confirmed, ZoneId.of("UTC"))
    }

    private companion object {
        /** The plugin publishes its serial from pump state, which these unit tests never populate. */
        const val PUMP_SERIAL = "10000001"
    }

    private fun finishUnprovenCancellation(attempt: app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt) =
        YpsoPumpPlugin::class.java
            .getDeclaredMethod("finishUnprovenExtendedCancellation", app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt::class.java)
            .apply { isAccessible = true }
            .invoke(plugin, attempt) as app.aaps.core.interfaces.pump.PumpEnactResult

}
