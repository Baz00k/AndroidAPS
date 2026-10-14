package app.aaps.ui.activities.stats

import androidx.compose.runtime.Immutable
import java.time.LocalDate

internal const val CYCLE_DAYS = 28
private const val CYCLES = 6
/** Six cycles, plus room to shift day 1 back by up to a whole cycle less one day. */
internal const val CYCLE_HISTORY_DAYS = CYCLE_DAYS * CYCLES + CYCLE_DAYS - 1
/** A cycle counts only with most of its days, so a few missing records do not drag its line or the average. */
private const val MIN_CYCLE_DAYS = 21

/**
 * Daily insulin totals overlaid in 28-day cycles, newest cycle first. Values are units per day; a
 * missing day stays null rather than shifting later days into the wrong cycle day.
 */
@Immutable
data class CyclePattern(val firstDay: LocalDate, val cycles: List<List<Double?>>, val average: List<Double?>)

/** [newest] is the last completed day; [offset] moves the end of the newest cycle that many days earlier. */
internal fun cyclePattern(totals: Map<LocalDate, Double>, newest: LocalDate, offset: Int): CyclePattern? {
    val end = cycleStart(newest, offset).plusDays(CYCLE_DAYS - 1L)
    val cycles = (0 until CYCLES)
        .map { cycle -> (0 until CYCLE_DAYS).map { day -> totals[end.minusDays((cycle * CYCLE_DAYS + CYCLE_DAYS - 1 - day).toLong())] } }
        .filter { cycle -> cycle.count { it != null } >= MIN_CYCLE_DAYS }
    if (cycles.size < 2) return null
    val average = (0 until CYCLE_DAYS).map { day -> cycles.mapNotNull { it[day] }.takeIf { it.isNotEmpty() }?.average() }
    return CyclePattern(end.minusDays(CYCLE_DAYS - 1L), cycles, average)
}

/** Day 1 of the newest cycle. */
internal fun cycleStart(newest: LocalDate, offset: Int): LocalDate =
    newest.minusDays(offset.coerceIn(0, CYCLE_DAYS - 1) + CYCLE_DAYS - 1L)
