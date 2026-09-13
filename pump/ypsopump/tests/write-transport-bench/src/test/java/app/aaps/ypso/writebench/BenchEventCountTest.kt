package app.aaps.ypso.writebench

import app.aaps.pump.ypsopump.comm.YpsoGlb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchEventCountTest {
    @Test
    fun `authenticated event count accepts exact GLB without CRC`() {
        assertEquals(3000, BenchEventCount.decode(YpsoGlb.encode(3000)))
    }

    @Test
    fun `event count rejects malformed or non-exact GLB`() {
        assertNull(BenchEventCount.decode(YpsoGlb.encode(3000).copyOf(7)))
        assertNull(BenchEventCount.decode(YpsoGlb.encode(3000) + byteArrayOf(0, 0)))
    }
}
