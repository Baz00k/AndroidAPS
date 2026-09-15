package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.comm.YpsoCommandCodes
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.comm.commands.CountCommand
import app.aaps.pump.ypsopump.comm.commands.IndexCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `index is an exact GLB selector`() {
        assertEquals(YpsoGlb.encode(42).toList(), IndexCommand(YpsoCommandCodes.EVENT_ENTRY_INDEX, 42).encode().toList())
    }
}
