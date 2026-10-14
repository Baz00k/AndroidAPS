package app.aaps.ui.activities.stats

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class StatsReportsTest {
    private val zone = ZoneId.of("UTC")
    private val from = Instant.parse("2026-10-11T00:00:00Z").toEpochMilli()
    private val to = Instant.parse("2026-10-14T00:00:00Z").toEpochMilli()
    private fun at(text: String) = Instant.parse(text).toEpochMilli()
    private fun reading(time: Long, value: Double) = GV(timestamp = time, value = value, raw = null, noise = null,
                                                       trendArrow = TrendArrow.NONE, sourceSensor = SourceSensor.UNKNOWN)

    @Test fun `completed days are split by local date, keep empty days and exclude the unfinished day`() {
        val report = summarizeCompletedDays(listOf(
            reading(at("2026-10-11T12:00:00Z"), 60.0), // below 70, outside tight
            reading(at("2026-10-11T12:05:00Z"), 120.0), // in range and tight
            reading(at("2026-10-13T12:00:00Z"), 200.0), // above range
            reading(to, 100.0) // today
        ), from, to, 70.0, 180.0, zone)
        assertThat(report.days.map { it.date }).containsExactly(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 11)).inOrder()
        assertThat(report.days[1].readings).isEqualTo(0)
        assertThat(report.days[2].low).isEqualTo(50.0)
        assertThat(report.days[2].tight).isEqualTo(50.0)
        assertThat(report.days[0].high).isEqualTo(100.0)
        assertThat(report.readings).isEqualTo(3)
        assertThat(report.tight).isWithin(1e-9).of(100.0 / 3)
        // 3 of the 864 five-minute slots of three days have a reading.
        assertThat(report.coverage).isEqualTo(0)
        assertThat(report.days[2].coverage).isEqualTo(0)
    }

    @Test fun `Dexcom high limit is lower at night`() {
        val report = summarizeCompletedDays(listOf(
            reading(at("2026-10-12T02:00:00Z"), 160.0), // night: above 8.3 mmol/L
            reading(at("2026-10-12T12:00:00Z"), 160.0), // day: in range
            reading(at("2026-10-12T22:59:00Z"), 160.0), // still day
            reading(at("2026-10-12T23:00:00Z"), 160.0)  // night
        ), from, to, 70.0, 180.0, zone)
        assertThat(report.dexcom).containsExactly(0.0, 0.0, 50.0, 50.0, 0.0).inOrder()
        // The consensus range used by the pentagon has no night limit.
        assertThat(report.hyper).isEqualTo(0.0)
    }

    @Test fun `mean and SD use one value per five minutes so duplicate broadcasts do not count twice`() {
        val report = summarizeCompletedDays(listOf(
            reading(at("2026-10-12T12:00:00Z"), 100.0),
            reading(at("2026-10-12T12:00:30Z"), 100.0),
            reading(at("2026-10-12T12:00:40Z"), 100.0),
            reading(at("2026-10-12T12:05:00Z"), 200.0)
        ), from, to, 70.0, 180.0, zone)
        assertThat(report.mean).isEqualTo(150.0)
        assertThat(report.sd).isEqualTo(50.0)
        assertThat(report.readings).isEqualTo(4)
    }

    @Test fun `a full day of five minute readings is complete coverage`() {
        val day = at("2026-10-12T00:00:00Z")
        val report = summarizeCompletedDays((0 until 288).map { reading(day + it * 300_000L, 100.0) }, from, to, 70.0, 180.0, zone)
        assertThat(report.days[1].coverage).isEqualTo(100)
        assertThat(report.coverage).isEqualTo(33)
    }

    @Test fun `healthy reference values draw the reference pentagon and no data draws none`() {
        // Mean 90 mg/dL with CV 17 % and nothing out of range is the published healthy reference.
        val healthy = CompletedGlucose(readings = 10, coverage = 100, tight = 100.0, dexcom = listOf(0.0, 0.0, 100.0, 0.0, 0.0),
                                       hypo = 0.0, hyper = 0.0, mean = 90.0, sd = 15.3, days = emptyList())
        assertThat(glucosePentagon(healthy)!!.ratio).isWithin(1e-9).of(1.0)
        val worse = glucosePentagon(healthy.copy(hyper = 40.0, mean = 180.0))!!
        assertThat(worse.outOfRange).isEqualTo(40.0)
        assertThat(worse.ratio).isGreaterThan(1.0)
        assertThat(glucosePentagon(healthy.copy(readings = 0, mean = 0.0))).isNull()
    }

    @Test fun `estimated A1c follows the ADAG table`() {
        // ADAG: 154 mg/dL corresponds to 7 % (53 mmol/mol IFCC).
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertThat(estimatedA1c(154.0)).isEqualTo("7.0% · 53 mmol/mol")
        } finally {
            Locale.setDefault(original)
        }
    }
}
