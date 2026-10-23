package app.aaps.ui.dialogs.compose

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WizardResultTest {

    @Test
    fun `state saved before the adjustment existed restores it as zero`() {
        val old = listOf(12, 0, 0, Double.NaN, true, true, false, false, false, false, false)

        assertThat(checkNotNull(WizardInputs.Saver.restore(old)).adjustment).isEqualTo(0.0)
    }

    @Test
    fun `an adjustment must be a whole pump step`() {
        assertThat(isWholeStep(0.3, 0.1)).isTrue()
        assertThat(isWholeStep(-0.5, 0.1)).isTrue()
        assertThat(isWholeStep(0.25, 0.1)).isFalse()
        assertThat(isWholeStep(0.5, 0.0)).isFalse()
    }
}
