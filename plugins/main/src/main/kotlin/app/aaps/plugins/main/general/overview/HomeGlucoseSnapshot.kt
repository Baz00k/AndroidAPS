package app.aaps.plugins.main.general.overview

import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.plugins.main.general.overview.compose.HomeGlucose

/** One canonical sensor series for the headline and chart, independent of loop input. */
data class HomeGlucoseSnapshot(val readings: List<GV>, val glucose: HomeGlucose) {
    companion object {
        fun load(persistenceLayer: PersistenceLayer, from: Long, now: Long): HomeGlucoseSnapshot {
            val readings = HomeGlucose.sensorReadings(persistenceLayer.getBgReadingsDataFromTimeToTime(from, now, true), now)
            if (readings.isNotEmpty()) return HomeGlucoseSnapshot(readings, HomeGlucose.from(readings, now))

            // A single unrestricted "last" record could be future-dated or a sensor error.
            // Search older history only when the chart has no usable samples; keep it off the chart.
            val older = persistenceLayer.getBgReadingsDataFromTimeToTime(0, from - 1, false)
            return HomeGlucoseSnapshot(emptyList(), HomeGlucose.from(older, now))
        }
    }
}
