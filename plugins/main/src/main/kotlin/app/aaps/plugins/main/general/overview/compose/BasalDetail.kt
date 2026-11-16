package app.aaps.plugins.main.general.overview.compose

import androidx.compose.runtime.Immutable
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.TB
import kotlin.math.roundToInt

/** The BASAL sheet: the profile rate now and what runs over it. A null [scheduled] means no profile. */
@Immutable
data class BasalDetail(
    val scheduled: String? = null,
    val tempBasal: Running? = null,
    val extendedBolus: Running? = null
) {

    @Immutable
    data class Running(val rate: String, val started: String, val remaining: String)
}

/**
 * [tempBasal] is the temp basal active at [now], including one a pump fakes with an extended bolus;
 * [extendedBolus] is a real extended bolus active at [now]. A percent temp basal without a profile
 * rate shows its percent only: its absolute rate is unknown.
 */
internal fun basalDetail(
    now: Long,
    scheduledRate: Double?,
    tempBasal: TB?,
    extendedBolus: EB?,
    timeString: (Long) -> String,
    rateString: (Double) -> String
): BasalDetail {
    val temp = tempBasal?.takeIf { it.isValid && now in it.timestamp until it.end }?.let { tb ->
        val absolute = if (tb.isAbsolute) tb.rate else scheduledRate?.let { it * tb.rate / 100 }
        val percent = if (!tb.isAbsolute) tb.rate.roundToInt() else scheduledRate?.takeIf { it > 0 }?.let { (tb.rate / it * 100).roundToInt() }
        val rate = listOfNotNull(absolute?.let(rateString), percent?.let { "$it%" }).joinToString(" · ")
        BasalDetail.Running(rate, timeString(tb.timestamp), remaining(now, tb.end, tb.duration))
    }
    val extended = extendedBolus?.takeIf { it.isValid && now in it.timestamp until it.end }?.let { eb ->
        BasalDetail.Running(rateString(eb.rate), timeString(eb.timestamp), remaining(now, eb.end, eb.duration))
    }
    return BasalDetail(scheduledRate?.let(rateString), temp, extended)
}

private fun remaining(now: Long, end: Long, durationMs: Long) =
    "${((end - now) / 60_000.0).roundToInt()} of ${(durationMs / 60_000.0).roundToInt()} min"
