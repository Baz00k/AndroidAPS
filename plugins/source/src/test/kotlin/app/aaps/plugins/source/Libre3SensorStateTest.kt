package app.aaps.plugins.source

import app.aaps.plugins.source.compose.Libre3SensorState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The status line and signal mapping are the parts of the Sensor screen a person actually reads
 * at 3am, so they are pinned by tests rather than eyeballed.
 */
class Libre3SensorStateTest {

    @Test
    fun `warming up never reads as an error`() {
        val s = Libre3SensorState(
            connection = Libre3SensorState.Connection.Connected,
            lifecycle = Libre3SensorState.Lifecycle.WarmingUp(42)
        )
        // An hour with no data is the sensor working as designed. Saying "no data" here trains
        // exactly the distrust the whole screen exists to avoid.
        assertThat(s.statusLine).isEqualTo("Warming up · 42 min left")
        assertThat(s.statusLine.lowercase()).doesNotContain("no data")
        assertThat(s.statusLine.lowercase()).doesNotContain("error")
    }

    @Test
    fun `warm up wins over connection state`() {
        // Disconnected during warm-up is still "warming up" — the reconnect is routine and the
        // user cannot act on it, whereas the warm-up countdown is what they are waiting for.
        val s = Libre3SensorState(
            connection = Libre3SensorState.Connection.Disconnected,
            lifecycle = Libre3SensorState.Lifecycle.WarmingUp(5)
        )
        assertThat(s.statusLine).contains("Warming up")
    }

    @Test
    fun `active and connected reports reading freshness`() {
        val s = Libre3SensorState(
            connection = Libre3SensorState.Connection.Connected,
            lifecycle = Libre3SensorState.Lifecycle.Active(0.5f, "7d 2h"),
            lastReadingAgoMinutes = 1
        )
        assertThat(s.statusLine).isEqualTo("Connected · last reading 1m ago")
    }

    @Test
    fun `connected with no reading yet does not claim an age`() {
        val s = Libre3SensorState(
            connection = Libre3SensorState.Connection.Connected,
            lifecycle = Libre3SensorState.Lifecycle.Active(1f, "15d")
        )
        // null must render as "Connected", never as "0m ago" — which would look like fresh data.
        assertThat(s.statusLine).isEqualTo("Connected")
    }

    @Test
    fun `expired and failed are stated plainly`() {
        assertThat(
            Libre3SensorState(lifecycle = Libre3SensorState.Lifecycle.Expired).statusLine
        ).contains("expired")
        assertThat(
            Libre3SensorState(lifecycle = Libre3SensorState.Lifecycle.Failed(7)).statusLine
        ).contains("failed")
    }

    @Test
    fun `no credentials says so instead of pretending to connect`() {
        assertThat(Libre3SensorState().statusLine).isEqualTo("No sensor configured")
    }

    @Test
    fun `signal maps to coarse bars`() {
        fun bars(dbm: Int) = Libre3SensorState(signalDbm = dbm).signalBars
        assertThat(bars(-55)).isEqualTo(4)
        assertThat(bars(-65)).isEqualTo(3)
        assertThat(bars(-75)).isEqualTo(2)   // the -74 seen on the real sensor
        assertThat(bars(-85)).isEqualTo(1)
        assertThat(bars(-99)).isEqualTo(0)
    }

    @Test
    fun `unknown signal is null rather than zero bars`() {
        // Zero bars means "measured, and bad". Unknown must not masquerade as that.
        assertThat(Libre3SensorState().signalBars).isNull()
    }
}
