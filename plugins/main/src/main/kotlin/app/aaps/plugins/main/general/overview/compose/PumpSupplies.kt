package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.compose.theme.AapsTone
import app.aaps.core.interfaces.pump.Pump

internal fun keyExpirySupply(key: Pump.KeyExpiryIndicator, title: String, value: String): HomeUiState.Supply =
    HomeUiState.Supply(title, value, when {
        key.remainingMs == null -> AapsTone.Neutral
        key.due -> AapsTone.Low
        key.reminderDue -> AapsTone.High
        else -> AapsTone.InRange
    })

/** [level] is NaN when the pump has no reading to show. */
internal fun reservoirSupply(level: Double, critical: Double, warning: Double, format: (Double) -> String): HomeUiState.Supply =
    when {
        level.isNaN() -> HomeUiState.Supply("Reservoir", "—", AapsTone.Neutral)
        level <= 0.0  -> HomeUiState.Supply("Reservoir", "Empty", AapsTone.Low)
        else          -> HomeUiState.Supply(
            "Reservoir", format(level),
            when {
                level <= critical -> AapsTone.Low
                level <= warning  -> AapsTone.High
                else              -> AapsTone.InRange
            }
        )
    }

/** A 0% reading is shown as unavailable rather than as a flat battery. */
internal fun batterySupply(percent: Int): HomeUiState.Supply =
    if (percent > 0) HomeUiState.Supply("Battery", "$percent%", if (percent < 25) AapsTone.Low else AapsTone.InRange)
    else HomeUiState.Supply("Battery", "—", AapsTone.InRange)
