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
    fun `pump clock offset is measured on either side of a DST change`() {
        val zone = ZoneId.of("Europe/Warsaw")
        fun offset(pump: LocalDateTime, phone: String) = YpsoPumpLocalTime.clockOffsetMs(pump, Instant.parse(phone).toEpochMilli(), zone)

        // A pump 36 s behind the phone, in both passes of the repeated hour and while it has yet to fall back.
        assertEquals(-36_000L, offset(LocalDateTime.of(2026, 10, 25, 2, 29, 24), "2026-10-25T00:30:00Z"))
        assertEquals(-36_000L, offset(LocalDateTime.of(2026, 10, 25, 2, 29, 24), "2026-10-25T01:30:00Z"))
        assertEquals(-36_000L, offset(LocalDateTime.of(2026, 10, 25, 2, 59, 34), "2026-10-25T01:00:10Z"))
        assertEquals(-36_000L, offset(LocalDateTime.of(2026, 10, 25, 3, 29, 24), "2026-10-25T02:30:00Z"))
        // A pump that has not sprung forward yet: its rows so far carry the offset before the gap.
        assertEquals(-36_000L, offset(LocalDateTime.of(2026, 3, 29, 2, 4, 24), "2026-03-29T01:05:00Z"))
    }
}
