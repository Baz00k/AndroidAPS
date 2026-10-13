package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.lifecycle.AppLifecycle
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.pump.PumpEnactResultObject
import app.aaps.pump.ypsopump.ble.YpsoGattLayout
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import javax.inject.Provider

/** Real plugin, manager, link, framing, CRC, decode and state on a fake pump; mock only AEAD transport. */
class YpsoStatusIntegrationTest {
    @Test
    fun `authenticated polling preserves counter and idle acquisition across expiry and recovery`() {
        val fixture = YpsoPumpFixture(logger = AAPSLoggerTest())
        var counter = 0L
        // Synthetic running status: reservoir 550 centi-units, 2 battery bars, basal 0.85 U/h.
        // CRC computed independently using a Python bitwise polynomial loop.
        whenever(fixture.sessionCrypto.decrypt(any(), any())).thenAnswer {
            SessionCrypto.Message(hex("0a2602000002550000006400000000000000a9d1"), 8, ++counter)
        }
        var elapsed = 1_000L
        val state = fixture.pumpState.apply { elapsedRealtime = { elapsed } }
        val provisioning = fixture.provisioning
        whenever(provisioning.installed()).thenReturn(
            YpsoProvisioningService.InstalledSession(
                "10000001", fixture.mac, "fingerprint", null, null, emptyMap(), null,
                PumpSession.Availability(setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN)),
                "test-generation", app.aaps.pump.ypsopump.crypto.KeyTiming(),
            )
        )
        whenever(provisioning.isConfigured()).thenReturn(true)
        val rh: ResourceHelper = mock()
        whenever(rh.gs(any())).thenReturn("localized")
        whenever(rh.gs(any(), anyVararg())).thenReturn("localized")
        val preferences: Preferences = mock()
        whenever(preferences.get(IntKey.OverviewResCritical)).thenReturn(10)
        val sync: PumpSync = mock()
        val ui: UiInteraction = mock()
        val plugin = YpsoPumpPlugin(AAPSLoggerTest(), rh, preferences, mock(), state, fixture.manager, sync, mock(), ui,
                                    Provider { PumpEnactResultObject(rh) }, provisioning, mock(), mock(), mock<AppLifecycle>())
        // Status polling schedules bounded background history recovery. Run it inline so the
        // queue-empty disconnect assertions below cannot race the recovery lease.
        plugin.dispatchHistoryRecovery = { task -> task() }
        var readSucceeds = true
        fixture.pump.configure = { connection ->
            connection.refuseRead = { it == YpsoGattLayout.CHAR_STATUS && !readSucceeds }
            connection.onRead = { if (it == YpsoGattLayout.CHAR_STATUS) connection.answerRead(it, YpsoPumpFixture.SINGLE_FRAME) }
        }
        fixture.connect()
        plugin.getPumpStatus("poll")
        assertEquals(5.5, plugin.reservoirLevel)
        assertEquals(40, plugin.batteryLevel)
        val acquired = plugin.lastDataTime
        assertTrue(acquired > 0)
        val date: DateUtil = mock()
        whenever(date.minOrSecAgo(org.mockito.kotlin.eq(rh), any())).thenReturn("reading age")
        fun display() = buildPumpStatusState(state, idleCommandQueue(), date, rh)
        assertEquals(5.5, display().reservoir)
        assertEquals(40, display().battery)
        plugin.disconnect("Queue empty")
        elapsed += 299_999
        assertEquals(acquired, plugin.lastDataTime)
        assertEquals(5.5, plugin.reservoirLevel)
        assertEquals(5.5, display().reservoir)
        assertEquals(false, display().connectionHealthy) // Link disconnected, sample remains current.
        elapsed++
        assertEquals(0L, plugin.lastDataTime)
        assertTrue(plugin.reservoirLevel.isNaN())
        assertNull(plugin.batteryLevel)
        assertEquals(5.5, display().reservoir)
        assertEquals(40, display().battery)
        assertEquals(false, display().connectionHealthy)
        fixture.connect()
        assertEquals(0L, plugin.lastDataTime)
        plugin.getPumpStatus("recovery")
        assertEquals(5.5, plugin.reservoirLevel)
        readSucceeds = false
        plugin.getPumpStatus("failed poll")
        assertTrue(plugin.reservoirLevel.isNaN())
        assertEquals(0L, plugin.lastDataTime)
        assertEquals(5.5, display().reservoir)
        assertEquals(40, display().battery)
        assertEquals(false, display().connectionHealthy)
        verify(ui, times(2)).addNotification(org.mockito.kotlin.eq(Notification.PUMP_RESERVOIR_LOW), any(), org.mockito.kotlin.eq(Notification.URGENT))
        verifyNoInteractions(sync)
        assertEquals(0L, fixture.manager.writeCounter)
        // Independent Python hashlib MD5(mac bytes + documented access salt), public synthetic MAC.
        val expectedAuth = YpsoGattLayout.CHAR_AUTH to hex("04319d09e5ba61be2acf95ebebffe38a").toList()
        assertEquals(listOf(expectedAuth, expectedAuth), fixture.pump.connections.flatMap { c -> c.writes.map { it.first to it.second.toList() } })
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
