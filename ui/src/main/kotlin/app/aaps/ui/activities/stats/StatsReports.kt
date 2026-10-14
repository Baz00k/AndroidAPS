package app.aaps.ui.activities.stats

import androidx.compose.runtime.Immutable
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GV
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Below this share of expected five-minute readings a period or day is flagged, as in CGM reporting consensus. */
internal const val MIN_COVERAGE = 70

private const val SLOT = 300_000L
private const val LOW = Constants.STATS_RANGE_LOW_MMOL * Constants.MMOLL_TO_MGDL
private const val HIGH = Constants.STATS_RANGE_HIGH_MMOL * Constants.MMOLL_TO_MGDL
private const val TIGHT_LOW = Constants.STATS_TARGET_LOW_MMOL * Constants.MMOLL_TO_MGDL
private const val TIGHT_HIGH = Constants.STATS_TARGET_HIGH_MMOL * Constants.MMOLL_TO_MGDL

/** One completed local day. Percentages are 0..100 of that day's raw readings; coverage is of its five-minute slots. */
@Immutable
data class DayGlucose(
    val date: LocalDate,
    val readings: Int,
    val coverage: Int,
    val low: Double,
    val inRange: Double,
    val tight: Double,
    val high: Double
)

/**
 * Completed-day reports. Percentages count raw readings, like the headline time in range; mean and SD
 * use five-minute buckets, like the glucose tiles, so duplicate broadcasts do not weight a reading twice.
 * All glucose values are mg/dL.
 */
@Immutable
data class CompletedGlucose(
    val readings: Int,
    val coverage: Int,
    /** Days with at least one reading. */
    val daysWithData: Int,
    val tight: Double,
    /** Below and above the 3.9–10.0 mmol/L consensus range, for the pentagon. */
    val hypo: Double,
    val hyper: Double,
    val mean: Double,
    val sd: Double,
    val days: List<DayGlucose>
)

/** Readings in [from, to): the day starting at [to] is not complete yet. */
internal fun summarizeCompletedDays(raw: List<GV>, from: Long, to: Long, low: Double, high: Double, zone: ZoneId): CompletedGlucose {
    require(low >= 39 && low <= high) { "Invalid glucose range" }
    class Day(val date: LocalDate) {
        var readings = 0; var below = 0; var inRange = 0; var tight = 0; var above = 0
        val slots = HashSet<Long>()
    }
    val days = linkedMapOf<LocalDate, Day>()
    var date = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
    val last = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
    while (date.isBefore(last)) {
        days[date] = Day(date)
        date = date.plusDays(1)
    }
    var hypo = 0; var hyper = 0; var count = 0
    val buckets = linkedMapOf<Long, DoubleArray>()
    val clock = LocalHour(zone)
    for (reading in raw) {
        val value = reading.value
        if (!value.isFinite() || value < 39 || reading.timestamp < from || reading.timestamp >= to) continue
        clock.at(reading.timestamp)
        val day = days[clock.date] ?: continue
        count++
        day.readings++
        day.slots += reading.timestamp / SLOT
        when {
            value < low -> day.below++
            value <= high -> day.inRange++
            else -> day.above++
        }
        if (value in TIGHT_LOW..TIGHT_HIGH) day.tight++
        if (value < LOW) hypo++
        if (value > HIGH) hyper++
        buckets.getOrPut(reading.timestamp / SLOT) { DoubleArray(2) }.let { it[0] += value; it[1]++ }
    }
    val values = buckets.values.map { it[0] / it[1] }
    val mean = if (values.isEmpty()) 0.0 else values.average()
    val sd = if (values.size > 1) sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size) else 0.0
    fun pct(value: Int, total: Int) = if (total == 0) 0.0 else value * 100.0 / total
    return CompletedGlucose(
        readings = count,
        coverage = coverage(values.size, from, to),
        daysWithData = days.values.count { it.readings > 0 },
        tight = pct(days.values.sumOf { it.tight }, count),
        hypo = pct(hypo, count),
        hyper = pct(hyper, count),
        mean = mean,
        sd = sd,
        days = days.values.reversed().map { day ->
            val start = day.date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = day.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            DayGlucose(day.date, day.readings, coverage(day.slots.size, start, end),
                       pct(day.below, day.readings), pct(day.inRange, day.readings), pct(day.tight, day.readings), pct(day.above, day.readings))
        }
    )
}

private fun coverage(slots: Int, from: Long, to: Long): Int =
    if (to <= from) 0 else (slots * 100L / ((to - from) / SLOT).coerceAtLeast(1)).toInt().coerceAtMost(100)

/** Local date of ascending timestamps, without a calendar lookup for every reading. */
private class LocalHour(private val zone: ZoneId) {
    var date: LocalDate = LocalDate.MIN
        private set
    private var start = Long.MAX_VALUE
    private var end = Long.MIN_VALUE

    fun at(time: Long) {
        if (time in start until end) return
        val local = Instant.ofEpochMilli(time).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        date = local.toLocalDate()
        start = local.toInstant().toEpochMilli()
        end = local.plusHours(1).toInstant().toEpochMilli()
    }
}

/**
 * Glucose pentagon, simplified from Vigersky et al. (2018) as AAPS 4.0 does: out of range, CV, below and
 * above range as percentages and the mean, each scaled 0.18..1 between a healthy reference and a cap.
 * Ranges are the 3.9–10.0 mmol/L consensus all day, as in the published pentagon.
 * [ratio] is the pentagon's area over the reference's. It is a comparison, not a validated risk score.
 */
@Immutable
data class Pentagon(
    val outOfRange: Double,
    val cv: Double,
    val hypo: Double,
    val hyper: Double,
    val mean: Double,
    val radii: List<Double>,
    val reference: List<Double>,
    val ratio: Double
)

private const val BASELINE = 0.18
private val CAPS = listOf(100.0, 60.0, 20.0, 80.0, 300.0)
private val HEALTHY = listOf(0.0, 17.0, 0.0, 0.0, 90.0)

internal fun glucosePentagon(glucose: CompletedGlucose): Pentagon? {
    if (glucose.readings == 0 || glucose.mean <= 0) return null
    val values = listOf(glucose.hypo + glucose.hyper, glucose.sd / glucose.mean * 100.0, glucose.hypo, glucose.hyper, glucose.mean)
    fun scale(list: List<Double>) = list.mapIndexed { i, value -> BASELINE + (1.0 - BASELINE) * (value / CAPS[i]).coerceIn(0.0, 1.0) }
    val radii = scale(values)
    val reference = scale(HEALTHY)
    fun area(r: List<Double>) = 0.5 * sin(2 * PI / 5) * r.indices.sumOf { r[it] * r[(it + 1) % r.size] }
    return Pentagon(values[0], values[1], values[2], values[3], values[4], radii, reference, area(radii) / area(reference))
}
