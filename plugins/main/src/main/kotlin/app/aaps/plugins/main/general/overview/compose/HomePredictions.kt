package app.aaps.plugins.main.general.overview.compose

import androidx.compose.runtime.Immutable
import app.aaps.core.interfaces.aps.APSResult

@Immutable
enum class PredictionKind { IOB, COB, ZT, UAM, A_COB }

/** Projections stay separate from CGM readings and never become the current glucose value. */
@Immutable
data class ChartPrediction(val kind: PredictionKind, val points: List<GlucosePoint>)

/** Use the APS timestamps and five-minute cadence, without interpolating or extrapolating. */
internal fun buildHomePredictions(result: APSResult?, now: Long, toUnits: (Double) -> Double): List<ChartPrediction> {
    // A stopped loop must not leave an old forecast looking current. Also reject future-dated results.
    if (result == null || !result.hasPredictions || result.date <= 0L || now - result.date !in 0L..15 * 60_000L) return emptyList()
    val predictions = result.predictions() ?: return emptyList()
    return listOf(
        PredictionKind.IOB to predictions.IOB,
        PredictionKind.COB to predictions.COB,
        PredictionKind.ZT to predictions.ZT,
        PredictionKind.UAM to predictions.UAM,
        PredictionKind.A_COB to predictions.aCOB
    ).mapNotNull { (kind, values) ->
        val points = values?.mapIndexedNotNull { index, value ->
            val time = result.date + index * 5 * 60_000L
            // Index zero is the algorithm's starting glucose, not a future prediction.
            if (index == 0 || time <= now || value < 39) null
            else GlucosePoint(time, toUnits(value.toDouble()))
        }.orEmpty()
        points.takeIf { it.isNotEmpty() }?.let { ChartPrediction(kind, it) }
    }
}
