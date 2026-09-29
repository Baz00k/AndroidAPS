package app.aaps.plugins.main.general.overview.compose

import app.aaps.plugins.main.R

/** Stable names, never enum ordinals, are persisted. New overlays must be opt-in. */
enum class GlucoseOverlay(val defaultVisible: Boolean) {
    TARGET(false), BASAL(true), TREATMENTS(true), RAW_READINGS(true)
}

data class HomeGraphSettings(
    val overlays: Map<GlucoseOverlay, Boolean> = emptyMap(),
    val forecasts: Set<PredictionKind> = emptySet()
) {
    fun visible(overlay: GlucoseOverlay): Boolean = overlays[overlay] ?: overlay.defaultVisible
    fun withOverlay(overlay: GlucoseOverlay, visible: Boolean) = copy(overlays = overlays + (overlay to visible))
    fun withForecast(kind: PredictionKind, visible: Boolean) = copy(forecasts = if (visible) forecasts + kind else forecasts - kind)
    fun encode(): String = (GlucoseOverlay.entries.map { "${it.name}=${visible(it)}" } +
        PredictionKind.entries.map { "PREDICTION_${it.name}=${it in forecasts}" }).joinToString(",")

    companion object {
        fun decode(value: String): HomeGraphSettings {
            val entries = value.split(',').mapNotNull {
                val parts = it.split('=')
                if (parts.size != 2) null else parts[1].toBooleanStrictOrNull()?.let { visible -> parts[0] to visible }
            }.toMap()
            return HomeGraphSettings(
                GlucoseOverlay.entries.mapNotNull { overlay -> entries[overlay.name]?.let { overlay to it } }.toMap(),
                PredictionKind.entries.filter { entries["PREDICTION_${it.name}"] == true }.toSet()
            )
        }
    }
}

/** Filter visible series without changing the fixed viewport. */
internal fun HomeChartData.forDisplay(settings: HomeGraphSettings): HomeChartData = copy(
    predictions = predictions.filter { it.kind in settings.forecasts },
    targets = if (settings.visible(GlucoseOverlay.TARGET)) targets else emptyList(),
    basal = if (settings.visible(GlucoseOverlay.BASAL)) basal else emptyList(),
    treatments = if (settings.visible(GlucoseOverlay.TREATMENTS)) treatments else emptyList()
)

/** Scale enabled series over the full snapshot to keep vertical bounds stable while panning. */
internal fun HomeChartData.glucoseBounds(): Pair<Double, Double> {
    val values = (readings + trace + targets + predictions.flatMap { it.points }).map { it.value }.filter { it.isFinite() }
    val high = maxOf(highMark + 2.0, kotlin.math.ceil((values.maxOrNull() ?: highMark) + 0.5))
    val low = minOf(lowMark - 1.0, (values.minOrNull() ?: lowMark) - 0.5).coerceAtLeast(0.0)
    return low to high
}

internal fun GlucoseOverlay.labelResource(): Int = when (this) {
    GlucoseOverlay.TARGET -> R.string.overview_graph_target
    GlucoseOverlay.BASAL -> R.string.overview_show_basals
    GlucoseOverlay.TREATMENTS -> R.string.overview_show_treatments
    GlucoseOverlay.RAW_READINGS -> R.string.overview_graph_raw_readings
}
