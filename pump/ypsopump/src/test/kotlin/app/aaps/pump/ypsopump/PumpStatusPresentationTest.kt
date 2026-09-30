package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.data.YpsoPumpState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.util.Locale

class PumpStatusPresentationTest {
    private val strings = mapOf(
        R.string.ypsopump_connected to "Connected",
        R.string.ypsopump_disconnected to "Not connected",
        R.string.ypsopump_connecting to "Connecting…",
        R.string.ypsopump_awaiting_readings to "Connected · awaiting readings",
        R.string.ypsopump_authenticated_no_status to "No readings yet.",
        R.string.ypsopump_short_status to "Reservoir %1\$.2f U Battery %2\$d%%",
        R.string.ypsopump_short_status_reservoir to "Reservoir %1\$.2f U",
    )
    private val rh: ResourceHelper = mock {
        on { gs(any<Int>()) } doAnswer { strings.getValue(it.getArgument(0)) }
        on { gs(any<Int>(), anyVararg()) } doAnswer {
            String.format(Locale.US, strings.getValue(it.getArgument(0)), *it.arguments.drop(1).toTypedArray())
        }
    }

    @Test
    fun `connected before first reading has one waiting state on both surfaces`() {
        val state = YpsoPumpState().apply { connectionState = ConnectionState.CONNECTED }
        val presentation = pumpStatusPresentation(state, rh)
        assertEquals("Connected · awaiting readings", presentation.connectionSummary)
        assertEquals(presentation.connectionSummary, presentation.shortStatus)
        assertNull(presentation.snapshot)
        assertFalse(presentation.connectionHealthy)
    }

    @Test
    fun `last readings stay shown through disconnect reconnect failure and recovery`() {
        var elapsed = 1000L
        val state = YpsoPumpState().apply { elapsedRealtime = { elapsed } }
        state.publishStatus(42.5, null, false, 100, 1234L, batteryBars = 3)
        var presentation = pumpStatusPresentation(state, rh)
        assertEquals("Not connected", presentation.connectionSummary)
        assertEquals("Reservoir 42.50 U Battery 60%", presentation.shortStatus)
        assertFalse(presentation.connectionHealthy)

        elapsed += YpsoPumpState.STATUS_MAX_AGE_MS
        for (connection in ConnectionState.entries) {
            state.connectionState = connection
            presentation = pumpStatusPresentation(state, rh)
            assertEquals(42.5, presentation.snapshot?.reservoirUnits)
            assertEquals(60, presentation.battery)
            assertEquals("Reservoir 42.50 U Battery 60%", presentation.shortStatus)
            assertFalse(presentation.connectionHealthy)
        }

        state.connectionState = ConnectionState.CONNECTED
        state.invalidateStatus(preserveDisplay = true)
        assertEquals(presentation.shortStatus, pumpStatusPresentation(state, rh).shortStatus)
        state.publishStatus(40.0, null, false, 100, 2345L)
        presentation = pumpStatusPresentation(state, rh)
        assertTrue(presentation.connectionHealthy)
        assertNull(presentation.battery)
        assertEquals("Reservoir 40.00 U", presentation.shortStatus)
    }

    @Test
    fun `reservoir and battery are taken from the same snapshot at expiry`() {
        val state = YpsoPumpState().apply { elapsedRealtime = { 0L }; connectionState = ConnectionState.CONNECTED }
        state.publishStatus(0.0, 0, false, 100, 1234L)
        var elapsed = YpsoPumpState.STATUS_MAX_AGE_MS - 1
        state.elapsedRealtime = { elapsed++ }
        val presentation = pumpStatusPresentation(state, rh)
        assertEquals(0.0, presentation.snapshot?.reservoirUnits)
        assertEquals(0, presentation.battery)
        assertEquals("Reservoir 0.00 U Battery 0%", presentation.shortStatus)
        assertTrue(presentation.connectionHealthy)
    }
}
