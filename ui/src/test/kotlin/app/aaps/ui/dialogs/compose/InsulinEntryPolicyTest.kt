package app.aaps.ui.dialogs.compose

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class InsulinEntryPolicyTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun `a delivered bolus is always recorded now, whatever time was picked for logging`() {
        assertThat(InsulinEntryPolicy.eventTime(InsulinIntent.DELIVER, now, now - 120 * minute)).isEqualTo(now)
    }

    @Test
    fun `a logged dose keeps its time within the last 12 hours`() {
        assertThat(InsulinEntryPolicy.eventTime(InsulinIntent.LOG, now, now - 120 * minute)).isEqualTo(now - 120 * minute)
        assertThat(InsulinEntryPolicy.eventTime(InsulinIntent.LOG, now, now - 12 * 60 * minute)).isEqualTo(now - 12 * 60 * minute)
        assertThat(InsulinEntryPolicy.eventTime(InsulinIntent.LOG, now, null)).isEqualTo(now)
    }

    @Test
    fun `a logged dose can be neither in the future nor older than 12 hours`() {
        assertThat(InsulinEntryPolicy.eventTime(InsulinIntent.LOG, now, now + 30 * minute)).isEqualTo(now)
        assertThat(InsulinEntryPolicy.eventTime(InsulinIntent.LOG, now, now - 13 * 60 * minute)).isEqualTo(now - 12 * 60 * minute)
    }

    @Test
    fun `a picked time keeps its moment however long the form stays open`() {
        val pickedAt09 = now - 60 * minute
        // Picked at 10:00 as 09:00; submitted twenty minutes later it is still 09:00.
        assertThat(InsulinEntryPolicy.eventTime(InsulinIntent.LOG, now + 20 * minute, pickedAt09)).isEqualTo(pickedAt09)
    }

    @Test
    fun `delivery is unavailable to a follower or without a ready pump`() {
        assertThat(InsulinEntryPolicy.deliveryUnavailable(client = false, pumpSuspended = false, pumpInitialized = true)).isNull()
        assertThat(InsulinEntryPolicy.deliveryUnavailable(client = true, pumpSuspended = false, pumpInitialized = true)).isEqualTo(DeliveryUnavailable.FOLLOWER)
        assertThat(InsulinEntryPolicy.deliveryUnavailable(client = false, pumpSuspended = true, pumpInitialized = true)).isEqualTo(DeliveryUnavailable.PUMP_NOT_READY)
        assertThat(InsulinEntryPolicy.deliveryUnavailable(client = false, pumpSuspended = false, pumpInitialized = false)).isEqualTo(DeliveryUnavailable.PUMP_NOT_READY)
    }
}
