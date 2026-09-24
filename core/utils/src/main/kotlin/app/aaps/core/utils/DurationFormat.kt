package app.aaps.core.utils

import java.util.concurrent.TimeUnit

/**
 * Compact duration label for age/remaining-time tiles: whole minutes below an hour, whole hours
 * below a day, then days + remainder hours ("27m", "23h", "1d 3h"). Shared by the Home cannula
 * age and sensor countdown and by the pump profile "last read" row so they cannot drift apart.
 * The hours component is kept even when zero ("2d 0h") so the days part stays in place at day
 * boundaries.
 */
fun compactDurationLabel(millis: Long): String {
    val elapsed = millis.coerceAtLeast(0L)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    return when {
        hours >= 24 -> "${hours / 24}d ${hours % 24}h"
        hours >= 1  -> "${hours}h"
        else        -> "${TimeUnit.MILLISECONDS.toMinutes(elapsed)}m"
    }
}
