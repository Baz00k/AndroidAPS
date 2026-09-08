package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.data.YpsoFirmwareVersion
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class YpsoFirmwareVersionTest {

    @Test
    fun `minimum is inclusive and comparisons are numeric`() {
        listOf("V04.99.99", "V05.00.51").forEach { assertFalse(YpsoFirmwareVersion.parse(it)!!.meetsMinimum) }
        listOf("V05.00.52", "V05.02.03", "V06.00.00", "V10.00.00").forEach {
            assertTrue(YpsoFirmwareVersion.parse(it)!!.meetsMinimum)
        }
    }

    @Test
    fun `service strings malformed and absent firmware never imply eligibility`() {
        listOf("", "1.1", "5.0.52", "V05.00.52-extra", " V05.00.52", "V05.00.52\u0000").forEach {
            assertNull(YpsoFirmwareVersion.parse(it))
        }
        assertNull(YpsoFirmwareVersion.fromWire("V05.00.52".toByteArray()))
        assertNull(YpsoFirmwareVersion.fromWire("V05.00.52\u0000\u0000".toByteArray()))
        assertEquals(YpsoFirmwareVersion.MINIMUM, YpsoFirmwareVersion.fromWire("V05.00.52\u0000".toByteArray()))
    }
}
