package app.aaps.libre3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class Libre3PatchInfoTest {

    /** A synthetic but structurally exact 26-byte record. */
    private fun record(
        gen: Int = 1,
        wearMinutes: Int = 15 * 24 * 60,
        warmupUnits: Int = 12,
        state: Int = 2,
        serial: String = "XX0TM9UWU"
    ): ByteArray {
        val b = ByteArray(Libre3PatchInfo.SIZE)
        fun le16(o: Int, v: Int) { b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte() }
        le16(0, 3)                  // secVersion
        le16(2, 1)                  // localization
        le16(4, gen)
        le16(6, wearMinutes)
        le16(8, 0x1234); le16(10, 0x0000)   // firmwareVersion
        b[12] = 4
        b[13] = warmupUnits.toByte()
        b[14] = state.toByte()
        serial.forEachIndexed { i, c -> b[15 + i] = c.code.toByte() }
        return b
    }

    @Test
    fun `record decodes to the documented fields`() {
        val p = Libre3PatchInfo.parseRecord(record())!!
        assertThat(p.secVersion).isEqualTo(3)
        assertThat(p.puckGeneration).isEqualTo(1)
        assertThat(p.state).isEqualTo(2)
        assertThat(p.serialNumber).isEqualTo("XX0TM9UWU")
    }

    @Test
    fun `warmup is in units of five minutes`() {
        // 12 units = the familiar 60-minute warm-up. Reading the field as minutes would give 12.
        assertThat(Libre3PatchInfo.parseRecord(record(warmupUnits = 12))!!.warmupMinutes).isEqualTo(60)
    }

    @Test
    fun `generation one is the fifteen day Plus`() {
        assertThat(Libre3PatchInfo.parseRecord(record(gen = 1))!!.isPlus).isTrue()
        assertThat(Libre3PatchInfo.parseRecord(record(gen = 0))!!.isPlus).isFalse()
    }

    @Test
    fun `wear duration converts to days`() {
        assertThat(Libre3PatchInfo.parseRecord(record(wearMinutes = 15 * 24 * 60))!!.wearDurationDays)
            .isEqualTo(15)
        assertThat(Libre3PatchInfo.parseRecord(record(wearMinutes = 14 * 24 * 60))!!.wearDurationDays)
            .isEqualTo(14)
    }

    @Test
    fun `padding skip consumes one byte past the last 0xA5`() {
        // Juggluco's `while(*iter++ == 0xa5);` post-increments past the first non-padding byte
        // too. Treating it as "stop at the first non-0xA5" shifts every field by one.
        val rec = record()
        val framed = byteArrayOf(0x00) + ByteArray(3) { 0xA5.toByte() } + byteArrayOf(0x11) + rec
        val p = Libre3PatchInfo.parse(framed)!!
        assertThat(p.serialNumber).isEqualTo("XX0TM9UWU")
        assertThat(p.warmupMinutes).isEqualTo(60)
    }

    @Test
    fun `serial stops at a null terminator`() {
        val b = record(serial = "ABC")
        assertThat(Libre3PatchInfo.parseRecord(b)!!.serialNumber).isEqualTo("ABC")
    }

    @Test
    fun `short buffers are refused rather than half parsed`() {
        assertThat(Libre3PatchInfo.parse(ByteArray(10))).isNull()
        assertThat(Libre3PatchInfo.parseRecord(ByteArray(25))).isNull()
    }

    @Test
    fun `lifecycle states are distinguishable`() {
        assertThat(Libre3PatchInfo.parseRecord(record(state = Libre3PatchInfo.STATE_EXPIRED))!!.state)
            .isEqualTo(Libre3PatchInfo.STATE_EXPIRED)
        assertThat(Libre3PatchInfo.parseRecord(record(state = Libre3PatchInfo.STATE_TERMINATED))!!.state)
            .isEqualTo(Libre3PatchInfo.STATE_TERMINATED)
    }
}
