package app.aaps.plugins.main.general.overview.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsTone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Standalone display test: no app database, therapy configuration or pump access. */
class HomeGlucoseScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun sensorRefreshDoesNotReplaceLoopAgeOrEventualPrediction() {
        var loopClicks = 0
        val state = mutableStateOf(HomeUiState(
            loopStateLabel = "Closed Loop", loopSubLabel = "· 4m ago", loopTone = AapsTone.InRange, looping = true,
            bg = "99", bgTone = AapsTone.InRange, trendArrow = "↓", delta = "-11", timeAgo = "10s ago",
            units = "mg/dL", eventualBg = "42", stateLine = "In target range", targetRange = "85–110 mg/dL",
            iob = "1.67 U", cob = "0 g", basal = "0.00 U/h", ready = true
        ))
        compose.setContent {
            AapsTheme {
                HomeScreen(state.value, HomeActions(onLoop = { loopClicks++ }), graph = {})
            }
        }
        compose.onNodeWithText("Closed Loop  · 4m ago").assertExists().performClick()
        assertEquals(1, loopClicks)
        compose.onNodeWithText("99").assertExists()
        compose.onNodeWithText("↓ -11").assertExists()
        compose.onNodeWithText("10s ago").assertExists()
        compose.runOnIdle {
            state.value = state.value.copy(bg = "95", trendArrow = "↘", delta = "-12", timeAgo = "0s ago")
        }
        compose.onNodeWithText("95").assertExists()
        compose.onNodeWithText("↘ -12").assertExists()
        compose.onNodeWithText("0s ago").assertExists()
        compose.onNodeWithText("Closed Loop  · 4m ago").assertExists()
        compose.onNodeWithText("42").assertExists()
        compose.onNodeWithText("EVENTUAL").assertExists()
    }
}
