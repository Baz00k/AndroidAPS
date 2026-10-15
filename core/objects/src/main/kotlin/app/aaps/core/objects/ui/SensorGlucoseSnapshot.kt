package app.aaps.core.objects.ui

import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.db.PersistenceLayer

/** One canonical sensor series for the headline and chart, independent of loop input. */
data class SensorGlucoseSnapshot(val readings: List<GV>, val glucose: SensorGlucose) {
    companion object {
        fun load(persistenceLayer: PersistenceLayer, from: Long, now: Long): SensorGlucoseSnapshot {
            val readings = SensorGlucose.sensorReadings(persistenceLayer.getBgReadingsDataFromTimeToTime(from, now, true), now)
            if (readings.isNotEmpty()) return SensorGlucoseSnapshot(readings, SensorGlucose.from(readings, now))

            // A single unrestricted "last" record could be future-dated or a sensor error.
            // Fetch only the latest usable older record; keep stale fallback history off the chart.
            val older = persistenceLayer.getLastUsableGlucoseValueForDisplay(from - 1)
            return SensorGlucoseSnapshot(emptyList(), SensorGlucose.from(listOfNotNull(older), now))
        }
    }
}
