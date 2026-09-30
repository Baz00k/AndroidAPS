package app.aaps.ui.dialogs.compose

import app.aaps.core.data.time.T

/** Which reminders the Carbs dialog schedules once the user has confirmed. */
data class CarbReminderPlan(
    /** Seconds until the "time to eat" alarm, or null when no eat reminder is scheduled. */
    val eatReminderSeconds: Int?,
    val bolusReminder: Boolean
)

object CarbReminders {

    /**
     * @param carbsAfterConstraints carbs that will actually be entered (after constraints)
     * @param timeOffsetMin minutes from now to the meal; only a future meal (> 0) can be reminded
     * @param bolusReminderOffered whether the "remind me to bolus" toggle was shown to the user; a stale
     *   `true` from a toggle that is no longer offered is ignored
     */
    fun plan(
        carbsAfterConstraints: Int,
        timeOffsetMin: Int,
        eatReminder: Boolean,
        bolusReminder: Boolean,
        bolusReminderOffered: Boolean
    ): CarbReminderPlan = CarbReminderPlan(
        eatReminderSeconds = T.mins(timeOffsetMin.toLong()).secs().toInt()
            .takeIf { eatReminder && carbsAfterConstraints > 0 && timeOffsetMin > 0 },
        bolusReminder = bolusReminder && bolusReminderOffered
    )
}
