package app.aaps.plugins.main.general.overview.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.compose.theme.AapsTheme
import org.junit.Rule
import org.junit.Test

/** Display-only test: no therapy configuration, database or pump access. */
class HomeIobScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingProfileShowsUnavailableHeroAndSheetWithoutHidingRecordedDose() {
        val state = mutableStateOf(HomeUiState(
            bg = "99", cob = "0 g", basal = "0.00 U/h",
            iob = "--", iobBolus = "--", iobBasal = "--", iobSub = "No active profile",
            recentInsulin = listOf(HomeUiState.InsulinEntry(1, "10:32", "0.30 U", "", 1, 0.3)),
            ready = true
        ))
        compose.setContent {
            AapsTheme { HomeScreen(state.value, HomeActions(), graph = {}) }
        }
        compose.onNodeWithText("No active profile").assertIsDisplayed()
        compose.onNodeWithText("IOB").performClick()
        compose.onNodeWithText("Total").assertIsDisplayed()
        compose.onNodeWithText("From boluses").assertIsDisplayed()
        compose.onNodeWithText("From basal").assertIsDisplayed()
        compose.onNodeWithText("0.30 U").assertIsDisplayed()
        compose.onAllNodesWithText("--").assertCountEquals(4) // hero plus three sheet values
        compose.onAllNodesWithText("No active profile").assertCountEquals(2)[1].assertIsDisplayed()
        compose.onAllNodesWithText("0.00 U").assertCountEquals(0)

        // An open detail sheet must update too when a profile becomes usable or is removed.
        compose.runOnIdle { state.value = state.value.copy(iob = "0.25 U", iobBolus = "0.30 U", iobBasal = "-0.05 U", iobSub = "") }
        compose.onAllNodesWithText("0.25 U").assertCountEquals(2)[1].assertIsDisplayed()
        compose.onNodeWithText("-0.05 U").assertIsDisplayed()
        compose.onAllNodesWithText("No active profile").assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(iob = "--", iobBolus = "--", iobBasal = "--", iobSub = "No active profile") }
        compose.onAllNodesWithText("--").assertCountEquals(4)
        compose.onAllNodesWithText("No active profile").assertCountEquals(2)[1].assertIsDisplayed()
    }
}
