package app.aaps.ui.dialogs.compose


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

    const val MAX_LOG_AGE_MIN: Int = 12 * 60

    fun deliveryUnavailable(client: Boolean, pumpSuspended: Boolean, pumpInitialized: Boolean): DeliveryUnavailable? = when {
        client                            -> DeliveryUnavailable.FOLLOWER
        pumpSuspended || !pumpInitialized -> DeliveryUnavailable.PUMP_NOT_READY
        else                              -> null
    }

    /**
     * The time recorded for the entry. Delivery ignores any time picked while the screen was in Log mode;
     * a logged dose is held within the last [MAX_LOG_AGE_MIN] minutes and never in the future.
     */
    fun eventTime(intent: InsulinIntent, now: Long, offsetMin: Int): Long = when (intent) {
        InsulinIntent.DELIVER -> now
        InsulinIntent.LOG     -> now + offsetMin.coerceIn(-MAX_LOG_AGE_MIN, 0) * 60_000L
    }
}
