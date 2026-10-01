package app.aaps.core.compose.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class EntryTimeTest {

    private val zone = ZoneId.of("Europe/Warsaw")
    private val now = ZonedDateTime.of(2026, 9, 30, 10, 30, 20, 0, zone)

    @Test
    fun `offsets read as time from now`() {
        assertThat(EntryTime.relative(0)).isEqualTo("Now")
        assertThat(EntryTime.relative(15)).isEqualTo("in 15 min")
        assertThat(EntryTime.relative(-20)).isEqualTo("20 min ago")
        assertThat(EntryTime.relative(-80)).isEqualTo("1 h 20 min ago")
        assertThat(EntryTime.relative(120)).isEqualTo("in 2 h")
        assertThat(EntryTime.signed(-30)).isEqualTo("−30 min")
        assertThat(EntryTime.signed(0)).isEqualTo("Now")
    }

    @Test
    fun `a picked past time is today or yesterday, whichever is allowed`() {
        assertThat(EntryTime.offsetFor(now, LocalTime.of(9, 15), -12 * 60, 0)).isEqualTo(-75)
        // 23:00 is later than now: for a past-only entry that is last night.
        assertThat(EntryTime.offsetFor(now, LocalTime.of(23, 0), -12 * 60, 0)).isEqualTo(-(11 * 60 + 30))
    }

    @Test
    fun `a picked time outside the allowed window is held at the nearer limit`() {
        // 10:31 is one minute ahead; a past-only entry cannot be in the future.
        assertThat(EntryTime.offsetFor(now, LocalTime.of(10, 31), -12 * 60, 0)).isEqualTo(0)
        // 20:00 yesterday is 14.5 h back, beyond the 12 h window.
        assertThat(EntryTime.offsetFor(now, LocalTime.of(20, 0), -12 * 60, 0)).isEqualTo(-12 * 60)
    }

    @Test
    fun `a window that reaches into the future prefers the nearest occurrence`() {
        assertThat(EntryTime.offsetFor(now, LocalTime.of(11, 0), -60, 60)).isEqualTo(30)
        assertThat(EntryTime.offsetFor(now, LocalTime.of(10, 0), -60, 60)).isEqualTo(-30)
    }
}
