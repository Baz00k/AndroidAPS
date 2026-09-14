package app.aaps.plugins.main.general.overview

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class CompactDurationLabelTest {

    @Test
    fun underAnHourShowsMinutes() {
        assertThat(compactDurationLabel(TimeUnit.MINUTES.toMillis(27))).isEqualTo("27m")
        assertThat(compactDurationLabel(TimeUnit.MINUTES.toMillis(59))).isEqualTo("59m")
    }

    @Test
    fun underADayShowsWholeHours() {
        assertThat(compactDurationLabel(TimeUnit.HOURS.toMillis(5))).isEqualTo("5h")
        assertThat(compactDurationLabel(TimeUnit.HOURS.toMillis(23) + TimeUnit.MINUTES.toMillis(59))).isEqualTo("23h")
    }

    @Test
    fun exactlyOneDayShowsDaysAndZeroHours() {
        assertThat(compactDurationLabel(TimeUnit.HOURS.toMillis(24))).isEqualTo("1d 0h")
    }

    @Test
    fun pastADayKeepsRemainderHours() {
        assertThat(compactDurationLabel(TimeUnit.HOURS.toMillis(27))).isEqualTo("1d 3h")
        assertThat(compactDurationLabel(TimeUnit.HOURS.toMillis(47) + TimeUnit.MINUTES.toMillis(59))).isEqualTo("1d 23h")
    }

    @Test
    fun exactlyTwoDays() {
        assertThat(compactDurationLabel(TimeUnit.HOURS.toMillis(48))).isEqualTo("2d 0h")
    }

    @Test
    fun negativeDurationDoesNotProduceANegativeLabel() {
        assertThat(compactDurationLabel(-TimeUnit.HOURS.toMillis(25))).isEqualTo("0m")
    }
}
