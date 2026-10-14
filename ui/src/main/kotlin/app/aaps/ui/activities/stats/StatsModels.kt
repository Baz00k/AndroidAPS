package app.aaps.ui.activities.stats

import androidx.compose.runtime.Immutable
import java.time.LocalDate

/**
 * One hour of the day, summarised across every day in the range.
 *
 * Percentiles rather than mean and SD: glucose is skewed (a single 20 mmol excursion drags a mean far
 * more than it shifts the typical day), and median + IQR is what a clinic report shows, so this can be
 * read side by side with one. All values are in display units.
 */
@Immutable
data class HourProfile(
    val hour: Int,
    val p10: Double = 0.0,
    val p25: Double = 0.0,
    val median: Double = 0.0,
    val p75: Double = 0.0,
    val p90: Double = 0.0,
    val readings: Int = 0,
    /** Distinct days behind this hour — the sample depth, not the reading count. */
    val days: Int = 0
)

/** Presentation state for the redesigned Statistics screen. Percentages are 0..100. */
@Immutable
data class StatsUiState(
    val loading: Boolean = true,
    val rangeDays: Int = 7,
    val failed: Boolean = false,
    /** A refresh after a history change failed; the figures shown predate that change. */
    val refreshFailed: Boolean = false,
    val insulinLoading: Boolean = false,
    val insulinFailed: Boolean = false,
    val insulinDays: Int = 0,
    val tirReadings: Int = 0,
    val glucoseReadings: Int = 0,
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
    val cvGood: Boolean = false,
    val avgTdd: String = "--",
    val carbsPerDay: String = "--",
    // Hour-of-day profile. A stacked in-range bar per hour came first and was replaced: "all green"
    // cannot tell 4.5 from 9.8, and those are very different hours. A day-of-week view was also tried
    // and dropped — at short ranges each bar is one or two days, so a single bad Tuesday read as
    // "Tuesdays run high".
    val hourly: List<HourProfile> = emptyList(),
    // Needed by the profile chart for its target band and y-axis.
    val lowMark: Double = 4.0,
    val highMark: Double = 10.0,
    val decimals: Int = 1,
    // Completed-day reports. Formatted values are in display units.
    val completed: CompletedGlucose? = null,
    val pentagon: Pentagon? = null,
    val completedMean: String = "--",
    val sd: String = "--",
    val ea1c: String = "--",
    // Newest first; a day without a record is kept, so a gap is visible rather than averaged over.
    val insulinRows: List<InsulinDay> = emptyList(),
    val insulinAverage: InsulinDay? = null,
    val insulinToday: InsulinDay? = null
)

/** One day's insulin in units and carbs in grams. [recorded] is false for a completed day without a total. */
@Immutable
data class InsulinDay(
    val date: LocalDate,
    val basal: Double = 0.0,
    val bolus: Double = 0.0,
    val total: Double = 0.0,
    val carbs: Double = 0.0,
    val recorded: Boolean = true
)

/**
 * The on-demand 28-day insulin pattern. [availableDays] counts days with a total in the loaded history;
 * [firstDay] is day 1 of the newest cycle for the chosen offset, set once history is loaded, even when
 * that alignment has too few totals for a [pattern].
 */
@Immutable
data class CycleUiState(
    val requested: Boolean = false,
    val loading: Boolean = false,
    val loadedDays: Int = 0,
    val failed: Boolean = false,
    val offset: Int = 0,
    val availableDays: Int = 0,
    val firstDay: LocalDate? = null,
    val pattern: CyclePattern? = null
)

enum class Recalculation { IDLE, RUNNING, FAILED }
