package app.aaps.ui.activities.stats

import app.aaps.core.interfaces.utils.MidnightTime
import java.time.Instant
import java.time.ZoneId

/** Retain the established offset choice at repeated midnights. */
internal class StatsWindow(val now: Long, val days: Int, val zone: ZoneId = ZoneId.systemDefault()) {
    val rollingFrom = now - days * 86_400_000L
    val tirFrom = MidnightTime.calcDaysBack(now, days.toLong())
    val tirTo = MidnightTime.calc(now)
    private val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    // The shared calculator derives its own end from its start. If midnight is skipped or
    // repeated, splitting that request can change cache keys and even include an unfinished day.
    val canSplitTdd = (0..days).all { day ->
        zone.rules.getValidOffsets(today.minusDays(day.toLong()).atStartOfDay()).size == 1
    }

    fun tddEnd(day: Int): Long = today.minusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
}
