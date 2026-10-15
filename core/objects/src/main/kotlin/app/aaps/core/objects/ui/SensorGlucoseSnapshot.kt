package app.aaps.core.objects.ui

import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.db.PersistenceLayer

/** One canonical sensor series for the headline and chart, independent of loop input. */
data class SensorGlucoseSnapshot(val readings: List<GV>, val glucose: SensorGlucose) {
    companion object {
        fun load(persistenceLayer: PersistenceLayer, from: Long, now: Long): SensorGlucoseSnapshot {
            val readings = SensorGlucose.sensorReadings(persistenceLayer.getBgReadingsDataFromTimeToTime(from, now, true), now)
            if (readings.isNotEmpty()) return SensorGlucoseSnapshot(readings, SensorGlucose.from(readings, now))

            // Keep an old reading visible, but never scan all history for a display fallback.
            // The same validation below rejects an invalid or future-dated last record.
            val older = persistenceLayer.getLastGlucoseValue()
            return SensorGlucoseSnapshot(emptyList(), SensorGlucose.from(listOfNotNull(older), now))
        }
    }
}
