package app.aaps.ui.dialogs.compose

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WizardCarbControlsTest {

    @Test
    fun `wizard carb controls stop at the configured carb maximum`() {
        val controls = WizardCarbControls.fromOverviewIncrements(listOf(5, 10, 20), maxCarbs = 48)

        assertThat(controls.increase(45)).isEqualTo(48)
        assertThat(controls.addIncrement(45, 10)).isEqualTo(48)
        assertThat(controls.clamp(60)).isEqualTo(48)
        assertThat(controls.clamp(-5)).isEqualTo(0)
        assertThat(controls.addIncrement(Int.MAX_VALUE, 20)).isEqualTo(48)
        assertThat(WizardCarbControls.fromOverviewIncrements(listOf(5, 10, 20), maxCarbs = 12).increase(10)).isEqualTo(12)
    }

    @Test
    fun `wizard uses the first configured increment as its step and all three as quick increments`() {
        val controls = WizardCarbControls.fromOverviewIncrements(listOf(7, 15, 25), maxCarbs = 48)

        assertThat(controls.step).isEqualTo(7)
        assertThat(controls.quickIncrements).containsExactly(7, 15, 25).inOrder()
        assertThat(controls.increase(15)).isEqualTo(22)
        assertThat(controls.decrease(15)).isEqualTo(8)
        assertThat(controls.decrease(3)).isEqualTo(0)
        assertThat(controls.addIncrement(15, 7)).isEqualTo(22)
        assertThat(controls.addIncrement(22, 7)).isEqualTo(29)
    }

    @Test
    fun `new settings yield new wizard controls`() {
        val before = WizardCarbControls.fromOverviewIncrements(listOf(5, 10, 20), maxCarbs = 48)
        val after = WizardCarbControls.fromOverviewIncrements(listOf(3, 12, 30), maxCarbs = 48)

        assertThat(before.increase(0)).isEqualTo(5)
        assertThat(after.increase(0)).isEqualTo(3)
        assertThat(after.quickIncrements).containsExactly(3, 12, 30).inOrder()
    }

    @Test
    fun `zero and negative overview increments are shown and cannot enter negative wizard carbs`() {
        val controls = WizardCarbControls.fromOverviewIncrements(listOf(-5, 0, 20), maxCarbs = 48)
        val zeroStep = WizardCarbControls.fromOverviewIncrements(listOf(0, -10, -20), maxCarbs = 48)

        assertThat(controls.step).isEqualTo(5)
        assertThat(controls.quickIncrements).containsExactly(-5, 0, 20).inOrder()
        assertThat(controls.addIncrement(12, -5)).isEqualTo(7)
        assertThat(controls.addIncrement(2, -5)).isEqualTo(0)
        assertThat(controls.addIncrement(7, 0)).isEqualTo(7)
        assertThat(controls.decrease(2)).isEqualTo(0)
        assertThat(zeroStep.step).isEqualTo(1)
        assertThat(zeroStep.quickIncrements).containsExactly(0, -10, -20).inOrder()
    }
}
