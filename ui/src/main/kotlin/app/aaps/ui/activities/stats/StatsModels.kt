package app.aaps.ui.activities.stats

import androidx.compose.runtime.Immutable

/**
 * One time bucket's range split — an hour of the day, or a day of the week.
 *
 * Percentages are of that bucket's own readings, so every bar sums to 100 and buckets are comparable
 * regardless of how many readings each holds. [readings] is kept so a bucket with too little data can
 * be shown as unreliable rather than as a confident result.
 */
@Immutable
data class RangeBucket(
    val label: String,
    val veryLow: Double = 0.0,
    val low: Double = 0.0,
    val inRange: Double = 0.0,
    val high: Double = 0.0,
    val veryHigh: Double = 0.0,
    val readings: Int = 0
) {

    /** Below this there is not enough in the bucket to read a pattern off it. */
    val sparse: Boolean get() = readings < 12
}

/** Presentation state for the redesigned Statistics screen. Percentages are 0..100. */
@Immutable
data class StatsUiState(
    val loading: Boolean = true,
    val rangeDays: Int = 7,
    // Time-in-range 5-band split (percent of readings)
    val veryLow: Double = 0.0,
    val low: Double = 0.0,
    val inRange: Double = 0.0,
    val high: Double = 0.0,
    val veryHigh: Double = 0.0,
    // Tiles
    val gmi: String = "--",
    val avgGlucose: String = "--",
    val avgGlucoseUnit: String = "",
    val cv: String = "--",
    val cvGood: Boolean = true,
    val avgTdd: String = "--",
    val carbsPerDay: String = "--",
    // Pattern views — the aggregation a clinic summary leads with. 24 hourly buckets, 7 weekday
    // buckets, each split across the same five bands as the headline bar above.
    val byHour: List<RangeBucket> = emptyList(),
    val byWeekday: List<RangeBucket> = emptyList()
)
