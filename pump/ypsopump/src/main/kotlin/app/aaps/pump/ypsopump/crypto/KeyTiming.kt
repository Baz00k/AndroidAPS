package app.aaps.pump.ypsopump.crypto

/** Advisory metadata only. No value here is evidence of pump acceptance or delivery. */
data class KeyTiming(
    val expiryOverride: Long? = null,
    val reminderOverride: Long? = null,
    val expiryDue: Boolean = false,
    val reminderDue: Boolean = false,
    val observedAt: Long = 0,
) {
    enum class Origin { SOURCE, IMPORT_ESTIMATE, USER, UNKNOWN }
    data class Status(
        val expiresAt: Long?,
        val reminderAt: Long?,
        val origin: Origin,
        val reminderOrigin: Origin,
        val expiryDue: Boolean,
        val reminderDue: Boolean,
        val remainingMs: Long?,
    )

    fun status(createdAt: Long?, importedAt: Long?, now: Long): Status {
        val effectiveNow = maxOf(now, observedAt, 0)
        val source = validBaseline(createdAt)?.takeIf { importedAt == null || it <= importedAt }
        val baseline = source ?: validBaseline(importedAt)
        val deadline = expiryOverride ?: baseline?.plus(MAX_LIFETIME_MS)
        val reminder = reminderOverride ?: deadline?.minus(ADVANCE_NOTICE_MS)
        val due = expiryDue || deadline?.let { effectiveNow >= it } == true
        val origin = if (expiryOverride != null) Origin.USER else if (source != null) Origin.SOURCE else if (baseline != null) Origin.IMPORT_ESTIMATE else Origin.UNKNOWN
        return Status(
            deadline, reminder,
            origin,
            if (reminderOverride != null) Origin.USER else origin,
            due, reminderDue || reminder?.let { effectiveNow >= it } == true,
            deadline?.let { if (due) minOf(0, it - effectiveNow) else it - effectiveNow },
        )
    }

    /** Once observed due, clock rollback/restart cannot hide it. Explicit edits may reset that latch. */
    fun observe(createdAt: Long?, importedAt: Long?, now: Long): KeyTiming = status(createdAt, importedAt, now).let {
        copy(expiryDue = it.expiryDue, reminderDue = it.reminderDue, observedAt = maxOf(observedAt, now, 0))
    }

    companion object {
        const val DAY_MS = 24 * 60 * 60_000L
        const val MAX_LIFETIME_MS = 28 * DAY_MS
        const val ADVANCE_NOTICE_MS = 3 * DAY_MS
        // Invalid old metadata remains stored, but must not wrap into a plausible deadline.
        private fun validBaseline(value: Long?): Long? = value?.takeIf { it > 0 && it <= Long.MAX_VALUE - MAX_LIFETIME_MS }
    }
}
