package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.compose.theme.AapsTone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PumpSuppliesTest {

    private fun reservoir(level: Double) =
        reservoirSupply(level, critical = 10.0, warning = 30.0) { "%.2f U".format(java.util.Locale.US, it) }

    @Test
    fun `reservoir severity follows the critical and warning thresholds`() {
        assertEquals("8.00 U" to AapsTone.Low, reservoir(8.0).let { it.value to it.dotTone })
        assertEquals(AapsTone.Low, reservoir(10.0).dotTone)
        assertEquals(AapsTone.High, reservoir(20.0).dotTone)
        assertEquals(AapsTone.High, reservoir(30.0).dotTone)
        assertEquals("42.00 U" to AapsTone.InRange, reservoir(42.0).let { it.value to it.dotTone })
    }

    @Test
    fun `an empty reservoir is said out loud`() {
        assertEquals("Empty" to AapsTone.Low, reservoir(0.0).let { it.value to it.dotTone })
    }

    @Test
    fun `no reading is neutral rather than NaN`() {
        assertEquals("—" to AapsTone.Neutral, reservoir(Double.NaN).let { it.value to it.dotTone })
    }

    @Test
    fun `battery thresholds and unavailable zero`() {
        assertEquals("60%" to AapsTone.InRange, batterySupply(60).let { it.value to it.dotTone })
        assertEquals("24%" to AapsTone.Low, batterySupply(24).let { it.value to it.dotTone })
        assertEquals(AapsTone.InRange, batterySupply(25).dotTone)
        assertEquals("—" to AapsTone.InRange, batterySupply(0).let { it.value to it.dotTone })
    }
}
