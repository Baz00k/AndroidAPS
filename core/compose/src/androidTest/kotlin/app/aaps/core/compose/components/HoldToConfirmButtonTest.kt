package app.aaps.core.compose.components

import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import app.aaps.core.compose.theme.AapsTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class HoldToConfirmButtonTest {

    // "Remove animations": the system animator scale is 0.
    @get:Rule
    val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor = 0f
    })

    @Test
    fun aHoldIsRequiredWithAnimationsRemoved() {
        var confirmed = 0
        compose.setContent { AapsTheme { HoldToConfirmButton("Deliver 1.00 U", onConfirm = { confirmed++ }) } }
        compose.mainClock.autoAdvance = false
        val button = compose.onNodeWithText("Deliver 1.00 U")

        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        button.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1500)
        assertThat(confirmed).isEqualTo(0)

        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(800)
        assertThat(confirmed).isEqualTo(0)
        compose.mainClock.advanceTimeBy(300)
        assertThat(confirmed).isEqualTo(1)
        button.performTouchInput { up() }
    }
}
