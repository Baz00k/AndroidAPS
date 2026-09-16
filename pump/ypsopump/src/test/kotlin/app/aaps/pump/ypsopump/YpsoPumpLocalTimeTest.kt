package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.history.YpsoPumpLocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
}
