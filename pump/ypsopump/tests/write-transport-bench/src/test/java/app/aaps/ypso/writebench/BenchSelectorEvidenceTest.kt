package app.aaps.ypso.writebench

import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoGlb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchSelectorEvidenceTest {

    @Test
    fun `history evidence requires valid CRC and exposes embedded index`() {
        val payload = ByteArray(17).also {
            it[15] = 0x57
            it[16] = 0x01
        }
        val evidence = BenchSelectorEvidenceDecoder.history(YpsoCrc.appendCrc(payload), selectedIndex = 343)

        assertTrue(evidence.crcValid)
        assertEquals(343, evidence.embeddedHistoryIndex)
        assertEquals(true, evidence.semanticMatch)
    }

    @Test
    fun `history evidence rejects malformed CRC for semantic matching`() {
        val body = ByteArray(19).also { it[15] = 17 }
        val evidence = BenchSelectorEvidenceDecoder.history(body, selectedIndex = 17)

        assertFalse(evidence.crcValid)
        assertNull(evidence.embeddedHistoryIndex)
        assertEquals(false, evidence.semanticMatch)
    }

    @Test
    fun `setting evidence is observational and never claims a semantic match`() {
        val exact = BenchSelectorEvidenceDecoder.setting(YpsoGlb.encode(3))
        assertEquals(3, exact.glb)
        assertNull(exact.semanticMatch)

        val crcFramed = BenchSelectorEvidenceDecoder.setting(YpsoCrc.appendCrc(YpsoGlb.encode(10)))
        assertEquals(10, crcFramed.glb)
        assertTrue(crcFramed.crcValid)
        assertNull(crcFramed.semanticMatch)
    }
}
