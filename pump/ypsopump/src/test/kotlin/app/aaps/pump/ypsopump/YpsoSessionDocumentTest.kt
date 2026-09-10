package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.provisioning.PumpIdentity
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocumentParser
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoSessionDocumentTest {
    private val current = """
        {
          "schema_version": 1,
          "pump": {"mac": "EC:2A:F0:02:AF:6F", "serial": "10175983"},
          "shared_key": "${"01".repeat(32)}",
          "created_at": "2026-09-01T10:00:00Z",
          "captured_at": "2026-09-02T10:00:00Z",
          "reboot_counter": null,
          "source": {"profile": "mylife-maui-v1", "package": "com.ypsomed.mylife", "app_version": "2.6.1.001", "identity": "mylife-db-v1"}
        }
    """.trimIndent()

    @Test
    fun `current canonical CLI document parses without retaining donor identity`() {
        val document = YpsoSessionDocumentParser.parse(current.toByteArray(), Instant.parse("2026-09-03T00:00:00Z"))
        assertEquals("10175983", document.serial)
        assertEquals("EC:2A:F0:02:AF:6F", document.mac)
        assertEquals(null, document.rebootCounter)
        assertEquals(4, document.source.size)
        val legacy = current.replace("\"identity\": \"mylife-db-v1\"", "\"identity\": \"mylife-db-v1\", \"donor\": \"private-device\"")
        assertEquals(document.source, YpsoSessionDocumentParser.parse(legacy.toByteArray(), Instant.parse("2026-09-03T00:00:00Z")).source)
    }

    @Test
    fun `strict schema rejects missing unknown duplicate malformed and oversized inputs`() {
        val invalid = listOf(
            current.replace("\"schema_version\": 1,", ""),
            current.replace("\"schema_version\": 1,", "\"schema_version\": 1, \"unknown\": null,"),
            current.replace("\"schema_version\": 1,", "\"schema_version\": 1, \"schema_version\": 1,"),
            current.replace("EC:2A:F0:02:AF:6F", "not-a-mac"),
            current.replace("${"01".repeat(32)}", "00".repeat(32)),
            current.replace("2026-09-01T10:00:00Z", "2026-09-04T10:00:00Z"),
            current.replace("\"reboot_counter\": null", "\"reboot_counter\": 1.5")
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { YpsoSessionDocumentParser.parse(it.toByteArray(), Instant.parse("2026-09-03T00:00:00Z")) } }
        assertThrows(IllegalArgumentException::class.java) {
            YpsoSessionDocumentParser.parse(ByteArray(YpsoSessionDocumentParser.MAX_DOCUMENT_BYTES + 1))
        }
    }

    @Test
    fun `supported serial is entered independently and must match its MAC`() {
        PumpIdentity.validatePair(" 10175983 ", "ec:2a:f0:02:af:6f")
        assertTrue(PumpIdentity.deviceNameMatches("10175983", "mylife YpsoPump 175983"))
        assertTrue(PumpIdentity.deviceNameMatches("10175983", "mylife YpsoPump 10175983"))
        assertTrue(PumpIdentity.deviceNameMatches("10054912", "YpsoPump_10054912"))
        assertEquals("10054912", PumpIdentity.serialFromDeviceName("YpsoPump_10054912"))
        assertTrue(PumpIdentity.isSupportedDeviceName("mylife YpsoPump 175984"))
        assertFalse(PumpIdentity.deviceNameMatches("10175983", "unknown 175983"))
        assertFalse(PumpIdentity.isSupportedDeviceName("YpsoPump_123"))
        assertFalse(PumpIdentity.isSupportedDeviceName("unknown 175983"))
        assertThrows(IllegalArgumentException::class.java) { PumpIdentity.validatePair("10175984", "EC:2A:F0:02:AF:6F") }
        assertThrows(IllegalArgumentException::class.java) { PumpIdentity.normalizeSerial("02AF6F") }
    }
}
