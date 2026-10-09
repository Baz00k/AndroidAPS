package app.aaps.core.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import app.aaps.core.compose.theme.AapsTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class HoldToConfirmButtonTest {

    @get:Rule
    val compose = createComposeRule()

    private var confirmations = 0

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AapsTheme {
                Column { HoldToConfirmButton("Confirm dose", { confirmations++ }, holdMillis = 600) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun aTapDoesNotConfirm() {
        show()
        compose.onNodeWithText("Confirm dose").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(confirmations).isEqualTo(0)
    }

    @Test
    fun releasingEarlyDoesNotConfirm() {
        show()
        val button = compose.onNodeWithText("Confirm dose")
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        button.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(confirmations).isEqualTo(0)
    }

    @Test
    fun holdingConfirmsOnlyOnce() {
        show()
        val button = compose.onNodeWithText("Confirm dose")
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(confirmations).isEqualTo(1)
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(confirmations).isEqualTo(1)
        button.performTouchInput { up() }
    }

    @Test
    fun cancellingTheGestureDoesNotConfirm() {
        show()
        val button = compose.onNodeWithText("Confirm dose")
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        button.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(confirmations).isEqualTo(0)
    }
}
