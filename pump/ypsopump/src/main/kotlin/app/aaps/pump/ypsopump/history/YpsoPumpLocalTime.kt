package app.aaps.pump.ypsopump.history

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

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
}
