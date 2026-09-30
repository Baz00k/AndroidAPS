package app.aaps.plugins.insulin.compose

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.insulin.Insulin
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Chart data only: sampling a synthetic bolus does not record or deliver insulin. */
data class InsulinActivityCurve(
    val durationMinutes: Double,
    val points: List<InsulinActivityPoint>,
    val peak: InsulinActivityPoint,
    val axisMaxPercentPerHour: Double
)

data class InsulinActivityPoint(val minutes: Double, val percentPerHour: Double)

fun buildInsulinActivityCurve(insulin: Insulin, diaHours: Double, peakMinutes: Int): InsulinActivityCurve? {
    val durationMinutes = diaHours * 60.0
    val durationMillis = diaHours * 3_600_000.0
    // Do not silently clamp a peak or draw an invented curve for an invalid oref configuration.
    if (!durationMillis.isFinite() || durationMillis >= Long.MAX_VALUE.toDouble() ||
        durationMinutes < 30.0 || peakMinutes <= 0 || peakMinutes >= durationMinutes / 2.0
    ) return null

    val peakMillis = peakMinutes * 60_000L
    val times = ((0..80).map { (durationMillis * it / 80.0).roundToLong() } + peakMillis).distinct().sorted()
    val bolus = BS(timestamp = 0, amount = 1.0, type = BS.Type.NORMAL)
    val points = times.map { time ->
        // The insulin model returns U/min. For a 1 U sample, multiply by 100 and 60 for %/hour.
        val percentPerHour = insulin.iobCalcForTreatment(bolus, time, diaHours).activityContrib * 100.0 * 60.0
        if (!percentPerHour.isFinite() || percentPerHour < 0.0) return null
        InsulinActivityPoint(time / 60_000.0, percentPerHour)
    }
    val maximum = points.maxOf { it.percentPerHour }
    if (maximum <= 0.0) return null
    val axisMaximum = ceil(maximum / 10.0) * 10.0
    if (!axisMaximum.isFinite()) return null
    return InsulinActivityCurve(
        durationMinutes = durationMinutes,
        points = points,
        peak = points[times.indexOf(peakMillis)],
        axisMaxPercentPerHour = axisMaximum
    )
}
