package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WizardResultTest {

    @Test
    fun `an adjustment that did not reach the total is reported as ignored`() {
        // No dose to adjust: a positive adjustment must not create one.
        assertThat(WizardResult(appliedAdjustment = 0.0).adjustmentIgnored(1.0)).isTrue()
        // The clamped negative adjustment still reached the total (it removed what was there).
        assertThat(WizardResult(appliedAdjustment = -2.0).adjustmentIgnored(-5.0)).isFalse()
        assertThat(WizardResult(appliedAdjustment = 0.5).adjustmentIgnored(0.5)).isFalse()
        assertThat(WizardResult(appliedAdjustment = 0.0).adjustmentIgnored(0.0)).isFalse()
    }

    @Test
    fun `the saved inputs keep the adjustment across recreation`() {
        val saved = with(WizardInputs.Saver) { SaverScope { true }.save(WizardInputs(carbs = 12, adjustment = -0.5)) }
        val restored = WizardInputs.Saver.restore(checkNotNull(saved))

        assertThat(restored).isEqualTo(WizardInputs(carbs = 12, adjustment = -0.5))
    }
}
