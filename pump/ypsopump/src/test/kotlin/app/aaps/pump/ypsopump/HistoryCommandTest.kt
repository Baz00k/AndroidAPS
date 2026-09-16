package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.comm.YpsoCommandCodes
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.comm.commands.CountCommand
import app.aaps.pump.ypsopump.comm.commands.EventValueCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryCommandTest {

    @Test
    fun `count accepts only an exact non-negative GLB`() {
        val command = CountCommand(YpsoCommandCodes.EVENT_ENTRY_COUNT)
        command.decode(YpsoGlb.encode(321))
        assertTrue(command.success)
        assertEquals(321, command.entryCount)

        command.decode(byteArrayOf(0x41, 0x01, 0x00, 0x00))
        assertFalse(command.success)
        command.decode(YpsoGlb.encode(-1))
        assertFalse(command.success)
    }

    @Test
    fun `event value rejects anything but an exact CRC-valid wire row`() {
        val command = EventValueCommand()
        command.decode(byteArrayOf(1, 2, 3))
        assertFalse(command.success)
        assertNull(command.entry)

        command.decode("78563412060000000000006c00000000001319".hex())
        assertTrue(command.success)
        assertEquals(6, command.entry?.eventType)
    }

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
