package app.aaps.core.objects.ui

import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.db.PersistenceLayer

/** One canonical sensor series for the headline and chart, independent of loop input. */
data class SensorGlucoseSnapshot(val readings: List<GV>, val glucose: SensorGlucose) {
    companion object {
        /** Enough history for the newest reading and the sample behind its five-minute delta. */
        const val LATEST_WINDOW_MS = 15 * 60_000L

        /**
         * The newest sensor reading and its delta, as the Overview headline shows them. Treatment entry points
         * read this, not the loop's last five-minute bucket, which can lag one-minute sensor data by minutes.
         */
        fun latest(persistenceLayer: PersistenceLayer, now: Long): SensorGlucose =
            load(persistenceLayer, now - LATEST_WINDOW_MS, now).glucose

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
