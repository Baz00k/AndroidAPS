package app.aaps.plugins.source

import app.aaps.plugins.source.compose.Libre3SensorState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Lifecycle is DERIVED from the activation time rather than stored, so it stays correct across
 * restarts with no persistence of its own. These pin the boundaries, which is where a derived
 * value goes wrong.
 *
 * Mirrors `Libre3SourcePlugin.lifecycleNow()`.
 */
class Libre3LifecycleTest {

    private val warmup = 60
    private val lifeDays = 15
    private val lifeMin = lifeDays * 24 * 60

    private fun lifecycleAt(elapsedMin: Int): Libre3SensorState.Lifecycle = when {
        elapsedMin < 0 -> Libre3SensorState.Lifecycle.NoSensor
        elapsedMin < warmup -> Libre3SensorState.Lifecycle.WarmingUp(warmup - elapsedMin)
        elapsedMin >= lifeMin -> Libre3SensorState.Lifecycle.Expired
        else -> {
            val remaining = lifeMin - elapsedMin
            val h = remaining / 60
            Libre3SensorState.Lifecycle.Active(
                remaining.toFloat() / lifeMin,
                if (h >= 24) "${h / 24}d ${h % 24}h" else "${h}h"
            )
        }
    }

    @Test
    fun `warm up counts down and ends exactly at the boundary`() {
        assertThat((lifecycleAt(0) as Libre3SensorState.Lifecycle.WarmingUp).minutesRemaining).isEqualTo(60)
        assertThat((lifecycleAt(59) as Libre3SensorState.Lifecycle.WarmingUp).minutesRemaining).isEqualTo(1)
        // minute 60 is Active, not WarmingUp(0) — a zero-minute warm-up would read as stuck
        assertThat(lifecycleAt(60)).isInstanceOf(Libre3SensorState.Lifecycle.Active::class.java)
    }

    @Test
    fun `a fresh sensor reports nearly full life`() {
        val a = lifecycleAt(60) as Libre3SensorState.Lifecycle.Active
        assertThat(a.fractionRemaining).isGreaterThan(0.99f)
        assertThat(a.remainingLabel).isEqualTo("14d 23h")
    }

    @Test
    fun `expiry is the last minute, not a day early`() {
        assertThat(lifecycleAt(lifeMin - 1)).isInstanceOf(Libre3SensorState.Lifecycle.Active::class.java)
        assertThat(lifecycleAt(lifeMin)).isEqualTo(Libre3SensorState.Lifecycle.Expired)
    }

    @Test
    fun `remaining label switches from days to hours under one day`() {
        val oneDayLeft = lifecycleAt(lifeMin - 24 * 60) as Libre3SensorState.Lifecycle.Active
        assertThat(oneDayLeft.remainingLabel).isEqualTo("1d 0h")
        val fiveHoursLeft = lifecycleAt(lifeMin - 5 * 60) as Libre3SensorState.Lifecycle.Active
        assertThat(fiveHoursLeft.remainingLabel).isEqualTo("5h")
    }

    @Test
    fun `a clock that moved backwards is not reported as an active sensor`() {
        // Negative elapsed means the phone clock or the stored start is wrong. Claiming a healthy
        // sensor there would be worse than admitting we do not know.
        assertThat(lifecycleAt(-10)).isEqualTo(Libre3SensorState.Lifecycle.NoSensor)
    }

    @Test
    fun `fraction stays inside zero to one across the whole life`() {
        for (m in listOf(60, 1000, lifeMin / 2, lifeMin - 1)) {
            val a = lifecycleAt(m) as Libre3SensorState.Lifecycle.Active
            assertThat(a.fractionRemaining).isAtLeast(0f)
            assertThat(a.fractionRemaining).isAtMost(1f)
        }
    }
}
