package app.aaps.plugins.main.general.overview

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.plugins.main.general.overview.compose.GlucosePoint

internal const val TARGET_SAMPLE_INTERVAL_MS = 5 * 60_000L

/** Display-only target history. Call off the UI thread, alongside the other chart providers. */
internal class TargetChartData(
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil
) {
    fun build(from: Long, to: Long): List<GlucosePoint> {
        if (to <= from) return emptyList()

        // The range query includes starts, not overlaps: retain a target that began before `from`.
        // Resolve all five-minute samples in memory instead of issuing up to 289 target queries.
        val temporaryTargets = (listOfNotNull(persistenceLayer.getTemporaryTargetActiveAt(from)) +
            persistenceLayer.getTemporaryTargetDataFromTime(from, ascending = true).blockingGet())
            .filter { it.isValid && it.timestamp <= to }
            .sortedBy { it.timestamp }

        // A profile's timestamped getters select its time-of-day blocks, not a historical profile.
        // Re-resolve the effective profile only at switch boundaries, not for every graph sample.
        val switches = persistenceLayer.getEffectiveProfileSwitchesFromTimeToTime(from, to, ascending = true)
            .filter { it.isValid && it.timestamp > from && it.timestamp <= to }
            .map { it.timestamp }.distinct().sorted()
        var nextSwitch = 0
        var profile = profileFunction.getProfile(from)
        val units = profileUtil.units
        val targets = ArrayList<GlucosePoint>()
        var time = from
        while (true) {
            var latestSwitch: Long? = null
            while (nextSwitch < switches.size && switches[nextSwitch] <= time) {
                latestSwitch = switches[nextSwitch++]
            }
            latestSwitch?.let { profile = profileFunction.getProfile(it) }

            val temporaryTarget = temporaryTargets.lastOrNull { it.timestamp <= time && time < it.end }
            val target = TargetDisplay.at(
                time, temporaryTarget, profile?.getTargetLowMgdl(time), profile?.getTargetHighMgdl(time)
            )
            // Do not invent historical values using today's profile when history is unavailable.
            val low = target.low
            val high = target.high
            if (low != null && high != null)
                targets.add(GlucosePoint(time, profileUtil.fromMgdlToUnits((low + high) / 2, units)))
            if (time == to) break
            time = (time + TARGET_SAMPLE_INTERVAL_MS).coerceAtMost(to)
        }
        return targets
    }
}
