package app.aaps.ypso.writebench

import app.aaps.pump.ypsopump.comm.YpsoCrc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BenchCurrentHistoryObservationTest {

    @Test
    fun `consecutive index zero and one reads remain observational even when count and reboot match`() {
        val observation = BenchCurrentHistoryObservationDecoder.assess(
            firstBody = wire(sequence = 102, index = 0),
            secondBody = wire(sequence = 101, index = 1),
            countBefore = 3000,
            countAfter = 3000,
            rebootBefore = 21,
            rebootAfter = 21,
        )

        assertTrue(observation.startedAtLogicalHead)
        assertTrue(observation.countStable)
        assertTrue(observation.rebootStable)
        assertFalse(observation.stableHeadCursor)
        assertEquals("ADVANCING_SELECTOR_OBSERVATION_ONLY", observation.disposition)
    }

    private fun wire(sequence: Int, index: Int): ByteArray {
        val payload = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(842_796_136)
            .put(2)
            .putShort(120)
            .putShort(0)
            .putShort(0)
            .putInt(sequence)
            .putShort(index.toShort())
            .array()
        return YpsoCrc.appendCrc(payload)
    }
}
