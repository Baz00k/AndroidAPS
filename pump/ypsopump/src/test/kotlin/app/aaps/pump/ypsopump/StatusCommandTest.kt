package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.comm.commands.StatusCommand
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StatusCommandTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `physical TBR normal and stopped captures distinguish basal from battery`() {
        val tbr = StatusCommand().apply { decode(hex("0a09100000024e0000008200000090000000")) }
        val normal = StatusCommand().apply { decode(hex("0a06100000023c0000006400000000000000")) }
        val stopped = StatusCommand().apply { decode(hex("030310000002000000006400000000000000")) }
        listOf(tbr, normal, stopped).forEach {
            assertTrue(it.success)
            assertEquals(2, it.batteryBars)
            assertNull(it.batteryPercent)
        }
        assertEquals(0.78, tbr.basalRate)
        assertEquals(130, tbr.activeTbrPercent)
        assertEquals(144, tbr.tbrRemainingMinutes)
        assertEquals(0.60, normal.basalRate)
        assertEquals(0, normal.tbrRemainingMinutes)
        assertFalse(normal.isSuspended)
        assertEquals(0.0, stopped.basalRate)
        assertTrue(stopped.isSuspended)
        assertEquals(40.99, stopped.reservoirUnits)
    }

    @Test
    fun `unknown layouts enum and ranges reject including decoder reuse`() {
        val valid = hex("0a06100000023c0000006400000000000000")
        val invalid = listOf(valid.dropLast(1).toByteArray(), valid + 0, valid.copyOf().apply { this[0] = 99 },
            valid.copyOf().apply { this[5] = 6 }, valid.copyOf().apply { this[4] = 1 },
            valid.copyOf().apply { this[9] = 1 }, valid.copyOf().apply { this[13] = 1 },
            valid.copyOf().apply { this[17] = 1 }, valid.copyOf().apply { this[0] = 3 })
        invalid.forEach { bytes ->
            val cmd = StatusCommand().apply { decode(valid) }
            assertTrue(cmd.success)
            cmd.decode(bytes)
            assertFalse(cmd.success)
        }
    }
}
