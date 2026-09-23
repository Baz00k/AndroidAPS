package app.aaps.pump.ypsopump.tbr

import kotlin.math.roundToInt

/** A START_STOP_TBR request that the pump accepts over BLE (see docs/protocol.md). */
data class YpsoTbrRequest(val percent: Int, val durationMinutes: Int) {
    init {
        require(percent in 0..MAX_PERCENT) { "TBR percent $percent is outside 0–$MAX_PERCENT" }
        require(durationMinutes in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES) {
            "TBR duration $durationMinutes min is outside $MIN_DURATION_MINUTES–$MAX_DURATION_MINUTES"
        }
        require(percent != STOP_PERCENT) { "100% is the scheduled basal, not a TBR" }
    }

    companion object {
        const val MAX_PERCENT = 500
        const val MIN_DURATION_MINUTES = 15
        const val MAX_DURATION_MINUTES = 24 * 60
        const val STOP_PERCENT = 100
        const val STOP_DURATION_MINUTES = 0

        /**
         * Percent of the scheduled rate that represents [absoluteRate]. AAPS records percent TBRs
         * relative to the profile rate at each instant, so one conversion at enactment stays faithful
         * to what the pump delivers even across a later scheduled-rate change.
         */
        fun percentFor(absoluteRate: Double, scheduledRate: Double): Int {
            require(absoluteRate.isFinite() && absoluteRate >= 0.0) { "TBR rate $absoluteRate U/h is invalid" }
            require(scheduledRate.isFinite() && scheduledRate >= 0.0) { "scheduled basal $scheduledRate U/h is invalid" }
            if (absoluteRate == 0.0) return 0
            require(scheduledRate > 0.0) { "a positive TBR rate cannot be expressed against a 0 U/h scheduled basal" }
            return (absoluteRate / scheduledRate * 100.0).roundToInt().coerceAtMost(MAX_PERCENT)
        }
    }
}

/**
 * What the pump reported for one TBR write. A numeric code is only a candidate explanation: the
 * controller still requires a following status read before it treats any write as effective or not.
 */
sealed interface YpsoTbrWriteResult {
    /** Nothing left the phone. */
    data class NotSent(val detail: String) : YpsoTbrWriteResult

    /** The pump answered the final frame with a measured rejection code. */
    data class Rejected(val reason: YpsoTbrRejectReason) : YpsoTbrWriteResult

    /** Every frame was acknowledged. */
    data object Acknowledged : YpsoTbrWriteResult

    /** The command may have reached the pump. */
    data class Uncertain(val detail: String) : YpsoTbrWriteResult
}

/** Final-frame codes measured on V05.00.52; each left status and history unchanged. */
enum class YpsoTbrRejectReason(val code: Int) {
    PARAMETER_OUT_OF_RANGE(130),
    TBR_ALREADY_ACTIVE(134),
    PUMP_STOPPED(135);

    companion object {
        fun fromFinalFrameCode(code: Int?): YpsoTbrRejectReason? = entries.firstOrNull { it.code == code }
    }
}

/** Pump-reported TBR state from one fresh system status read. */
data class YpsoTbrObservation(
    /** False while the pump is in Stop mode. */
    val running: Boolean,
    val percent: Int,
    val remainingMinutes: Int,
    val observedAt: Long,
) {
    val active: Boolean get() = running && percent != YpsoTbrRequest.STOP_PERCENT && remainingMinutes > 0

    val idle: Boolean get() = running && percent == YpsoTbrRequest.STOP_PERCENT && remainingMinutes == 0

    /** A TBR started moments ago reads its request, with remaining minutes at most one below it. */
    fun justStarted(request: YpsoTbrRequest): Boolean =
        running && percent == request.percent &&
            remainingMinutes in (request.durationMinutes - 1)..request.durationMinutes
}
