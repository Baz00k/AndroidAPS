package app.aaps.plugins.main.general.overview

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.profile.ProfileUtil

/** Display-only snapshot. Values used for comparisons stay in mg/dL, independent of display units. */
internal data class TargetDisplay(val low: Double?, val high: Double?, val temporaryTarget: TT?) {

    fun range(units: GlucoseUnit, profileUtil: ProfileUtil): String =
        if (low != null && high != null)
            "${profileUtil.toTargetRangeString(low, high, GlucoseUnit.MGDL, units)} ${if (units == GlucoseUnit.MMOL) "mmol/L" else "mg/dL"}"
        else ""

    fun stateLine(bgMgdl: Double?, units: GlucoseUnit, profileUtil: ProfileUtil): String = when {
        bgMgdl == null || low == null || high == null -> ""
        bgMgdl > high -> "${profileUtil.fromMgdlToStringInUnits(bgMgdl - high, units)} above target"
        bgMgdl < low -> "${profileUtil.fromMgdlToStringInUnits(low - bgMgdl, units)} below target"
        else -> "In target range"
    }

    companion object {
        fun at(now: Long, temporaryTarget: TT?, profileLow: Double?, profileHigh: Double?): TargetDisplay {
            val active = temporaryTarget?.takeIf { it.isValid && it.timestamp <= now && now < it.end }
            return TargetDisplay(active?.lowTarget ?: profileLow, active?.highTarget ?: profileHigh, active)
        }
    }
}
