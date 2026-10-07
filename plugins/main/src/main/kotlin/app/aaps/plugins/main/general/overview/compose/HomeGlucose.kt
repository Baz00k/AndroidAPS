package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.TrendArrow
import kotlin.math.abs

/** Sensor-only presentation data. Never published to the calculator's dosing-input store. */
data class HomeGlucose(
    val reading: GV? = null,
    val deltaMgdl: Double? = null,
    val trend: TrendArrow? = null
) {
    fun isFresh(now: Long): Boolean = reading?.timestamp?.let { it <= now && it > now - 9 * 60_000L } == true

    companion object {
        /** Prefer the later-inserted database record when sensor sources share a timestamp. */
        fun sensorReadings(readings: List<GV>, now: Long): List<GV> = readings
            .filter { it.isValid && it.value.isFinite() && it.value >= 39 && it.timestamp in 0..now }
            .sortedWith(compareByDescending<GV> { it.timestamp }.thenByDescending { it.id })
            .distinctBy { it.timestamp }
            .reversed()

        fun from(readings: List<GV>, now: Long): HomeGlucose {
            // The database's glucose-history query excludes sensor error values below 39 mg/dL.
            val samples = sensorReadings(readings, now).asReversed()
            val latest = samples.firstOrNull() ?: return HomeGlucose()
            // Keep delta in mg/dL per five minutes regardless of the sensor's reporting interval.
            // Use an actual sample nearest five minutes ago; do not bridge a large gap or invent zero.
            val previous = samples.drop(1)
                .filter { latest.timestamp - it.timestamp in 150_000L..450_000L }
                .minByOrNull { abs(latest.timestamp - it.timestamp - 300_000L) }
            val delta = previous?.let { (latest.value - it.value) * 300_000.0 / (latest.timestamp - it.timestamp) }
            val trend = latest.trendArrow.takeUnless { it == TrendArrow.NONE } ?: delta?.let {
                when {
                    it <= -17.5 -> TrendArrow.DOUBLE_DOWN
                    it <= -10.0 -> TrendArrow.SINGLE_DOWN
                    it <= -5.0  -> TrendArrow.FORTY_FIVE_DOWN
                    it <= 5.0   -> TrendArrow.FLAT
                    it <= 10.0  -> TrendArrow.FORTY_FIVE_UP
                    it <= 17.5  -> TrendArrow.SINGLE_UP
                    else        -> TrendArrow.DOUBLE_UP
                }
            }
            return HomeGlucose(latest, delta, trend)
        }
    }
}
