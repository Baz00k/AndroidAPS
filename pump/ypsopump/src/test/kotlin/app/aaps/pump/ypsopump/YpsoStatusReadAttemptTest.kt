package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoBleManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoStatusReadAttemptTest {

    @Test
    fun `timeout cancellation prevents late status publication`() {
        val attempt = YpsoBleManager.StatusReadAttempt()

        assertTrue(attempt.cancel())
        assertFalse(attempt.tryComplete())
    }

    @Test
    fun `callback completion prevents timeout cancellation`() {
        val attempt = YpsoBleManager.StatusReadAttempt()

        assertTrue(attempt.tryComplete())
        assertFalse(attempt.cancel())
    }
}
