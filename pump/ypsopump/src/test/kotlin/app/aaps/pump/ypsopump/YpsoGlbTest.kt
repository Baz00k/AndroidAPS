package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.comm.YpsoGlb
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class YpsoGlbTest {
    @Test
    fun `selector is exact little endian value and complement transaction`() {
        assertArrayEquals(
            byteArrayOf(0x78, 0x56, 0x34, 0x12, 0x87.toByte(), 0xa9.toByte(), 0xcb.toByte(), 0xed.toByte()),
            YpsoGlb.encode(0x12345678),
        )
        assertEquals(0x12345678, YpsoGlb.decodeExact(YpsoGlb.encode(0x12345678)))
    }

    @Test
    fun `short trailing or corrupt selector is rejected`() {
        val valid = YpsoGlb.encode(17)
        assertNull(YpsoGlb.decodeExact(valid.copyOf(7)))
        assertNull(YpsoGlb.decodeExact(valid + 0))
        assertNull(YpsoGlb.decodeExact(valid.copyOf().apply { this[7] = 0 }))
    }

    @Test
    fun `readback scanner finds intact GLB without weakening exact write encoding`() {
        assertEquals(42, YpsoGlb.find(byteArrayOf(9, 8) + YpsoGlb.encode(42) + byteArrayOf(7)))
        assertNull(YpsoGlb.find(ByteArray(20)))
    }
}
