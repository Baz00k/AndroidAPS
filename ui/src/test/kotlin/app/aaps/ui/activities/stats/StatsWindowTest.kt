package app.aaps.ui.activities.stats

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.TimeZone

class StatsWindowTest {
    @Test fun `repeated midnight retains established TIR inclusion`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Havana"))
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
