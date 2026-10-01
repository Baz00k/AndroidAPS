package app.aaps.ui.dialogs.compose

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WizardInputsTest {

    @Test
    fun `carbs on board is not counted unless asked for`() {
        val inputs = WizardInputs()

        assertThat(inputs.countsCob(25.0)).isFalse()
        assertThat(inputs.countsIob(25.0)).isTrue()
    }

    @Test
    fun `carbs on board brings active insulin with it, so bolused carbs are not dosed twice`() {
        val inputs = WizardInputs(useCob = true, useIob = false)

        assertThat(inputs.countsCob(25.0)).isTrue()
        assertThat(inputs.countsIob(25.0)).isTrue()
    }

    @Test
    fun `unknown carbs on board is left out rather than counted as zero`() {
        val inputs = WizardInputs(useCob = true, useIob = false)

        assertThat(inputs.countsCob(null)).isFalse()
        // Nothing is dosed for carbs on board, so active insulin follows its own switch again.
        assertThat(inputs.countsIob(null)).isFalse()
    }
}
