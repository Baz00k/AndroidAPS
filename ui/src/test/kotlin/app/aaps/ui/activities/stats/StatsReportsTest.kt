package app.aaps.ui.activities.stats

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StatsReportsTest {
    private val zone = ZoneId.of("UTC")
    private val from = Instant.parse("2026-10-11T00:00:00Z").toEpochMilli()
    private val to = Instant.parse("2026-10-14T00:00:00Z").toEpochMilli()
    private fun at(text: String) = Instant.parse(text).toEpochMilli()
    private fun reading(time: Long, value: Double) = GV(timestamp = time, value = value, raw = null, noise = null,
                                                       trendArrow = TrendArrow.NONE, sourceSensor = SourceSensor.UNKNOWN)

    @Test fun `completed days are split by local date with their coverage, and the pentagon uses the consensus range`() {
        val day = at("2026-10-12T00:00:00Z")
        val report = summarizeCompletedDays(listOf(
            reading(at("2026-10-11T12:00:00Z"), 60.0), // below 70, outside tight
            reading(at("2026-10-11T12:05:00Z"), 160.0) // in the consensus range, above the user's 70–140 and tight
        ) + (0 until 288).map { reading(day + it * 300_000L, 100.0) } + reading(to, 100.0), // a full day, then today
                                            from, to, 70.0, 140.0, zone)
        assertThat(report.days.map { it.date }).containsExactly(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 11)).inOrder()
        assertThat(report.days[0].readings).isEqualTo(0)
        assertThat(report.days[1].coverage).isEqualTo(100)
        assertThat(report.days[2].low).isEqualTo(50.0)
        assertThat(report.days[2].high).isEqualTo(50.0)
        assertThat(report.days[2].tight).isEqualTo(0.0)
        assertThat(report.daysWithData).isEqualTo(2)
        assertThat(report.readings).isEqualTo(290)
        // 290 of the 864 five-minute slots of three days.
        assertThat(report.coverage).isEqualTo(33)
        assertThat(report.hypo).isWithin(1e-9).of(100.0 / 290)
        assertThat(report.hyper).isEqualTo(0.0)
    }

    @Test fun `duplicate broadcasts count once in mean and SD, and the healthy reference is the reference pentagon`() {
        val report = summarizeCompletedDays(listOf(
            reading(at("2026-10-12T12:00:00Z"), 100.0),
            reading(at("2026-10-12T12:00:30Z"), 100.0),
            reading(at("2026-10-12T12:00:40Z"), 100.0),
            reading(at("2026-10-12T12:05:00Z"), 200.0)
        ), from, to, 70.0, 180.0, zone)
        assertThat(report.mean).isEqualTo(150.0)
        assertThat(report.sd).isEqualTo(50.0)
        // Mean 90 mg/dL with CV 17 % and nothing out of range is the published healthy reference.
        val healthy = CompletedGlucose(readings = 10, coverage = 100, daysWithData = 1, tight = 100.0, hypo = 0.0, hyper = 0.0, mean = 90.0, sd = 15.3, days = emptyList())
        assertThat(glucosePentagon(healthy)!!.ratio).isWithin(1e-9).of(1.0)
        assertThat(glucosePentagon(healthy.copy(hyper = 40.0, mean = 180.0))!!.ratio).isGreaterThan(1.0)
        assertThat(glucosePentagon(healthy.copy(readings = 0, mean = 0.0))).isNull()
    }
}
