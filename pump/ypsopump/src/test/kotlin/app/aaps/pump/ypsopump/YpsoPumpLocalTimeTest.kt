package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.history.YpsoPumpLocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class YpsoPumpLocalTimeTest {

    @Test
    fun `paired target factory seconds decode to pump local display time`() {
        assertEquals(LocalDateTime.of(2026, 9, 15, 14, 2, 16), YpsoPumpLocalTime.localDateTime(842_796_136))
        val resolved = assertInstanceOf(
            YpsoPumpLocalTime.Resolution.Resolved::class.java,
            YpsoPumpLocalTime.resolve(842_796_136, ZoneId.of("Europe/Warsaw")),
        )
        assertEquals(Instant.parse("2026-09-15T12:02:16Z"), resolved.instant)
    }

    @Test
    fun `DST gaps and overlaps remain unresolved rather than guessed`() {
        val zone = ZoneId.of("Europe/Warsaw")
        val gapSeconds = java.time.Duration.between(LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2026, 3, 29, 2, 30)).seconds
        val overlapSeconds = java.time.Duration.between(LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2026, 10, 25, 2, 30)).seconds

        assertInstanceOf(YpsoPumpLocalTime.Resolution.Gap::class.java, YpsoPumpLocalTime.resolve(gapSeconds, zone))
        val overlap = assertInstanceOf(YpsoPumpLocalTime.Resolution.Overlap::class.java, YpsoPumpLocalTime.resolve(overlapSeconds, zone))
        assertEquals(2, overlap.offsets.size)
    }

    @Test
    fun `a time AAPS recorded for the event picks the overlap offset`() {
        val zone = ZoneId.of("Europe/Warsaw")
        val seconds = java.time.Duration.between(LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2026, 10, 25, 2, 30)).seconds
        val first = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli()
        val second = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()

        assertEquals(listOf(first, second), YpsoPumpLocalTime.candidates(seconds, zone))
        assertEquals(first, YpsoPumpLocalTime.epochMillis(seconds, zone, near = first + 40_000L))
        assertEquals(second, YpsoPumpLocalTime.epochMillis(seconds, zone, near = second - 40_000L))
        assertNull(YpsoPumpLocalTime.epochMillis(seconds, zone))
        assertNull(YpsoPumpLocalTime.epochMillis(seconds, zone, near = first + 30 * 60_000L))
        assertEquals(Instant.parse("2026-09-15T12:02:16Z").toEpochMilli(), YpsoPumpLocalTime.epochMillis(842_796_136, zone, near = 0L))
    }

    @Test
    fun `pump clock offset is not measured while the pump reads a repeated or skipped wall time`() {
        val zone = ZoneId.of("Europe/Warsaw")
        val phone = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()

        assertNull(YpsoPumpLocalTime.clockOffsetMs(LocalDateTime.of(2026, 10, 25, 2, 29, 24), phone, zone))
        assertNull(YpsoPumpLocalTime.clockOffsetMs(LocalDateTime.of(2026, 3, 29, 2, 30), phone, zone))
        assertEquals(-36_000L, YpsoPumpLocalTime.clockOffsetMs(LocalDateTime.of(2026, 10, 25, 3, 29, 24), Instant.parse("2026-10-25T02:30:00Z").toEpochMilli(), zone))
    }
}
