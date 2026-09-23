package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.bolus.YpsoBolusBlock
import app.aaps.pump.ypsopump.comm.YpsoBolusNotification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Frames captured from CONTROL_NOTIFY on firmware V05.00.52 during real bolus runs. */
class YpsoBolusNotificationTest {

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `extended start and its cancellation are reported on the same slow sequence`() {
        // 13:46:29 extended bolus started, then cancel dispatched 13:51:28 and announced 13:51:29.
        val started = requireNotNull(YpsoBolusNotification.decode(hex("000000000001ecbb00005d45f1")))
        val terminal = requireNotNull(YpsoBolusNotification.decode(hex("000000000004ecbb00005e543c")))

        assertEquals(YpsoBolusNotification.STATUS_DELIVERING, started.slowStatusCode)
        assertEquals(48108L, started.slowSequence)
        assertEquals(YpsoBolusNotification.STATUS_COMPLETED, terminal.slowStatusCode)
        assertEquals(48108L, terminal.slowSequence)

        assertFalse(started.isTerminalFor(YpsoBolusBlock.SLOW, 48108L))
        assertTrue(terminal.isTerminalFor(YpsoBolusBlock.SLOW, 48108L))
    }

    @Test
    fun `immediate delivery reports its own terminal transition on the fast block`() {
        val started = requireNotNull(YpsoBolusNotification.decode(hex("01edbb000000000000005f4fb3")))
        val terminal = requireNotNull(YpsoBolusNotification.decode(hex("04edbb000000000000006055ed")))

        assertEquals(YpsoBolusNotification.STATUS_DELIVERING, started.fastStatusCode)
        assertEquals(48109L, started.fastSequence)
        assertEquals(48109L, terminal.fastSequence)
        assertTrue(terminal.isTerminalFor(YpsoBolusBlock.FAST, 48109L))
        // The idle slow block must never be mistaken for this command's terminal evidence.
        assertFalse(terminal.isTerminalFor(YpsoBolusBlock.SLOW, 48109L))
    }

    @Test
    fun `another sequence never resolves this command`() {
        val terminal = requireNotNull(YpsoBolusNotification.decode(hex("000000000004ecbb00005e543c")))

        assertFalse(terminal.isTerminalFor(YpsoBolusBlock.SLOW, 48107L))
        assertFalse(terminal.isTerminalFor(YpsoBolusBlock.SLOW, 48109L))
    }

    @Test
    fun `cancelled code is accepted as terminal alongside completed`() {
        val cancelled = requireNotNull(YpsoBolusNotification.decode(hex("000000000003ecbb00005e543c")))

        assertEquals(YpsoBolusNotification.STATUS_CANCELLED, cancelled.slowStatusCode)
        assertTrue(cancelled.isTerminalFor(YpsoBolusBlock.SLOW, 48108L))
    }

    @Test
    fun `short or unknown bodies are rejected rather than guessed`() {
        assertNull(YpsoBolusNotification.decode(hex("000000000004ecbb")))
        assertNull(YpsoBolusNotification.decode(hex("000000000009ecbb00005e543c")))
    }
}
