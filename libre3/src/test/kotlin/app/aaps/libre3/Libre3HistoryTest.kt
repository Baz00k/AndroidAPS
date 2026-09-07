package app.aaps.libre3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class Libre3HistoryTest {

    // --- control command ----------------------------------------------------

    @Test
    fun `control command is 7 packed bytes with kind 1,0`() {
        val cmd = Libre3History.controlHistoryCommand(arg = 1, from = 0)
        assertThat(cmd.size).isEqualTo(7)
        assertThat(cmd[0]).isEqualTo(1)
        assertThat(cmd[1]).isEqualTo(0)      // {1,0} = history; {1,1} would be clinical
        assertThat(cmd[2]).isEqualTo(1)
    }

    @Test
    fun `from is little endian int32`() {
        // 0x00012345 -> 45 23 01 00
        val cmd = Libre3History.controlHistoryCommand(from = 0x00012345)
        assertThat(cmd[3]).isEqualTo(0x45.toByte())
        assertThat(cmd[4]).isEqualTo(0x23.toByte())
        assertThat(cmd[5]).isEqualTo(0x01.toByte())
        assertThat(cmd[6]).isEqualTo(0x00.toByte())
    }

    // --- history payload ----------------------------------------------------

    private fun payload(startLifeCount: Int, vararg values: Int): ByteArray {
        val b = ByteArray(2 + values.size * 2)
        b[0] = (startLifeCount and 0xFF).toByte()
        b[1] = ((startLifeCount shr 8) and 0xFF).toByte()
        values.forEachIndexed { i, v ->
            b[2 + i * 2] = (v and 0xFF).toByte()
            b[3 + i * 2] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }

    @Test
    fun `entries are spaced five minutes apart from the start lifeCount`() {
        val entries = Libre3History.parseHistory(payload(100, 120, 125, 130))
        assertThat(entries).hasSize(3)
        assertThat(entries.map { it.lifeCount }).containsExactly(100, 105, 110).inOrder()
        assertThat(entries.map { it.mgDl }).containsExactly(120, 125, 130).inOrder()
    }

    @Test
    fun `invalid readings are dropped, never clamped or zero filled`() {
        // A gap the sensor could not measure must stay a gap — backfilling a fabricated value
        // is worse than backfilling nothing, because the loop would trust it.
        val entries = Libre3History.parseHistory(payload(200, 120, 0, 700, 130))
        assertThat(entries.map { it.mgDl }).containsExactly(120, 130).inOrder()
        // and the surviving entries keep their true positions in time
        assertThat(entries.map { it.lifeCount }).containsExactly(200, 215).inOrder()
    }

    @Test
    fun `boundary values follow Abbott's 39 to 501 window`() {
        val entries = Libre3History.parseHistory(payload(0, 38, 39, 501, 502))
        assertThat(entries.map { it.mgDl }).containsExactly(39, 501).inOrder()
    }

    @Test
    fun `too short payload yields nothing`() {
        assertThat(Libre3History.parseHistory(ByteArray(3))).isEmpty()
        assertThat(Libre3History.parseHistory(ByteArray(0))).isEmpty()
    }

    @Test
    fun `header alone yields no entries`() {
        assertThat(Libre3History.parseHistory(payload(500))).isEmpty()
    }

    // --- patch status -------------------------------------------------------

    @Test
    fun `patch status decodes twelve packed bytes`() {
        val b = byteArrayOf(
            0x64, 0x00,              // lifeCount 100
            0x00, 0x00,              // errorData
            0x0A, 0x00,              // eventData 10
            0x02,                    // index
            0x03,                    // patchState
            0x6E, 0x00,              // currentLifeCount 110
            0x01, 0x02               // stack / app disconnect reasons
        )
        val s = Libre3PatchStatus.parse(b)!!
        assertThat(s.lifeCount).isEqualTo(100)
        assertThat(s.currentLifeCount).isEqualTo(110)
        assertThat(s.patchState).isEqualTo(3)
        assertThat(s.totalEvents).isEqualTo(3)          // index + 1
        assertThat(s.eventCode).isEqualTo(4010)         // 4000 + eventData
        assertThat(s.stackDisconnectReason).isEqualTo(1)
        assertThat(s.appDisconnectReason).isEqualTo(2)
    }

    @Test
    fun `patch status rejects the wrong length`() {
        assertThat(Libre3PatchStatus.parse(ByteArray(11))).isNull()
        assertThat(Libre3PatchStatus.parse(ByteArray(13))).isNull()
    }
}
