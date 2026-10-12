package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.compose.theme.AapsTone
import app.aaps.core.interfaces.pump.Pump
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeyExpirySupplyTest {
    @Test fun `unknown timing is neutral and not a healthy green indicator`() {
        assertEquals(AapsTone.Neutral, keyExpirySupply(Pump.KeyExpiryIndicator(null, false, false, false), "Pump key", "—").dotTone)
    }

    @Test fun `reminder warns while overdue takes priority`() {
        val key = Pump.KeyExpiryIndicator(1000, false, false, true)
        assertEquals(AapsTone.InRange, keyExpirySupply(key, "Pump key", "~1d").dotTone)
        assertEquals(AapsTone.High, keyExpirySupply(key.copy(reminderDue = true), "Pump key", "~1d").dotTone)
        assertEquals(AapsTone.Low, keyExpirySupply(key.copy(due = true, reminderDue = true), "Pump key", "Renew key").dotTone)
    }
}
