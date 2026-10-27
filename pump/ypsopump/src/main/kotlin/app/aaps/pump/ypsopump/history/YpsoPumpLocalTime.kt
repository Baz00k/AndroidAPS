package app.aaps.pump.ypsopump.history

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Pump-local wall-clock seconds since 2000-01-01. The pump follows no zone: its clock is whatever the
 * user last set, and each set writes a date or time change row (see docs/history.md).
 */
object YpsoPumpLocalTime {
    private val FACTORY_EPOCH = LocalDateTime.of(2000, 1, 1, 0, 0)

    fun localDateTime(factorySeconds: Long): LocalDateTime {
        require(factorySeconds in 0..0xffffffffL) { "factorySeconds must be an unsigned 32-bit value" }
        return FACTORY_EPOCH.plusSeconds(factorySeconds)
    }

    /**
     * The pump's wall clock [pumpLocal] read as if it were UTC, minus the phone clock [phoneMs]: the
     * offset of whatever the pump clock was set to, plus its drift.
     */
    fun pumpOffsetMs(pumpLocal: LocalDateTime, phoneMs: Long): Long = pumpLocal.toInstant(ZoneOffset.UTC).toEpochMilli() - phoneMs

    /** Phone times of a read's rows, or why some have none. */
    class RowTimes(private val offsets: Map<Long, Long>, val invalidChange: YpsoHistoryEntry?) {
        /** When [entry] happened on the phone clock; null without a clock reading or behind an [invalidChange]. */
        fun at(entry: YpsoHistoryEntry): Long? =
            offsets[entry.sequence]?.let { localDateTime(entry.factorySeconds).toInstant(ZoneOffset.UTC).toEpochMilli() - it }
    }

    /**
     * Places every row of [snapshot] by its clock reading, chained back through the clock change rows
     * newer than the row: crossing one backwards undoes the step it made. A change row is stamped with
     * the clock it replaced, so it belongs to the segment before it. A change row whose setting is
     * impossible, or later than the clock the next change row of its sequence generation replaced, places
     * nothing at or before it; it is found also without a live reading.
     */
    fun rowTimes(snapshot: YpsoHistorySnapshot): RowTimes {
        val offsets = HashMap<Long, Long>()
        val live = snapshot.pumpClockOffsetMs
        var offset = live ?: 0L
        var replacedNext: LocalDateTime? = null
        var newerSequence = Long.MAX_VALUE
        for (row in snapshot.rowsNewestFirst) {
            // A sequence reset starts a new generation: its change rows follow no clock of the one before.
            if (row.sequence > newerSequence) replacedNext = null
            newerSequence = row.sequence
            YpsoHistoryClassifier.classify(row).takeIf { it.kind == YpsoHistoryKind.DATE_CHANGED || it.kind == YpsoHistoryKind.TIME_CHANGED }?.let {
                val replaced = localDateTime(row.factorySeconds)
                if (it.clockSetTo == null || replacedNext?.isBefore(it.clockSetTo) == true) return RowTimes(offsets, row)
                offset -= Duration.between(replaced, it.clockSetTo).toMillis()
                replacedNext = replaced
            }
            if (live != null) offsets[row.sequence] = offset
        }
        return RowTimes(offsets, null)
    }
}
