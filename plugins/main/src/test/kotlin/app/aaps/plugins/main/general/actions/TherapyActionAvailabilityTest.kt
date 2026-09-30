package app.aaps.plugins.main.general.actions

import app.aaps.core.data.model.RM
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TherapyActionAvailabilityTest {

    @Test
    fun `a temporary target can be set only while the loop runs with a profile`() {
        assertTrue(TherapyActionAvailability.tempTarget(targetActive = false, hasProfile = true, mode = RM.Mode.CLOSED_LOOP))
        assertTrue(TherapyActionAvailability.tempTarget(targetActive = false, hasProfile = true, mode = RM.Mode.OPEN_LOOP))
        assertFalse(TherapyActionAvailability.tempTarget(targetActive = false, hasProfile = false, mode = RM.Mode.CLOSED_LOOP))
        assertFalse(TherapyActionAvailability.tempTarget(targetActive = false, hasProfile = true, mode = RM.Mode.DISABLED_LOOP))
        assertFalse(TherapyActionAvailability.tempTarget(targetActive = false, hasProfile = true, mode = RM.Mode.SUSPENDED_BY_USER))
    }

    @Test
    fun `an active target stays editable even when the loop is stopped`() {
        assertTrue(TherapyActionAvailability.tempTarget(targetActive = true, hasProfile = false, mode = RM.Mode.DISABLED_LOOP))
        assertTrue(TherapyActionAvailability.tempTarget(targetActive = true, hasProfile = true, mode = RM.Mode.SUSPENDED_BY_USER))
    }

    private fun extended(
        capable: Boolean = true, initialized: Boolean = true, suspended: Boolean = false,
        mode: RM.Mode = RM.Mode.CLOSED_LOOP, faking: Boolean = false, client: Boolean = false
    ) = TherapyActionAvailability.extendedBolus(capable, initialized, suspended, mode, faking, client)

    @Test
    fun `extended bolus needs a capable, initialized, delivering pump`() {
        assertTrue(extended())
        assertFalse(extended(capable = false))
        assertFalse(extended(initialized = false))
        assertFalse(extended(suspended = true))
    }

    @Test
    fun `extended bolus is not offered when disconnected, emulating temps, or as a client`() {
        assertFalse(extended(mode = RM.Mode.DISCONNECTED_PUMP))
        assertFalse(extended(faking = true))
        assertFalse(extended(client = true))
        assertTrue(extended(mode = RM.Mode.SUSPENDED_BY_USER))
    }
}
