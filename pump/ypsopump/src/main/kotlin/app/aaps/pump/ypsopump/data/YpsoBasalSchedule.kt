package app.aaps.pump.ypsopump.data

import app.aaps.pump.ypsopump.comm.YpsoGlb

/** Immutable pump-local hourly schedule; all values are measured centi-units/hour. */
internal class YpsoBasalSchedule private constructor(private val hourly: List<Int>) {
    enum class Program(val wireValue: Int, val firstSetting: Int) {
        A(3, 14), B(10, 38);

        companion object {
            fun decode(body: ByteArray): Program? = entries.singleOrNull { it.wireValue == YpsoGlb.decodeExact(body) }
        }
    }

    data class EffectiveSegment(val secondsFromMidnight: Int, val unitsPerHour: Double)

    fun rateAt(secondsFromMidnight: Int): Double {
        require(secondsFromMidnight in 0 until 86_400)
        return hourly[secondsFromMidnight / 3_600] / 100.0
    }

    /**
     * Input must already include AAPS percentage and time shift (Profile.getBasalValues()).
     * Compare every interval, including sub-hour transitions; never round a mismatch into a match.
     */
    fun matches(effective: List<EffectiveSegment>): Boolean {
        if (effective.isEmpty() || effective.first().secondsFromMidnight != 0) return false
        if (effective.any { it.secondsFromMidnight !in 0 until 86_400 || !it.unitsPerHour.isFinite() || it.unitsPerHour < 0 }) return false
        if (effective.zipWithNext().any { (a, b) -> a.secondsFromMidnight >= b.secondsFromMidnight }) return false
        return effective.indices.all { i ->
            val segment = effective[i]
            val end = effective.getOrNull(i + 1)?.secondsFromMidnight ?: 86_400
            val firstHour = segment.secondsFromMidnight / 3_600
            val lastHour = (end - 1) / 3_600
            (firstHour..lastHour).all {
                val measured = hourly[it] / 100.0
                // Account only for binary floating-point arithmetic in effective AAPS values.
                // This is many orders below a pump step and cannot round a clinical mismatch away.
                kotlin.math.abs(segment.unitsPerHour - measured) <= 4 * Math.ulp(measured)
            }
        }
    }

    companion object {
        /** No sentinel, CRC-search or partially populated schedule may become profile evidence. */
        fun decode(hourlyBodies: List<ByteArray>): YpsoBasalSchedule? {
            if (hourlyBodies.size != 24) return null
            val values = hourlyBodies.map { YpsoGlb.decodeExact(it) ?: return null }
            if (values.any { it !in 0..4_000 }) return null
            return YpsoBasalSchedule(values.toList())
        }
    }
}
