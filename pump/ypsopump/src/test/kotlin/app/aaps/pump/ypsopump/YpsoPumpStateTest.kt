package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.data.YpsoPumpState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoPumpStateTest {
    @Test
    fun `reservoir is exposed only with a fresh published status`() {
        val state = YpsoPumpState()

        assertNull(state.reservoirUnitsIfFresh())

        state.publishStatus(
            reservoirUnits = 42.5,
            batteryPercent = 75,
            isSuspended = false,
            activeTbrPercent = 100,
            timestamp = 1234L,
        )

        assertEquals(42.5, state.reservoirUnitsIfFresh())

        state.invalidateStatus()

        assertNull(state.reservoirUnitsIfFresh())
    }

    @Test
    fun `successful status remains healthy while queue is idle`() {
        val state = YpsoPumpState()
        state.publishStatus(42.5, 75, false, 100, 1234L)
        state.connectionState = ConnectionState.DISCONNECTED

        assertTrue(state.hasVerifiedStatus)
        assertTrue(state.connectionHealthy)
    }

    @Test
    fun `disconnected before first status is not healthy`() {
        val state = YpsoPumpState()

        assertFalse(state.hasVerifiedStatus)
        assertFalse(state.connectionHealthy)
    }
}
