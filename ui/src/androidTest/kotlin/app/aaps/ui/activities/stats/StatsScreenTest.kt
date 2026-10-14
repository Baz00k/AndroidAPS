package app.aaps.ui.activities.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.aaps.core.compose.theme.AapsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Presentation only; no therapy database, settings or pump is connected. */
class StatsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun availableHistoryRangeSelectionAndRetry() {
        var state by mutableStateOf(StatsUiState(loading = false, rangeDays = 7, insulinDays = 8))
        compose.setContent { AapsTheme { StatsScreen(state, StatsActions({ state = StatsUiState(rangeDays = it) }, {})) } }
        compose.onNodeWithText("TDD and carbs: 8 available daily records").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("90d").performScrollTo().performClick()
        compose.onNodeWithText("Last 90 days").assertIsDisplayed()
        compose.onNodeWithText("Loading statistics…").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(loading = false, failed = true) }
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Loading statistics…").assertIsDisplayed()
    }

    @Test fun recalculationNeedsConfirmation() {
        var recalculations = 0
        compose.setContent {
            AapsTheme { StatsScreen(StatsUiState(loading = false, insulinDays = 1), StatsActions({}, {}, onRecalculate = { recalculations++ })) }
        }
        compose.onNodeWithText("Recalculate daily totals").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, recalculations) }
        compose.onNodeWithText("Recalculate daily totals").performScrollTo().performClick()
        compose.onNodeWithText("Recalculate").performClick()
        compose.runOnIdle { assertEquals(1, recalculations) }
    }
}
