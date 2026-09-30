package app.aaps.ui.dialogs.compose

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class InsulinEntryPolicyTest {

    private val zone = ZoneId.of("Europe/Warsaw")
    private val now = ZonedDateTime.of(2026, 9, 30, 10, 30, 0, 0, zone).toInstant().toEpochMilli()
    private val hour = TimeUnit.HOURS.toMillis(1)

    @Test
    fun `a delivered bolus is always recorded now, whatever time was picked for logging`() {
        assertThat(InsulinEntryPolicy.eventTime(InsulinIntent.DELIVER, now, now - 2 * hour)).isEqualTo(now)
    }

    @Test
    fun `a logged dose keeps its time within the last 12 hours`() {
        assertThat(InsulinEntryPolicy.eventTime(InsulinIntent.LOG, now, now - 2 * hour)).isEqualTo(now - 2 * hour)
        assertThat(InsulinEntryPolicy.eventTime(InsulinIntent.LOG, now, now - 12 * hour)).isEqualTo(now - 12 * hour)
    }

    @Test
    fun `a logged dose can be neither in the future nor older than 12 hours`() {
        assertThat(InsulinEntryPolicy.eventTime(InsulinIntent.LOG, now, now + hour)).isEqualTo(now)
        assertThat(InsulinEntryPolicy.eventTime(InsulinIntent.LOG, now, now - 13 * hour)).isEqualTo(now - 12 * hour)
    }

    @Test
    fun `a picked clock time is today when it has passed`() {
        val expected = ZonedDateTime.of(2026, 9, 30, 9, 15, 0, 0, zone).toInstant().toEpochMilli()
        assertThat(InsulinEntryPolicy.logTime(now, LocalTime.of(9, 15), zone)).isEqualTo(expected)
        assertThat(InsulinEntryPolicy.logTime(now, LocalTime.of(10, 30), zone)).isEqualTo(now)
    }

    @Test
    fun `a picked clock time later than now means yesterday, held at the 12 hour limit`() {
        val yesterday2300 = ZonedDateTime.of(2026, 9, 29, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertThat(InsulinEntryPolicy.logTime(now, LocalTime.of(23, 0), zone)).isEqualTo(yesterday2300)
        assertThat(InsulinEntryPolicy.logTime(now, LocalTime.of(10, 31), zone)).isEqualTo(now - 12 * hour)
    }

    @Test
    fun `delivery is unavailable to a follower or without a ready pump`() {
        assertThat(InsulinEntryPolicy.deliveryUnavailable(client = false, pumpSuspended = false, pumpInitialized = true)).isNull()
        assertThat(InsulinEntryPolicy.deliveryUnavailable(client = true, pumpSuspended = false, pumpInitialized = true)).isEqualTo(DeliveryUnavailable.FOLLOWER)
        assertThat(InsulinEntryPolicy.deliveryUnavailable(client = false, pumpSuspended = true, pumpInitialized = true)).isEqualTo(DeliveryUnavailable.PUMP_NOT_READY)
        assertThat(InsulinEntryPolicy.deliveryUnavailable(client = false, pumpSuspended = false, pumpInitialized = false)).isEqualTo(DeliveryUnavailable.PUMP_NOT_READY)
    }
}
