package app.aaps.plugins.main.general.overview.compose

import app.aaps.plugins.main.R

internal fun GlucoseOverlay.labelResource(): Int = when (this) {
    GlucoseOverlay.TARGET -> R.string.overview_graph_target
    GlucoseOverlay.BASAL -> R.string.overview_show_basals
    GlucoseOverlay.TREATMENTS -> R.string.overview_show_treatments
    GlucoseOverlay.RAW_READINGS -> R.string.overview_graph_raw_readings
}
