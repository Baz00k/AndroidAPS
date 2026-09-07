package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.comm.YpsoCrc
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoCrcTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Pins the provisional CRC implementation to a synthetic vector; this is not target validation. */
    @Test
    fun `crc16 matches a known vector`() {
        val payload = hex("000102030405060708090a0b0c0d0e0f10")
        assertArrayEquals(hex("1e8f"), YpsoCrc.crc16(payload))
        assertTrue(YpsoCrc.isValid(payload + hex("1e8f")))
    }

    @Test
    fun `appendCrc then isValid round-trips`() {
        val payload = hex("0102030405")
        val encoded = YpsoCrc.appendCrc(payload)

        assertTrue(YpsoCrc.isValid(encoded))
        assertArrayEquals(payload, YpsoCrc.validatedPayload(encoded))
    }

    @Test
    fun `isValid rejects a corrupted trailer`() {
        val p = YpsoCrc.appendCrc(hex("aabbccdd"))
        p[p.size - 1] = (p[p.size - 1] + 1).toByte()
        assertFalse(YpsoCrc.isValid(p))
        assertNull(YpsoCrc.validatedPayload(p))
    }
}
