package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.data.YpsoPumpState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
            timestamp = 1234L
        )

        assertEquals(42.5, state.reservoirUnitsIfFresh())

        state.invalidateStatus()

        assertNull(state.reservoirUnitsIfFresh())
    }
}
