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
    private fun summarize(raw: List<GV>, convert: (Double) -> Double = { it }) =
        summarizeGlucose(raw, 0, 86_400_000, 0, 43_200_000, 70.0, 180.0, convert, TimeZone.getTimeZone("UTC"))

    @Test fun `clinical boundaries are inclusive and TIR does not deduplicate`() {
        val summary = summarize(listOf(39.0, 53.0, 54.0, 69.0, 70.0, 180.0, 181.0, 250.0, 251.0, 300.0).map { reading(1, it) })
        assertThat(summary.bands).containsExactly(20.0, 20.0, 20.0, 20.0, 20.0).inOrder()
        assertThat(summary.tirReadings).isEqualTo(10)
        assertThat(summary.readings).isEqualTo(1)
        assertThat(summary.average).isWithin(1e-10).of(144.7)
    }

    @Test fun `rolling glucose and completed day TIR retain their different windows`() {
        val summary = summarizeGlucose(listOf(reading(1, 90.0), reading(43_200_000, 180.0), reading(86_400_000, 270.0)),
                                       43_200_000, 86_400_000, 0, 43_200_000, 70.0, 180.0, { it }, TimeZone.getTimeZone("UTC"))
        assertThat(summary.average).isEqualTo(225.0)
        assertThat(summary.tirReadings).isEqualTo(2)
        assertThat(summary.bands[2]).isEqualTo(100.0)
    }

    @Test fun `percentiles interpolate bucket means and use explicit display units`() {
        val raw = listOf(reading(0, 90.0), reading(1, 270.0), reading(300_000, 360.0))
        val mgdl = summarize(raw)
        val mmol = summarize(raw) { it / 18 }
        assertThat(mgdl.average).isEqualTo(270.0)
        assertThat(mgdl.cv).isWithin(1e-10).of(100.0 / 3)
        assertThat(mmol.hourly[0].p10).isEqualTo(11.0)
        assertThat(mmol.hourly[0].p25).isEqualTo(12.5)
        assertThat(mmol.hourly[0].median).isEqualTo(15.0)
        assertThat(mmol.hourly[0].p75).isEqualTo(17.5)
        assertThat(mmol.hourly[0].p90).isEqualTo(19.0)
        assertThat(mmol.hourly[0].days).isEqualTo(1)
        assertThat(mmol.bands).isEqualTo(mgdl.bands)
    }

    @Test fun `empty and invalid history has no manufactured percentages or glucose`() {
        val summary = summarize(listOf(reading(0, Double.NaN), reading(1, Double.POSITIVE_INFINITY), reading(2, 38.0)))
        assertThat(summary.readings).isEqualTo(0)
        assertThat(summary.tirReadings).isEqualTo(0)
        assertThat(summary.bands).containsExactly(0.0, 0.0, 0.0, 0.0, 0.0)
        assertThat(summary.hourly.all { it.readings == 0 }).isTrue()
    }

    @Test fun `constant readings have zero CV`() {
        assertThat(summarize(listOf(reading(0, 100.0), reading(300_000, 100.0))).cv).isEqualTo(0.0)
    }

    @Test fun `custom targets outside clinical extremes still have disjoint bands`() {
        val summary = summarizeGlucose(listOf(reading(0, 45.0), reading(300_000, 52.0), reading(600_000, 260.0), reading(900_000, 310.0)),
                                       0, 1_000_000, 0, 1_000_000, 50.0, 300.0, { it }, TimeZone.getTimeZone("UTC"))
        assertThat(summary.bands).containsExactly(25.0, 0.0, 50.0, 0.0, 25.0).inOrder()
        assertThat(summary.bands.sum()).isEqualTo(100.0)
    }

    @Test fun `hour sample depth counts calendar days across a year boundary`() {
        val a = java.time.Instant.parse("2025-12-31T01:00:00Z").toEpochMilli()
        val b = java.time.Instant.parse("2026-01-01T01:00:00Z").toEpochMilli()
        val summary = summarizeGlucose(listOf(reading(a, 100.0), reading(b, 100.0)), a, b, a, b,
                                       70.0, 180.0, { it }, TimeZone.getTimeZone("UTC"))
        assertThat(summary.hourly[1].days).isEqualTo(2)
    }
}
