package app.aaps.ui.activities.stats

import java.time.Instant
import java.time.ZoneId

/**
 * The [days] completed local days before the day of [now], as [from] until [to]. Every figure on the
 * screen uses this one window, so they describe the same days. Today is left out because it is not over.
 * Days start at their first local instant, so a skipped or repeated midnight still splits by calendar date.
 */
internal class StatsWindow(val now: Long, val days: Int, val zone: ZoneId = ZoneId.systemDefault()) {
    private val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val from: Long = today.minusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    val to: Long = today.atStartOfDay(zone).toInstant().toEpochMilli()
}
