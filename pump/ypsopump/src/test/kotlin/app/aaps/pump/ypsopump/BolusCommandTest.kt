package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BolusCommandTest {

    @Test
    fun `standard encoding is exact and leaves the combination immediate field zero`() {
        assertArrayEquals(
            lePayload(total = 120, duration = 0, immediate = 0, type = BolusCommand.TYPE_IMMEDIATE),
            BolusCommand(1.2).encode(),
        )
        assertArrayEquals(
            lePayload(total = 3000, duration = 0, immediate = 0, type = BolusCommand.TYPE_IMMEDIATE),
            BolusCommand(30.0).encode(),
        )
        assertArrayEquals(
            byteArrayOf(10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            BolusCommand(0.1).encode(),
        )
    }

    @Test
    fun `extended and combination encoding is exact`() {
        assertArrayEquals(
            lePayload(total = 50, duration = 15, immediate = 0, type = BolusCommand.TYPE_EXTENDED),
            BolusCommand(0.5, durationMinutes = 15).encode(),
        )
        assertArrayEquals(
            lePayload(total = 100, duration = 30, immediate = 40, type = BolusCommand.TYPE_EXTENDED),
            BolusCommand(1.0, durationMinutes = 30, immediateUnits = 0.4).encode(),
        )
    }

    @Test
    fun `extended requests reject invalid duration or amount relationships`() {
        assertThrows(IllegalArgumentException::class.java) { BolusCommand(0.5, immediateUnits = 0.4).encode() }
        assertThrows(IllegalArgumentException::class.java) { BolusCommand(0.5, durationMinutes = 15, immediateUnits = 0.6).encode() }
        assertThrows(IllegalArgumentException::class.java) { BolusCommand(0.5, durationMinutes = 15, immediateUnits = 0.05).encode() }
        assertThrows(IllegalArgumentException::class.java) { BolusCommand(0.5, durationMinutes = 1441).encode() }
    }

    @Test
    fun `invalid immediate requests reject instead of rounding or clamping`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -0.01, 0.01, 0.09, 1.05, 30.1, 1.234).forEach { units ->
            assertThrows(IllegalArgumentException::class.java, { BolusCommand(units).encode() }, "units=$units")
        }
        assertThrows(IllegalArgumentException::class.java) { BolusCommand(1.0, immediateUnits = 0.99).encode() }
    }

    @Test
    fun `status decoder exposes fast and slow sequence identities`() {
        val status = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
            .put(BolusCommand.STATUS_DELIVERING.toByte())
            .putInt(0xfedcba98.toInt()).putInt(25).putInt(100)
            .put(BolusCommand.STATUS_MIXED_DELIVERING.toByte())
            .putInt(0x89abcdef.toInt()).putInt(17).putInt(50)
            .putInt(8).putInt(25).putInt(5).putInt(15)
            .array()

        val command = BolusCommand(0.0).apply { decode(status) }

        assertTrue(command.success)
        assertEquals(0xfedcba98L, command.fastSequence)
        assertEquals(0x89abcdefL, command.extendedSequence)
        assertEquals(0.25, command.deliveredUnits)
        assertEquals(1.0, command.totalProgrammedUnits)
        assertEquals(0.08, command.comboImmediateDeliveredUnits)
        assertEquals(0.25, command.comboImmediateTotalUnits)
        assertEquals(5, command.extendedMinutesElapsed)
        assertEquals(15, command.extendedMinutesTotal)
    }

    @Test
    fun `cancel encoding is a distinct all-zero command with only its type set`() {
        assertArrayEquals(ByteArray(13).also { it[12] = 1 }, BolusCommand.cancelPayload(extended = false))
        assertArrayEquals(ByteArray(13).also { it[12] = 2 }, BolusCommand.cancelPayload(extended = true))
    }

    private fun lePayload(total: Int, duration: Int, immediate: Int, type: Byte): ByteArray =
        ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(total).putInt(duration).putInt(immediate).put(type).array()
}
