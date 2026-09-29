package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.compose.theme.AapsTone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PumpSuppliesTest {

    private fun reservoir(level: Double, stale: Boolean = false) =
        reservoirSupply(level, stale, critical = 10.0, warning = 30.0) { "%.2f U".format(java.util.Locale.US, it) }

    @Test
    fun `stale values keep their text and severity but are flagged`() {
        val current = reservoir(8.0)
        val stale = reservoir(8.0, stale = true)
        assertEquals("8.00 U" to AapsTone.Low, current.value to current.dotTone)
        assertEquals(current.value to current.dotTone, stale.value to stale.dotTone)
        assertFalse(current.stale)
        assertTrue(stale.stale)
        assertEquals(AapsTone.High, reservoir(20.0, stale = true).dotTone)
        assertEquals(AapsTone.InRange, reservoir(42.0, stale = true).dotTone)
    }

    @Test
    fun `a stale empty reservoir is flagged so it is not read as a current fact`() {
        assertEquals("Empty" to AapsTone.Low, reservoir(0.0).let { it.value to it.dotTone })
        assertFalse(reservoir(0.0).stale)
        assertTrue(reservoir(0.0, stale = true).stale)
    }

    @Test
    fun `no reading is neutral rather than NaN`() {
        val supply = reservoir(Double.NaN)
        assertEquals("—" to AapsTone.Neutral, supply.value to supply.dotTone)
        assertFalse(supply.stale)
    }

    @Test
    fun `battery thresholds, stale flag and unavailable zero`() {
        assertEquals("60%" to AapsTone.InRange, batterySupply(60, false).let { it.value to it.dotTone })
        assertEquals("24%" to AapsTone.Low, batterySupply(24, true).let { it.value to it.dotTone })
        assertTrue(batterySupply(24, true).stale)
        assertEquals(AapsTone.InRange, batterySupply(25, false).dotTone)
        assertEquals("—" to AapsTone.InRange, batterySupply(0, true).let { it.value to it.dotTone })
    }
}
