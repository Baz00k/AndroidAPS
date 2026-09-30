package app.aaps.core.compose.components

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Entry times are minutes from now: negative in the past, positive in the future. These are the
 * rules every entry screen shares for showing and picking them.
 */
object EntryTime {

    /** "now", "in 15 min", "20 min ago", "1 h 20 min ago". */
    fun relative(offsetMin: Int): String {
        if (offsetMin == 0) return "now"
        val span = duration(abs(offsetMin))
        return if (offsetMin > 0) "in $span" else "$span ago"
    }

    /** "+15 min", "−30 min", "Now": the compact form used on quick choices. */
    fun signed(offsetMin: Int): String = when {
        offsetMin == 0 -> "Now"
        offsetMin > 0  -> "+${duration(offsetMin)}"
        else           -> "−${duration(-offsetMin)}"
    }

    private fun duration(minutes: Int): String = when {
        minutes < 60      -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else              -> "${minutes / 60} h ${minutes % 60} min"
    }

    /**
     * A clock time picked for an entry, as the occurrence (yesterday, today or tomorrow) nearest to
     * [now] that lies within [minOffsetMin]..[maxOffsetMin]; held at the nearer limit when none does.
     */
    fun offsetFor(now: ZonedDateTime, picked: LocalTime, minOffsetMin: Int, maxOffsetMin: Int): Int {
        val today = now.with(picked).withSecond(0).withNano(0)
        val candidates = listOf(today.minusDays(1), today, today.plusDays(1))
            .map { Duration.between(now.withSecond(0).withNano(0), it).toMinutes().toInt() }
        return candidates.filter { it in minOffsetMin..maxOffsetMin }.minByOrNull { abs(it) }
            // None fits: the occurrence closest to the window, not to now, so a time too far back is
            // held at the earliest allowed moment rather than turning into "now".
            ?: candidates.minBy { abs(it - it.coerceIn(minOffsetMin, maxOffsetMin)) }.coerceIn(minOffsetMin, maxOffsetMin)
    }
}
