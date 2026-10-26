package app.aaps.pump.ypsopump.history

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

/** Conversion of pump-local wall-clock seconds since 2000-01-01, with DST ambiguity explicit. */
object YpsoPumpLocalTime {
    private val FACTORY_EPOCH = LocalDateTime.of(2000, 1, 1, 0, 0)

    sealed interface Resolution {
        val localDateTime: LocalDateTime

        data class Resolved(
            override val localDateTime: LocalDateTime,
            val instant: Instant,
            val zoneId: ZoneId,
            val offset: ZoneOffset,
        ) : Resolution

        data class Gap(
            override val localDateTime: LocalDateTime,
            val zoneId: ZoneId,
        ) : Resolution

        data class Overlap(
            override val localDateTime: LocalDateTime,
            val zoneId: ZoneId,
            val offsets: List<ZoneOffset>,
        ) : Resolution
    }

    fun localDateTime(factorySeconds: Long): LocalDateTime {
        require(factorySeconds in 0..0xffffffffL) { "factorySeconds must be an unsigned 32-bit value" }
        return FACTORY_EPOCH.plusSeconds(factorySeconds)
    }

    /** AAPS milliseconds exist only when the pump's local wall clock maps to one zone offset. */
    fun resolve(factorySeconds: Long, zoneId: ZoneId): Resolution {
        val local = localDateTime(factorySeconds)
        val offsets = zoneId.rules.getValidOffsets(local)
        return when (offsets.size) {
            0 -> Resolution.Gap(local, zoneId)
            1 -> Resolution.Resolved(local, local.toInstant(offsets.single()), zoneId, offsets.single())
            else -> Resolution.Overlap(local, zoneId, offsets)
        }
    }

    /** Every instant this wall time can name: one, none in a gap, or each offset of an overlap. */
    fun candidates(factorySeconds: Long, zoneId: ZoneId): List<Long> {
        val local = localDateTime(factorySeconds)
        return zoneId.rules.getValidOffsets(local).map { local.toInstant(it).toEpochMilli() }
    }

    /**
     * The row's instant. [near] only picks an overlap's offset: it is when AAPS sent the command this
     * row records, so the row lies within [NEAR_TOLERANCE_MS] of it and the other offset an hour away.
     * Null in a gap, or in an overlap without [near] or with no candidate close to it.
     */
    fun epochMillis(factorySeconds: Long, zoneId: ZoneId, near: Long? = null): Long? {
        val candidates = candidates(factorySeconds, zoneId)
        if (candidates.size <= 1) return candidates.singleOrNull()
        return near?.let { reference -> candidates.singleOrNull { abs(it - reference) <= NEAR_TOLERANCE_MS } }
    }

    /** Pump clock drift plus the time a dose takes, far below the half hour separating overlap offsets. */
    const val NEAR_TOLERANCE_MS = 10 * 60_000L

    /**
     * Pump clock minus the phone clock [phoneMs]. A pump reading in the repeated hour takes the offset
     * nearest the phone, which a pump within minutes of it always is; the earlier offset alone reads an
     * hour off once the hour repeats. A reading in a skipped hour comes from a pump that has not moved
     * forward yet: every row it wrote before that hour has the offset before it, and rows in the hour
     * itself have no instant at all, so it is read at the offset before the gap.
     */
    fun clockOffsetMs(pumpLocal: LocalDateTime, phoneMs: Long, zoneId: ZoneId): Long {
        val offsets = zoneId.rules.getValidOffsets(pumpLocal)
            .ifEmpty { listOf(zoneId.rules.getTransition(pumpLocal).offsetBefore) }
        return offsets.map { pumpLocal.toInstant(it).toEpochMilli() - phoneMs }.minBy { abs(it) }
    }
}
