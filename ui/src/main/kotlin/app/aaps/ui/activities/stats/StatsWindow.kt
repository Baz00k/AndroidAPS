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

    /**
     * Whether the one-day TDD request ending at [tddEnd] of [day] covers exactly that completed day.
     *
     * The shared calculator derives its end from its start. If either midnight is skipped or repeated,
     * the interval shifts and can reach into today, whose partial total it would then cache for the
     * loop's own TDD to read later. Such a day is reported as missing instead of being requested.
     */
    fun safeDay(day: Int): Boolean = (day..day + 1).all { back ->
        zone.rules.getValidOffsets(today.minusDays(back.toLong()).atStartOfDay()).size == 1
    }

    fun tddEnd(day: Int): Long = today.minusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
}
