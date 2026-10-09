package app.aaps.pump.common.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class PumpTimeDifferenceDtoTest {

    @Test
    fun `pump ahead is positive behind is negative and milliseconds truncate toward zero`() {
        val device = Instant.parse("2026-03-29T00:59:59.900Z").atZone(ZoneId.of("Europe/Warsaw"))
        listOf(0L to 0, 999L to 0, -999L to 0, 1_999L to 1, -1_999L to -1, 60_000L to 60).forEach { (delta, expected) ->
            val dto = PumpTimeDifferenceDto(device, device.plusNanos(delta * 1_000_000))
            assertEquals(expected, dto.timeDifference)
            dto.pumpTime = device.minusSeconds(5)
            dto.calculateDifference()
            assertEquals(-5, dto.timeDifference)
        }
    }

    @Test
    fun `elapsed seconds do not compare wall clocks across zones or DST`() {
        val before = Instant.parse("2026-03-29T00:59:59Z").atZone(ZoneId.of("Europe/Warsaw"))
        val after = Instant.parse("2026-03-29T01:00:00Z").atZone(ZoneId.of("Europe/Warsaw"))
        val status = object : PumpStatus(app.aaps.core.data.pump.defs.PumpType.GENERIC_AAPS) {
            override val errorInfo: String? = null
        }.apply { pumpTime = PumpTimeDifferenceDto(before, after) }
        assertEquals(1, status.pumpTime!!.timeDifference)
        assertEquals(0, PumpTimeDifferenceDto(before, before.withZoneSameInstant(ZoneOffset.UTC)).timeDifference)
        val first = Instant.parse("2026-10-25T00:30:00Z").atZone(ZoneId.of("Europe/Warsaw"))
        val second = Instant.parse("2026-10-25T01:30:00Z").atZone(ZoneId.of("Europe/Warsaw"))
        assertEquals(3600, PumpTimeDifferenceDto(first, second).timeDifference)
        assertThrows(ArithmeticException::class.java) { PumpTimeDifferenceDto(before, before.plusSeconds(Int.MAX_VALUE.toLong() + 1)) }
    }
}
