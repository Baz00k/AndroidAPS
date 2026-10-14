package app.aaps.ui.activities.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.aaps.core.compose.theme.AapsTheme
import org.junit.Rule
import org.junit.Test

/** Presentation fixtures only; no therapy database, settings or pump is connected. */
class StatsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rangesLoadingFailureRetryAndEmptyHistory() {
        var state by mutableStateOf(StatsUiState())
        compose.setContent { AapsTheme { StatsScreen(state, { state = StatsUiState(rangeDays = it) }, {}) } }
        compose.onNodeWithText("Loading statistics…").assertIsDisplayed()
        compose.onNodeWithText("90d").performClick().assertIsSelected()
        compose.onNodeWithText("Last 90 days").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(loading = false, failed = true) }
        compose.onNodeWithText("Could not load statistics").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Loading statistics…").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(loading = false) }
        compose.onNodeWithText("No glucose readings in this period").assertIsDisplayed()
        compose.onNodeWithText("No complete insulin history for this period").performScrollTo().assertIsDisplayed()
    }

    @Test fun glucoseIsUsableWhileInsulinLoadsAndOldResultsDisappearOnRangeChange() {
        var state by mutableStateOf(StatsUiState(loading = false, glucoseReadings = 100, tirReadings = 100,
                                                inRange = 80.0, avgGlucose = "6.1", avgGlucoseUnit = "mmol/L", insulinLoading = true,
                                                hourly = (0..23).map { HourProfile(it, 4.0, 5.0, 6.0, 7.0, 9.0, 12, 7) }))
        compose.setContent { AapsTheme { StatsScreen(state, { state = StatsUiState(rangeDays = it) }, {}) } }
        compose.onNodeWithText("6.1 mmol/L").assertIsDisplayed()
        compose.onNodeWithText("Loading insulin history…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("30d").performScrollTo().performClick()
        compose.onNodeWithText("6.1 mmol/L").assertDoesNotExist()
        compose.onNodeWithText("Loading statistics…").assertIsDisplayed()
    }

    @Test fun partialInsulinFailureKeepsGlucoseAndOffersRetry() {
        var retried = false
        compose.setContent { AapsTheme { StatsScreen(StatsUiState(loading = false, glucoseReadings = 1,
                                                               avgGlucose = "110", avgGlucoseUnit = "mg/dL", insulinFailed = true),
                                                      { retried = true }, {}) } }
        compose.onNodeWithText("110 mg/dL").assertIsDisplayed()
        compose.onNodeWithText("Could not load insulin history").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.runOnIdle { check(retried) }
    }

    @Test fun exceptionalMidnightHistoryDoesNotClaimMoreCompletedDaysThanSelected() {
        compose.setContent { AapsTheme { StatsScreen(StatsUiState(loading = false, rangeDays = 7, insulinDays = 8), {}, {}) } }
        compose.onNodeWithText("TDD and carbs: 8 available daily records").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("TDD and carbs: 8 of 7 completed days").assertDoesNotExist()
    }
}
