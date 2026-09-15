package app.aaps.ypso.writebench

import app.aaps.pump.ypsopump.comm.YpsoGlb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchHistoryCountTest {
    @Test
    fun `authenticated history count accepts exact GLB without CRC`() {
        assertEquals(3000, BenchHistoryCount.decode(YpsoGlb.encode(3000)))
    }

    @Test
    fun `history count rejects malformed or non-exact GLB`() {
        assertNull(BenchHistoryCount.decode(YpsoGlb.encode(3000).copyOf(7)))
        assertNull(BenchHistoryCount.decode(YpsoGlb.encode(3000) + byteArrayOf(0, 0)))
    }
}
