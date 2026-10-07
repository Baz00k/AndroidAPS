package app.aaps.plugins.main.general.actions.compose

import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import app.aaps.core.compose.theme.AapsTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class ActionsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun profileTapExplainsUnavailableSwitchWithoutDispatchingAndDispatchesWhenAvailable() {
        val reason = "This pump does not support basal profile programming from AAPS."
        val profile = mutableStateOf(
            TherapyAction(ActionId.PROFILE_SWITCH, "Profile switch", "Daily", enabled = false, unavailableReason = reason),
        )
        val dispatched = mutableListOf<ActionId>()
        compose.setContent {
            AapsTheme { ActionsScreen(ActionsUiState(therapy = listOf(profile.value)), dispatched::add) }
        }

        compose.onNodeWithText("Daily").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeAndWaitForEvent(
            { compose.onNodeWithText("Profile switch").performTouchInput { click() } },
            { event -> event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.text.any { it.toString() == reason } },
            3_000,
        )
        compose.runOnIdle {
            assertThat(dispatched).isEmpty()
            profile.value = profile.value.copy(enabled = true, unavailableReason = "")
        }
        compose.onNodeWithText("Profile switch").performClick()
        compose.runOnIdle { assertThat(dispatched).containsExactly(ActionId.PROFILE_SWITCH) }
    }
}
