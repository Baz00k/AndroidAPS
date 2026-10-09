package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.KeyTiming

/** Reconcile the shared notification store once per lifetime and on advisory state changes. */
internal class KeyExpiryNotification {
    private var synchronized = false
    private var published: Pair<Long?, Boolean>? = null

    fun publish(status: KeyTiming.Status?, dismiss: () -> Unit, add: (Long?, Boolean) -> Unit) {
        val next = status?.takeIf { it.reminderDue || it.expiryDue }?.let { it.expiresAt to it.expiryDue }
        if (synchronized && next == published) return
        dismiss()
        next?.let { add(it.first, it.second) }
        published = next
        synchronized = true
    }

    fun reset() { synchronized = false; published = null }
}
