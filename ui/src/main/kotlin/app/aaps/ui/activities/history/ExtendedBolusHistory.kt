package app.aaps.ui.activities.history

import app.aaps.core.data.model.EB
import app.aaps.core.data.ue.ValueWithUnit
import java.math.BigDecimal
import java.math.RoundingMode

/** Show the persisted amount, duration and rate as recorded; a running record is listed but not removable. */
internal fun EB.toHistoryItem(from: Long, now: Long, dayLabel: String, time: String): HistoryItem? {
    if (!isValid || timestamp !in from..now) return null

    val running = end > now
    val rateText = BigDecimal.valueOf(rate).setScale(2, RoundingMode.HALF_UP).toPlainString()
    val sub = "${durationText(duration)} · $rateText U/h" +
        (if (isEmulatingTempBasal) " · Emulated temporary basal" else "") +
        (if (running) RUNNING_SUFFIX else "")
    return HistoryItem(
        id, timestamp, dayLabel, time, HistoryKind.EXTENDED,
        "Extended bolus", sub,
        BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toPlainString() + " U",
        // Same audit values original AAPS logged when removing an extended bolus.
        auditValues = listOf(
            ValueWithUnit.Timestamp(timestamp),
            ValueWithUnit.Insulin(amount),
            ValueWithUnit.UnitPerHour(rate),
            ValueWithUnit.Minute((duration / 60_000).toInt())
        ),
        removable = !running,
        runningUntil = if (running) end else null
    )
}
