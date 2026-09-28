package app.aaps.plugins.main.general.overview.compose

import androidx.compose.runtime.Immutable

/** A CGM reading, already converted to the user's display units. */
@Immutable
data class GlucosePoint(val time: Long, val value: Double)

/**
 * Effective delivery rate (U/hr) from [time] until the next step, plus the profile's [scheduled] rate
 * at that moment. Sampled rather than taken record-by-record: temp basals overlap and supersede each
 * other, so drawing one step per record doubles back on itself. Scheduled basal is sampled with the
 * historical profile, so a profile switch shows up where it happened instead of today's value being
 * back-projected over the whole window.
 */
@Immutable
data class BasalStep(val time: Long, val rate: Double, val scheduled: Double = rate)

/** Basal is sampled on this epoch-aligned grid (below the 5-minute loop cadence). */
internal const val BASAL_SAMPLE_MS = 3 * 60_000L

/** A longer gap between basal samples means no profile was available: draw nothing, not a bridge. */
internal const val BASAL_GAP_MS = 2 * BASAL_SAMPLE_MS

/** A gap longer than this between CGM points is a sensor dropout, not a line. */
internal const val TRACE_GAP_MS = 20 * 60_000L

@Immutable
enum class TreatmentKind { BOLUS, SMB, CARBS }

/** A bolus or carb entry, drawn on the rail between the two panels. */
@Immutable
data class ChartTreatment(val time: Long, val amount: Double, val kind: TreatmentKind)

/**
 * Everything the home graphs draw. Built off the same sources the legacy GraphView pipeline used —
 * CGM readings from the persistence layer, `iobCobCalculator.getBasalData` for delivery, the
 * persistence layer for treatments — so this changes the rendering surface, not the data.
 *
 * This is a SAMPLE, not a viewport: it always covers [from]..[now] (the whole pan budget), and the
 * visible window is resolved at draw time from [ChartWindow] + [ChartPanState]. Nothing historical
 * is ever later than [now]; only forecasts are.
 *
 * Values are in display units; [lowMark] / [highMark] are the same Overview thresholds that colour the
 * hero BG, so the band and the big number can never disagree.
 */
@Immutable
data class HomeChartData(
    /** Oldest sample time loaded; also the earliest point the graphs can be panned to. */
    val from: Long = 0L,
    /** Build clock and historical cutoff: the "now" line, and where history stops. */
    val now: Long = 0L,
    /** Raw sensor readings — every one, drawn as a faint scatter. Sorted by time. */
    val readings: List<GlucosePoint> = emptyList(),
    /**
     * The 5-minute bucketed series — what the loop's own statistics are computed from. Drawn as
     * the trace so the line and the decisions cannot disagree. Empty on a 5-minute source, where
     * bucketing is a no-op and [readings] is already the right thing to draw. Sorted by time,
     * without interpolated gap fills.
     */
    val bucketed: List<GlucosePoint> = emptyList(),
    val predictions: List<ChartPrediction> = emptyList(),
    val basal: List<BasalStep> = emptyList(),
    val treatments: List<ChartTreatment> = emptyList(),
    val lowMark: Double = 4.0,
    val highMark: Double = 10.0,
    val decimals: Int = 1,
    /** Effective target midpoint, separate from the glucose warning thresholds. */
    val targets: List<GlucosePoint> = emptyList(),
    val glucoseUnits: String = "mmol/L",
    val additional: AdditionalGraphData = AdditionalGraphData()
) {

    val hasData: Boolean get() = readings.isNotEmpty() && now > from

    /**
     * The series the trace is drawn from: bucketed where it exists, raw readings elsewhere.
     *
     * Only meaningfully different on a dense (1-minute) source. Never a cosmetic filter — it is
     * the same averaging the loop uses, so a surprising decision can be read off the graph. The
     * bucketed table and the readings are refreshed by different workers, so either can cover a
     * slightly different span; raw readings fill whichever edge the bucketed series does not reach
     * (typically a reading that arrived after the last bucketing run), instead of the line stopping
     * short of the newest value.
     *
     * Computed once per snapshot (not per frame) because panning redraws continuously.
     */
    val trace: List<GlucosePoint> = mergeTrace(readings, bucketed)

    /** The newest measured value, at its real timestamp. Never a forecast, never moved to "now". */
    val latest: GlucosePoint? get() = trace.lastOrNull()

    /** True when raw and trace differ, i.e. there is a scatter worth drawing underneath. */
    val hasDenseScatter: Boolean = bucketed.size > 1 &&
        readings.count { it.time in bucketed.first().time..bucketed.last().time } > bucketed.size
}

/**
 * Top of the insulin panel: a round number just above the highest delivered or scheduled rate in the
 * loaded sample, fixed for the snapshot so the panel does not rescale while panning.
 */
internal fun insulinScaleMax(basal: List<BasalStep>): Double {
    val peak = basal.maxOfOrNull { maxOf(it.rate, it.scheduled) }?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    val step = when {
        peak <= 1.0 -> 0.25
        peak <= 4.0 -> 0.5
        else        -> 1.0
    }
    return (kotlin.math.ceil(peak * 1.1 / step) * step).coerceAtLeast(step)
}

internal fun mergeTrace(readings: List<GlucosePoint>, bucketed: List<GlucosePoint>): List<GlucosePoint> {
    if (bucketed.size <= 1) return readings
    val first = bucketed.first().time
    val last = bucketed.last().time
    return readings.filter { it.time < first } + bucketed + readings.filter { it.time > last }
}
