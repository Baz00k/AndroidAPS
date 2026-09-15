package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoCommandReadiness
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoCommandReadinessTest {
    private val readiness = YpsoCommandReadiness()
    private val gatt = Any()
    private val owner = YpsoCommandReadiness.Owner(gatt, "connection", "generation")

    @Test
    fun `connected authenticated read verified and command ready remain separate`() {
        readiness.connected(owner)
        assertFalse(readiness.snapshot(owner, counterCertain = true, setupRequired = true).commandReady)
        assertTrue(readiness.authenticated(owner))
        assertFalse(readiness.snapshot(owner, counterCertain = true, setupRequired = true).readVerified)
        assertTrue(readiness.readVerified(owner))
        assertFalse(readiness.snapshot(owner, counterCertain = true, setupRequired = true).commandReady)
        assertTrue(readiness.requiredSetupVerified(owner))
        assertTrue(readiness.snapshot(owner, counterCertain = true, setupRequired = true).commandReady)
    }

    @Test
    fun `status read does not substitute for setup or counter certainty`() {
        readiness.connected(owner)
        readiness.authenticated(owner)
        readiness.readVerified(owner)

        assertFalse(readiness.snapshot(owner, counterCertain = true, setupRequired = true).commandReady)
        readiness.requiredSetupVerified(owner)
        assertFalse(readiness.snapshot(owner, counterCertain = false, setupRequired = true).commandReady)
    }

    @Test
    fun `stale GATT connection or generation cannot inherit readiness`() {
        readiness.connected(owner)
        readiness.authenticated(owner)
        readiness.readVerified(owner)
        readiness.requiredSetupVerified(owner)

        assertFalse(readiness.authenticated(YpsoCommandReadiness.Owner(Any(), "connection", "generation")))
        assertFalse(readiness.snapshot(YpsoCommandReadiness.Owner(gatt, "connection", "other"), true, true).commandReady)
        readiness.disconnected(gatt)
        assertFalse(readiness.snapshot(owner, true, true).connected)
    }
}
