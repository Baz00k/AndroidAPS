package app.aaps.plugins.aps.openAPS

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Temp basal set alongside an SMB to withhold [durationReq] minutes of [basal].
 * Returns the duration in minutes (0 for none) and the unrounded rate in U/h.
 */
fun smbLowTemp(durationReq: Int, basal: Double): Pair<Int, Double> = when {
    durationReq <= 0  -> 0 to 0.0
    // don't set an SMB zero temp longer than 60 minutes
    durationReq >= 30 -> min(60, (durationReq / 30.0).roundToInt() * 30) to 0.0
    // A temp lasts at least 30 minutes, so deliver the basal left after withholding durationReq of them.
    // This deliberately differs from oref0, whose basal * durationReq / 30 withholds the complement.
    else              -> 30 to basal * (30 - durationReq) / 30.0
}
