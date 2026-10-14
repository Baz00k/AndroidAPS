package app.aaps.ui.activities.stats

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.TimeZone

class StatsGlucoseTest {
    private fun reading(time: Long, value: Double) = GV(timestamp = time, value = value, raw = null, noise = null,
                                                       trendArrow = TrendArrow.NONE, sourceSensor = SourceSensor.UNKNOWN)
    private fun summarize(raw: List<GV>, low: Double = 70.0, high: Double = 180.0) =
        summarizeGlucose(raw, 300_000, 900_000, 0, 600_000, low, high, { it / 18 }, TimeZone.getTimeZone("UTC"))

    @Test fun `one snapshot preserves raw TIR and rolling bucket percentiles in display units`() {
        val summary = summarize(listOf(reading(0, 54.0), reading(300_000, 70.0), reading(300_001, 110.0),
                                       reading(600_000, 180.0), reading(900_000, 360.0)))
        assertThat(summary.bands).containsExactly(0.0, 25.0, 75.0, 0.0, 0.0).inOrder()
        assertThat(summary.tirReadings).isEqualTo(4)
        assertThat(summary.readings).isEqualTo(3)
        assertThat(summary.average).isEqualTo(210.0) // Rolling bucket means: 90, 180, 360.
        assertThat(summary.hourly[0].median).isEqualTo(10.0)
        assertThat(summary.hourly[0].p25).isEqualTo(7.5)
        assertThat(summary.hourly[0].p75).isEqualTo(15.0)
    }

    @Test fun `custom targets outside clinical extremes still have disjoint bands`() {
        val summary = summarize(listOf(reading(0, 45.0), reading(1, 52.0), reading(2, 260.0), reading(3, 310.0)), 50.0, 300.0)
        assertThat(summary.bands).containsExactly(25.0, 0.0, 50.0, 0.0, 25.0).inOrder()
    }

    @Test fun `invalid history is empty rather than manufacturing statistics`() {
        val summary = summarize(listOf(reading(300_000, Double.NaN), reading(300_001, Double.POSITIVE_INFINITY), reading(600_000, 38.0)))
        assertThat(summary.readings).isEqualTo(0)
        assertThat(summary.tirReadings).isEqualTo(0)
        assertThat(summary.hourly.all { it.readings == 0 }).isTrue()
    }
}
