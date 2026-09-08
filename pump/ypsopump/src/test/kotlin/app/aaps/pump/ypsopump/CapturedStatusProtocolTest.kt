package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.comm.commands.StatusCommand
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoPumpState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Target captures transformed by tests/transform-status-capture.py, not the driver encoder. */
class CapturedStatusProtocolTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun crypto() = SessionCrypto().apply { sharedKey = ByteArray(32) { it.toByte() } }
    private val stopped = listOf(
        "142fd1c1c017eb4678c3d76ff187e084cb6782c8", "24458af9006a106c3385e60aed47898cab13c3df",
        "34a3656123c7a7a3de8d5c250269614216d7bc9e", "44d72b3f175dd6024a2c2d528e1ddf48"
    ).map(::hex)

    @Test
    fun `real crypto integrity decoding and publication of stopped capture`() {
        val body = crypto().decrypt(YpsoFraming.parseMultiFrameRead(stopped))
        assertArrayEquals(hex("030310000002000000006400000000000000a4ec"), body)
        val command = StatusCommand().apply { decode(requireNotNull(YpsoCrc.validatedPayload(body))) }
        assertTrue(command.success)
        val state = YpsoPumpState().apply { elapsedRealtime = { 1000L } }
        state.publishStatus(command.reservoirUnits, command.batteryPercent, command.isSuspended, command.activeTbrPercent, 5000L)
        assertEquals(40.99, state.statusSnapshot?.reservoirUnits)
        assertNull(state.statusSnapshot?.batteryPercent)
        assertTrue(state.isSuspended)
        assertEquals(0.0, command.basalRate)
    }

    @Test
    fun `captured square bolus decrypts with independent public key`() {
        val frames = listOf(
            "162aef7aef8d1ee70091e30be64c4f54bdd07e93", "264467606e7b25b4f2c018a24be1b1ee4d66ffbd",
            "36a7ab1f0ffd7a704de7d790eaa6bdaf6353907d", "46b91d8c53981741309c7e55eb5f1bbed1c76391",
            "56cba6317e5d2f0399f885f866fc790db5fb6760", "666a"
        ).map(::hex)
        val body = crypto().decrypt(YpsoFraming.parseMultiFrameRead(frames))
        val cmd = BolusCommand(0.0).apply { decode(requireNotNull(YpsoCrc.validatedPayload(body))) }
        assertTrue(cmd.success)
        assertTrue(cmd.isDelivering)
        assertEquals(0.50, cmd.extendedTotalUnits)
        assertEquals(0.17, cmd.extendedDeliveredUnits)
        assertEquals(5, cmd.extendedMinutesElapsed)
        assertEquals(15, cmd.extendedMinutesTotal)
    }

    @Test
    fun `tag corruption truncated and reordered frames reject`() {
        val envelope = YpsoFraming.parseMultiFrameRead(stopped)
        envelope[4] = (envelope[4].toInt() xor 1).toByte()
        assertThrows(SecurityException::class.java) { crypto().decrypt(envelope) }
        assertThrows(IllegalArgumentException::class.java) { YpsoFraming.parseMultiFrameRead(stopped.dropLast(1)) }
        assertThrows(IllegalArgumentException::class.java) { YpsoFraming.parseMultiFrameRead(stopped.reversed()) }
        assertThrows(IllegalArgumentException::class.java) {
            YpsoFraming.parseMultiFrameRead(listOf(stopped.first().dropLast(1).toByteArray()) + stopped.drop(1))
        }
    }
}
