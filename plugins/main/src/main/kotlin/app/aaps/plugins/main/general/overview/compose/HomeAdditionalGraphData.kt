package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensResult

/** Stable names are persisted; enum ordinals must never become preference values. */
enum class AdditionalSeries(val defaultGraph: Int) {
    IOB(0), COB(0), SENSITIVITY(0), DEVIATIONS(0), BGI(0)
}

data class AdditionalGraphSettings(val assignments: Map<AdditionalSeries, Int>) {
    fun graph(series: AdditionalSeries): Int = assignments[series] ?: series.defaultGraph
    fun withGraph(series: AdditionalSeries, graph: Int) =
        AdditionalGraphSettings(assignments + (series to graph.coerceIn(0, 4)))

    fun encode(): String = AdditionalSeries.entries.joinToString(",") { "${it.name}=${graph(it)}" }

    companion object {
        fun decode(value: String): AdditionalGraphSettings {
            val values = value.split(',').mapNotNull { entry ->
                val parts = entry.split('=')
                val series = AdditionalSeries.entries.find { it.name == parts.firstOrNull() }
                val graph = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size == 2 && series != null && graph != null && graph in 0..4) series to graph else null
            }.toMap()
            return AdditionalGraphSettings(values)
        }
    }
}

/** Display-only snapshots. No prediction, carry-forward, or replacement of missing data with zero. */
data class AdditionalGraphData(val points: Map<AdditionalSeries, List<GlucosePoint>> = emptyMap()) {
    companion object {
        private val unavailableSensitivity = AutosensResult().sensResult

        fun fromAutosens(samples: List<AutosensData>, from: Long, to: Long, now: Long, fromMgdl: (Double) -> Double): AdditionalGraphData {
            val history = samples.filter { it.time in from..minOf(to, now) }.sortedBy { it.time }
            fun points(value: (AutosensData) -> Double) = history.mapNotNull {
                val v = value(it)
                if (v.isFinite()) GlucosePoint(it.time, v) else null
            }
            return AdditionalGraphData(
                mapOf(
                    AdditionalSeries.COB to points { it.cob },
                    AdditionalSeries.SENSITIVITY to points {
                        // The algorithm uses ratio=1 as its unavailable fallback too. It is not a
                        // measured 0% change. Preserve computed neutral results and genuine jumps;
                        // missing results remain gaps, never smooth or carry them forward.
                        val result = it.autosensResult
                        if (result.sensResult == unavailableSensitivity) Double.NaN
                        else 100.0 * (result.ratio - 1.0)
                    },
                    AdditionalSeries.DEVIATIONS to points { fromMgdl(it.deviation) },
                    // AAPS additional graphs show insulin lowering impact as positive. The stored
                    // loop BGI has the opposite sign; converting here never alters the loop value.
                    AdditionalSeries.BGI to points { fromMgdl(-it.bgi) }
                )
            )
        }
    }
}

/** Include zero and negative IOB/deviations. Flat or empty series must still have a usable axis. */
internal fun additionalGraphBounds(points: List<GlucosePoint>, minimumSpan: Double): Pair<Double, Double> {
    val values = points.map { it.value }.filter { it.isFinite() }
    val low = minOf(0.0, values.minOrNull() ?: 0.0)
    val high = maxOf(0.0, values.maxOrNull() ?: 0.0)
    val padding = maxOf((high - low) * 0.1, minimumSpan / 2)
    return (low - padding) to (high + padding)
}

internal const val ADDITIONAL_GRAPH_GAP_MS = 6 * 60_000L
