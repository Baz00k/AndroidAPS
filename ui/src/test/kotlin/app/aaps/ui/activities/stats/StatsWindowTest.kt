package app.aaps.ui.activities.stats

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class StatsWindowTest {
    private val havana = ZoneId.of("America/Havana")

    @Test fun `days start at their first local instant across repeated and skipped midnights`() {
        // Havana repeats midnight on 2026-11-01: 04:30 UTC is 00:30 local on the first pass, so it is November 1.
        val reading = Instant.parse("2026-11-01T04:30:00Z").toEpochMilli()
        val endingThatDay = StatsWindow(Instant.parse("2026-11-01T17:00:00Z").toEpochMilli(), 7, havana)
        val startingThatDay = StatsWindow(Instant.parse("2026-11-08T17:00:00Z").toEpochMilli(), 7, havana)
        assertThat(reading).isAtLeast(endingThatDay.to)
        assertThat(reading).isIn(com.google.common.collect.Range.closedOpen(startingThatDay.from, startingThatDay.to))
        // Havana skips midnight on 2026-03-08; the day starts at 01:00 local, 05:00 UTC.
        val skipped = StatsWindow(Instant.parse("2026-03-09T16:00:00Z").toEpochMilli(), 1, havana)
        assertThat(skipped.from).isEqualTo(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli())
        assertThat(skipped.to).isEqualTo(Instant.parse("2026-03-09T04:00:00Z").toEpochMilli())
    }
}
