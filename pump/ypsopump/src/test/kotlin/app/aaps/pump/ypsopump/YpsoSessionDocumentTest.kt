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
          "pump": {"mac": "EC:2A:F0:00:00:01", "serial": "10000001"},
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
        assertEquals("10000001", document.serial)
        assertEquals("EC:2A:F0:00:00:01", document.mac)
        assertEquals(null, document.rebootCounter)
        assertEquals(4, document.source.size)
        val legacy = current.replace("\"identity\": \"mylife-db-v1\"", "\"identity\": \"mylife-db-v1\", \"donor\": \"private-device\"")
        assertEquals(document.source, YpsoSessionDocumentParser.parse(legacy.toByteArray(), Instant.parse("2026-09-03T00:00:00Z")).source)
    }

    @Test
    fun `strict schema rejects missing unknown duplicate malformed and oversized inputs`() {
        val invalid = listOf(
            current.replace("\"schema_version\": 1,", ""),
            current.replace("\"schema_version\": 1,", "\"schema_version\": 2,"),
            current.replace("\"schema_version\": 1,", "\"schema_version\": 1, \"unknown\": null,"),
            current.replace("\"schema_version\": 1,", "\"schema_version\": 1, \"schema_version\": 1,"),
            current.replace("\"pump\": {\"mac\": \"EC:2A:F0:00:00:01\", \"serial\": \"10000001\"},", ""),
            current.replace(", \"serial\": \"10000001\"", ""),
            current.replace("EC:2A:F0:00:00:01", "not-a-mac"),
            current.replace("${"01".repeat(32)}", "00".repeat(32)),
            current.replace("${"01".repeat(32)}", "01"),
            current.replace("${"01".repeat(32)}", "zz".repeat(32)),
            current.replace("2026-09-01T10:00:00Z", "2026-09-01T10:00:00"),
            current.replace("2026-09-01T10:00:00Z", "2026-09-04T10:00:00Z"),
            current.replace("2026-09-02T10:00:00Z", "2026-09-10T10:00:00Z"),
            current.replace("\"reboot_counter\": null", "\"reboot_counter\": 1.5")
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { YpsoSessionDocumentParser.parse(it.toByteArray(), Instant.parse("2026-09-03T00:00:00Z")) } }
        assertThrows(IllegalArgumentException::class.java) {
            YpsoSessionDocumentParser.parse(ByteArray(YpsoSessionDocumentParser.MAX_DOCUMENT_BYTES + 1))
        }
    }

    @Test
    fun `canonical document accepts an optional integer reboot hint`() {
        val withReboot = current.replace("\"reboot_counter\": null", "\"reboot_counter\": 8")

        assertEquals(8, YpsoSessionDocumentParser.parse(withReboot.toByteArray(), Instant.parse("2026-09-03T00:00:00Z")).rebootCounter)
    }

    @Test
    fun `supported serial is entered independently and must match its MAC`() {
        PumpIdentity.validatePair(" 10000001 ", "ec:2a:f0:00:00:01")
        assertTrue(PumpIdentity.deviceNameMatches("10000001", "mylife YpsoPump 000001"))
        assertTrue(PumpIdentity.deviceNameMatches("10000001", "mylife YpsoPump 10000001"))
        assertTrue(PumpIdentity.deviceNameMatches("10000001", "YpsoPump_10000001"))
        assertEquals("10000001", PumpIdentity.serialFromDeviceName("YpsoPump_10000001"))
        assertTrue(PumpIdentity.isSupportedDeviceName("mylife YpsoPump 000002"))
        assertFalse(PumpIdentity.deviceNameMatches("10000001", "unknown 000001"))
        assertFalse(PumpIdentity.isSupportedDeviceName("YpsoPump_123"))
        assertFalse(PumpIdentity.isSupportedDeviceName("unknown 000001"))
        assertThrows(IllegalArgumentException::class.java) { PumpIdentity.validatePair("10000002", "EC:2A:F0:00:00:01") }
        assertThrows(IllegalArgumentException::class.java) { PumpIdentity.normalizeSerial("12345678") }
        assertThrows(IllegalArgumentException::class.java) { PumpIdentity.normalizeSerial("02AF6F") }
    }
}
