package app.aaps.ui.activities.stats

import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.TimeZone

class StatsWindowTest {
    @Test fun `repeated midnight retains established TIR inclusion and skipped midnight does not split TDD`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Havana"))
            for (text in listOf("2026-11-01T17:00:00Z", "2026-11-08T17:00:00Z", "2026-03-09T16:00:00Z")) {
                val now = Instant.parse(text).toEpochMilli()
                val window = StatsWindow(now, 7)
                assertThat(window.tirTo).isEqualTo(MidnightTime.calc(now))
                assertThat(window.tirFrom).isEqualTo(MidnightTime.calcDaysBack(now, 7))
                assertThat(window.canSplitTdd).isFalse()
            }
            assertThat(StatsWindow(Instant.parse("2026-11-01T17:00:00Z").toEpochMilli(), 7).tirTo)
                .isEqualTo(Instant.parse("2026-11-01T05:00:00Z").toEpochMilli())
            assertThat(StatsWindow(Instant.parse("2026-11-08T17:00:00Z").toEpochMilli(), 7).tirFrom)
                .isEqualTo(Instant.parse("2026-11-01T05:00:00Z").toEpochMilli())
            val disputed = GV(timestamp = Instant.parse("2026-11-01T04:30:00Z").toEpochMilli(), value = 100.0,
                               raw = null, noise = null, trendArrow = TrendArrow.NONE, sourceSensor = SourceSensor.UNKNOWN)
            val endWindow = StatsWindow(Instant.parse("2026-11-01T17:00:00Z").toEpochMilli(), 7)
            val startWindow = StatsWindow(Instant.parse("2026-11-08T17:00:00Z").toEpochMilli(), 7)
            fun count(window: StatsWindow) = summarizeGlucose(listOf(disputed), window.rollingFrom, window.now,
                                                               window.tirFrom, window.tirTo, 70.0, 180.0, { it }).tirReadings
            assertThat(count(endWindow)).isEqualTo(1)
            assertThat(count(startWindow)).isEqualTo(0)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
