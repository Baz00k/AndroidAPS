package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.KeyTiming

/** Reconcile on every poll; only the consumer can know whether it already received a warning. */
internal class KeyExpiryNotification {
    fun publish(status: KeyTiming.Status?, set: (Pair<Long?, Boolean>?) -> Unit) {
        val next = status?.takeIf { it.reminderDue || it.expiryDue }?.let { it.expiresAt to it.expiryDue }
        // Emission is not acknowledgement: retry each poll for consumers attaching after startup.
        // The ordered consumer makes identical replacements idempotent without re-alerting.
        set(next)
    }

}
