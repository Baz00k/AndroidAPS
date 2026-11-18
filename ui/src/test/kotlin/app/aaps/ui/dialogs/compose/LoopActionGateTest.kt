package app.aaps.ui.dialogs.compose

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LoopActionGateTest {

    @Test
    fun `only the first of several taps reaches the action`() {
        val acted = mutableListOf<LoopActionId>()
        val gate = LoopActionGate { acted += it }

        gate.offer(LoopActionId.SUSPEND_1H)
        gate.offer(LoopActionId.SUSPEND_1H)
        gate.offer(LoopActionId.DISCONNECT_3H)

        assertThat(acted).containsExactly(LoopActionId.SUSPEND_1H)
    }
}
