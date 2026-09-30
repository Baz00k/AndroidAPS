package app.aaps.ui.dialogs.compose

import app.aaps.core.data.model.RM
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PumpReadyGateTest {

    private val stopped = DeliveryBlocker.PumpStopped(
        reservoirEmpty = false,
        detail = "",
        wording = PumpWording("empty", "empty", "stopped", "stopped")
    )

    private fun classify(pumpSuspended: Boolean, mode: RM.Mode) =
        classifyBlocker(pumpSuspended, mode) { stopped }

    @Test
    fun `a stopped pump wins over every running mode`() {
        RM.Mode.entries.forEach { mode ->
            assertThat(classify(pumpSuspended = true, mode = mode)).isSameInstanceAs(stopped)
        }
    }

    @Test
    fun `pump status is only read when the pump is actually stopped`() {
        var read = false
        classifyBlocker(pumpSuspended = false, mode = RM.Mode.DISCONNECTED_PUMP) { read = true; stopped }
        assertThat(read).isFalse()
    }

    @Test
    fun `a pump declared disconnected offers no bolus anyway`() {
        assertThat(classify(pumpSuspended = false, mode = RM.Mode.DISCONNECTED_PUMP)).isEqualTo(DeliveryBlocker.PumpDisconnected)
    }

    @Test
    fun `other suspensions stay a loop-suspended warning`() {
        listOf(RM.Mode.SUSPENDED_BY_USER, RM.Mode.SUSPENDED_BY_DST, RM.Mode.SUSPENDED_BY_PUMP, RM.Mode.SUPER_BOLUS).forEach { mode ->
            assertThat(classify(pumpSuspended = false, mode = mode)).isEqualTo(DeliveryBlocker.LoopSuspended(mode))
        }
    }

    @Test
    fun `running modes do not block delivery`() {
        listOf(RM.Mode.CLOSED_LOOP, RM.Mode.OPEN_LOOP, RM.Mode.DISABLED_LOOP, RM.Mode.CLOSED_LOOP_LGS).forEach { mode ->
            assertThat(classify(pumpSuspended = false, mode = mode)).isNull()
        }
    }
}
