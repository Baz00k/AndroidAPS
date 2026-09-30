package app.aaps.ui.dialogs.compose

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Whether an insulin entry asks the pump for a bolus or records one given some other way. */
enum class InsulinIntent { DELIVER, LOG }

/** Why the pump cannot be asked for a bolus from this screen. */
enum class DeliveryUnavailable(val label: String) {
    FOLLOWER("Follower"),
    PUMP_NOT_READY("Pump not ready")
}

/**
 * The rules behind the Insulin screen. A delivered bolus happens now; only a logged dose carries a
 * time of its own, and that time is always in the past — a record of a dose that has not happened yet
 * would be counted as insulin on board before it exists.
 */
object InsulinEntryPolicy {

    val MAX_LOG_AGE_MS: Long = TimeUnit.HOURS.toMillis(12)

    fun deliveryUnavailable(client: Boolean, pumpSuspended: Boolean, pumpInitialized: Boolean): DeliveryUnavailable? = when {
        client                            -> DeliveryUnavailable.FOLLOWER
        pumpSuspended || !pumpInitialized -> DeliveryUnavailable.PUMP_NOT_READY
        else                              -> null
    }

    /** The time recorded for the entry. Delivery ignores any time picked while the screen was in Log mode. */
    fun eventTime(intent: InsulinIntent, now: Long, loggedAt: Long): Long = when (intent) {
        InsulinIntent.DELIVER -> now
        InsulinIntent.LOG     -> loggedAt.coerceIn(now - MAX_LOG_AGE_MS, now)
    }

    /**
     * A clock time picked for a logged dose, as the most recent such moment not after [now]: a time
     * later than the current clock means yesterday. Anything older than [MAX_LOG_AGE_MS] is held at that limit.
     */
    fun logTime(now: Long, time: LocalTime, zone: ZoneId): Long {
        val nowZoned = Instant.ofEpochMilli(now).atZone(zone)
        var picked = nowZoned.with(time).withSecond(0).withNano(0)
        if (picked.isAfter(nowZoned)) picked = picked.minusDays(1)
        return picked.toInstant().toEpochMilli().coerceAtLeast(now - MAX_LOG_AGE_MS)
    }
}
