package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.compose.theme.AapsTone

/** [level] is NaN when the pump has no reading to show. Stale values keep their severity and are dimmed. */
internal fun reservoirSupply(level: Double, stale: Boolean, critical: Double, warning: Double, format: (Double) -> String): HomeUiState.Supply =
    when {
        level.isNaN() -> HomeUiState.Supply("Reservoir", "—", AapsTone.Neutral)
        level <= 0.0  -> HomeUiState.Supply("Reservoir", "Empty", AapsTone.Low, stale = stale)
        else          -> HomeUiState.Supply(
            "Reservoir", format(level),
            when {
                level <= critical -> AapsTone.Low
                level <= warning  -> AapsTone.High
                else              -> AapsTone.InRange
            },
            stale = stale
        )
    }

/** A 0% reading is shown as unavailable rather than as a flat battery. */
internal fun batterySupply(percent: Int, stale: Boolean): HomeUiState.Supply =
    if (percent > 0) HomeUiState.Supply("Battery", "$percent%", if (percent < 25) AapsTone.Low else AapsTone.InRange, stale = stale)
    else HomeUiState.Supply("Battery", "—", AapsTone.InRange)
