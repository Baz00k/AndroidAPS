package app.aaps.ui.activities.stats

import app.aaps.core.data.model.GV
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.sqrt

/** Glucose-only summary. TIR counts raw readings in [tirFrom, tirTo]; the average, CV and profile use 5-minute buckets in [rollingFrom, now]. */
internal data class StatsGlucose(
    val average: Double,
    val cv: Double,
    val bands: List<Double>,
    val tirReadings: Int,
    val readings: Int,
    val hourly: List<HourProfile>
)

internal fun summarizeGlucose(
    raw: List<GV>, rollingFrom: Long, now: Long, tirFrom: Long, tirTo: Long,
    low: Double, high: Double, convert: (Double) -> Double,
    timeZone: TimeZone = TimeZone.getDefault()
): StatsGlucose {
    require(low >= 39 && low <= high) { "Invalid glucose range" }
    // Preserve the first reading's timestamp and the arithmetic mean within each bucket.
    data class Bucket(val timestamp: Long, var sum: Double = 0.0, var count: Int = 0)
    val buckets = linkedMapOf<Long, Bucket>()
    var below = 0; var inRange = 0; var above = 0; var veryLow = 0; var veryHigh = 0; var count = 0
    for (reading in raw) {
        val value = reading.value
        if (!value.isFinite() || value < 39) continue
        if (reading.timestamp in tirFrom..tirTo) {
            count++
            when {
                value < low -> below++
                value <= high -> inRange++
                else -> above++
            }
            if (value < minOf(low, 54.0)) veryLow++
            if (value > maxOf(high, 250.0)) veryHigh++
        }
        if (reading.timestamp in rollingFrom..now) {
            val bucket = buckets.getOrPut(reading.timestamp / 300_000L) { Bucket(reading.timestamp) }
            bucket.sum += value
            bucket.count++
        }
    }
    val values = buckets.values.map { it.sum / it.count }
    val average = if (values.isEmpty()) 0.0 else values.average()
    val sd = if (values.size > 1) sqrt(values.sumOf { (it - average) * (it - average) } / values.size) else 0.0
    val perHour = Array(24) { mutableListOf<Double>() }
    val days = Array(24) { mutableSetOf<Int>() }
    val calendar = Calendar.getInstance(timeZone)
    buckets.values.forEach { bucket ->
        calendar.timeInMillis = bucket.timestamp
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        perHour[hour].add(convert(bucket.sum / bucket.count))
        days[hour].add(calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR))
    }
    val hourly = (0..23).map { hour ->
        val sorted = perHour[hour].sorted()
        fun percentile(q: Double): Double {
            val position = q * (sorted.size - 1)
            val lower = position.toInt()
            val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
            return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
        }
        if (sorted.isEmpty()) HourProfile(hour)
        else HourProfile(hour, percentile(.1), percentile(.25), percentile(.5), percentile(.75), percentile(.9), sorted.size, days[hour].size)
    }
    fun pct(value: Int) = if (count == 0) 0.0 else value * 100.0 / count
    return StatsGlucose(average, if (average > 0) sd / average * 100 else 0.0,
                        listOf(pct(veryLow), pct((below - veryLow).coerceAtLeast(0)), pct(inRange), pct((above - veryHigh).coerceAtLeast(0)), pct(veryHigh)),
                        count, values.size, hourly)
}
