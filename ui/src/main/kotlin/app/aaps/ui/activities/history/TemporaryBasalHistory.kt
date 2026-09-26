package app.aaps.ui.activities.history

import app.aaps.core.data.model.TB
import java.math.BigDecimal

/** Use the persisted rate and duration, including early stops, without interpreting delivery or IOB. */
internal fun TB.toHistoryItem(from: Long, now: Long, dayLabel: String, time: String): HistoryItem? {
    if (!isValid || timestamp !in from..now || type == TB.Type.FAKE_EXTENDED) return null

    val minutes = duration / 60_000
    val seconds = duration % 60_000 / 1_000
    val durationText = if (seconds == 0L) "$minutes min" else "$minutes min $seconds s"
    val typeText = when (type) {
        TB.Type.NORMAL                -> ""
        TB.Type.PUMP_SUSPEND          -> " · Pump suspend"
        TB.Type.EMULATED_PUMP_SUSPEND -> " · Emulated pump suspend"
        TB.Type.SUPERBOLUS            -> " · Superbolus"
        TB.Type.FAKE_EXTENDED         -> return null
    }
    val rateText = BigDecimal.valueOf(rate).stripTrailingZeros().toPlainString()
    return HistoryItem(
        id, timestamp, dayLabel, time, HistoryKind.TBR,
        "Temporary basal", "Recorded duration: $durationText$typeText",
        if (isAbsolute) "$rateText U/h" else "$rateText%"
    )
}
