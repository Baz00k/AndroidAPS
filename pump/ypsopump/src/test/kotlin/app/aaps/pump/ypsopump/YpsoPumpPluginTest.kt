package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.IntKey
import app.aaps.implementation.pump.PumpEnactResultObject
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.data.YpsoPumpState
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
    private val preferences: Preferences = mock()
    private val provisioning: YpsoProvisioningService = mock()
    private val installed = YpsoProvisioningService.InstalledSession(
        "10175983", "12:34:56:78:9A:BC", "fingerprint", null, Instant.EPOCH, emptyMap(), null,
        PumpSession.Availability(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE))
    )
    private val plugin = YpsoPumpPlugin(
        AAPSLoggerTest(), rh, preferences, mock(), state, manager, sync, mock(), mock(), mock(), ui,
        Provider { PumpEnactResultObject(rh).success(true).enacted(true) }, provisioning
    )

    @Test
    fun `direct Pump requests return non enacted outcomes with a verified status`() {
        state.publishStatus(80.0, 90, false, 100, 4000L)
        val profile: Profile = mock()
        val results = listOf(
            plugin.deliverTreatment(DetailedBolusInfo().apply { insulin = 1.25 }),
            plugin.setNewBasalProfile(profile),
            plugin.setTempBasalAbsolute(1.2, 30, profile, true, PumpSync.TemporaryBasalType.NORMAL),
            plugin.setTempBasalPercent(150, 30, profile, true, PumpSync.TemporaryBasalType.NORMAL),
            plugin.cancelTempBasal(true), plugin.setExtendedBolus(1.0, 30), plugin.cancelExtendedBolus(), plugin.loadTDDs()
        )
        results.forEach { assertFalse(it.success); assertFalse(it.enacted); assertEquals(0.0, it.bolusDelivered) }
        assertEquals(0.0, plugin.baseBasalRate)
        assertFalse(plugin.pumpDescription.isBolusCapable)
        assertFalse(plugin.pumpDescription.isTempBasalCapable)
        verifyNoInteractions(sync, manager)
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
    fun `polling alarms use verified measurements and failed reads cannot fabricate empty reservoir`() {
        whenever(provisioning.installed()).thenReturn(installed)
        whenever(provisioning.isConfigured()).thenReturn(true)
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
}
