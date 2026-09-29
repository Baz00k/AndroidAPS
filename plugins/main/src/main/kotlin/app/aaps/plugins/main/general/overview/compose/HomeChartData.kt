package app.aaps.plugins.main.general.overview.compose

import androidx.compose.runtime.Immutable

/** A CGM reading, already converted to the user's display units. */
@Immutable
data class GlucosePoint(val time: Long, val value: Double)

/** Effective and scheduled basal rates (U/h), sampled using the historical profile. */
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
 * Display-unit snapshot of the full pan budget, independent of the viewport.
 * History ends at [now]; predictions and planned targets may extend beyond it.
 */
@Immutable
data class HomeChartData(
    /** Oldest sample time loaded; also the earliest point the graphs can be panned to. */
    val from: Long = 0L,
    /** Build clock and historical cutoff: the "now" line, and where history stops. */
    val now: Long = 0L,
    /** Raw sensor readings — every one, drawn as a faint scatter. Sorted by time. */
    val readings: List<GlucosePoint> = emptyList(),
    /** Loop-bucketed readings, sorted by time and excluding interpolated gap fills. */
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
     * Use bucketed history, with raw readings covering edges not yet processed by the worker.
     * Computed once per snapshot, not per frame.
     */
    val trace: List<GlucosePoint> = mergeTrace(readings, bucketed)

    /** The newest measured value, at its real timestamp. Never a forecast, never moved to "now". */
    val latest: GlucosePoint? get() = trace.lastOrNull()

    /** True when raw and trace differ, i.e. there is a scatter worth drawing underneath. */
    val hasDenseScatter: Boolean = bucketed.size > 1 &&
        readings.count { it.time in bucketed.first().time..bucketed.last().time } > bucketed.size
}

/** Round insulin-axis maximum, fixed for the loaded snapshot while panning. */
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
