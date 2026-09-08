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
        state.publishStatus(command.reservoirUnits, command.batteryPercent, command.isSuspended, command.activeTbrPercent, 5000L, command.batteryBars)
        assertEquals(40.99, state.statusSnapshot?.reservoirUnits)
        assertNull(state.statusSnapshot?.batteryPercent)
        assertEquals(2, state.statusSnapshot?.batteryBars)
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
    fun `captured mixed and standard bolus states decode activity without terminal claims`() {
        val mixed = listOf(
            "16bcfc495d3553eb628a1bf28fab9ebd80582220", "264c51a9302bbb034bbf78caa52fbea698e0908a",
            "36d1948c6bb9c4a668439d4dc14678d17ef085cf", "462c295c87cdbc0412ae1f011d3735795e0aba87",
            "565d7b6041cb592742dce82efd28b54c86947f8f", "661a"
        ).map(::hex)
        val mixedBody = crypto().decrypt(YpsoFraming.parseMultiFrameRead(mixed))
        assertArrayEquals(
            hex("000000000000000000000000000387b9000032000000640000003200000032000000010000000f00000060c8"),
            mixedBody
        )
        val mixedCmd = BolusCommand(0.0).apply { decode(requireNotNull(YpsoCrc.validatedPayload(mixedBody))) }
        assertTrue(mixedCmd.success)
        assertTrue(mixedCmd.isDelivering)
        assertEquals(BolusCommand.STATUS_MIXED_DELIVERING, mixedCmd.extendedStatusCode)
        assertEquals(1.00, mixedCmd.extendedTotalUnits)
        assertEquals(0.50, mixedCmd.extendedDeliveredUnits)
        assertFalse(mixedCmd.isCompleted)
        assertFalse(mixedCmd.isCancelled)

        val standard = listOf(
            "16f3acace3ecf191ddee5ed9ee45ace6623ac820", "26fe19fa093ece03b74a64aa1ab77460f5639f0c",
            "364628a046607b3a256d74b530607667fcd2d6bc", "46da93dc66b343f802f00115694846b6540c0193",
            "564e142a7b7ecaa5401f305d46cd1070e1141569", "6655"
        ).map(::hex)
        val standardBody = crypto().decrypt(YpsoFraming.parseMultiFrameRead(standard))
        val standardCmd = BolusCommand(0.0).apply { decode(requireNotNull(YpsoCrc.validatedPayload(standardBody))) }
        assertTrue(standardCmd.success)
        assertTrue(standardCmd.isDelivering)
        assertEquals(1.00, standardCmd.totalProgrammedUnits)
        assertEquals(0.79, standardCmd.deliveredUnits, 0.0001)
    }

    @Test
    fun `captured zero-percent TBR decodes zero basal with remaining minutes`() {
        val frames = listOf(
            "149adcf8618cf76666f2160bd666a4f0dfb25a12", "24939517e74e6d7c9ed81c790bc55dde5d814c8c",
            "34eda1ce966b68b3b1eb799d709d6092b5e4283b", "4471ce266e4cef4355192710545e1e0f"
        ).map(::hex)
        val body = crypto().decrypt(YpsoFraming.parseMultiFrameRead(frames))
        assertArrayEquals(hex("0a7e0d00000300000000000000003b000000d660"), body)
        val command = StatusCommand().apply { decode(requireNotNull(YpsoCrc.validatedPayload(body))) }
        assertTrue(command.success)
        assertFalse(command.isSuspended)
        assertEquals(0.0, command.basalRate)
        assertEquals(0, command.activeTbrPercent)
        assertEquals(59, command.tbrRemainingMinutes)
        assertEquals(3, command.batteryBars)
        val state = YpsoPumpState().apply { elapsedRealtime = { 1000L } }
        state.publishStatus(command.reservoirUnits, command.batteryPercent, command.isSuspended, command.activeTbrPercent, 5000L, command.batteryBars)
        assertEquals(0, state.activeTbrPercent)
        assertFalse(state.isSuspended)
        assertEquals(60, state.mappedBatteryPercent)
    }

    @Test
    fun `no-cartridge sentinel authenticates but fails closed with no published measurement`() {
        val frames = listOf(
            "14ae0e2a6d65cd3086114ea1c7a2066a4f2966e2", "24f3b5083fccc274dbe3ecbabab8377c18a80e30",
            "34b580d534c651e54d77b5a6a39e05133592e638", "446680c8ff84ceaf5963821ad5b11529"
        ).map(::hex)
        val body = crypto().decrypt(YpsoFraming.parseMultiFrameRead(frames))
        assertArrayEquals(hex("03ffffffff0300000000640000000000000076be"), body)
        val payload = requireNotNull(YpsoCrc.validatedPayload(body))
        val command = StatusCommand().apply { decode(payload) }
        assertFalse(command.success)
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
