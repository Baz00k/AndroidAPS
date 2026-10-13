package app.aaps.plugins.main.general.overview.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.aaps.core.compose.theme.AapsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Display-only test: no therapy configuration, database or pump access. */
class HomeIobScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingProfileShowsUnavailableHeroAndSheetWithoutHidingRecordedDose() {
        val state = mutableStateOf(HomeUiState(
            bg = "99", cob = "--", basal = "--",
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
        compose.onAllNodesWithText("--").assertCountEquals(6) // three hero plus three sheet values
        compose.onAllNodesWithText("No active profile").assertCountEquals(2)[1].assertIsDisplayed()
        compose.onAllNodesWithText("0.00 U").assertCountEquals(0)

        // An open detail sheet must update too when a profile becomes usable or is removed.
        compose.runOnIdle { state.value = state.value.copy(iob = "0.25 U", iobBolus = "0.30 U", iobBasal = "-0.05 U", iobSub = "") }
        compose.onAllNodesWithText("0.25 U").assertCountEquals(2)[1].assertIsDisplayed()
        compose.onNodeWithText("-0.05 U").assertIsDisplayed()
        compose.onAllNodesWithText("No active profile").assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(iob = "--", iobBolus = "--", iobBasal = "--", iobSub = "No active profile") }
        compose.onAllNodesWithText("--").assertCountEquals(6)
        compose.onAllNodesWithText("No active profile").assertCountEquals(2)[1].assertIsDisplayed()
    }

    @Test fun unavailableCobSheetKeepsRecordedCarbsVisibleWithReason() {
        compose.setContent {
            AapsTheme {
                HomeScreen(HomeUiState(
                    bg = "99", iob = "--", cob = "--", basal = "--", iobSub = "No active profile",
                    recentCarbs = listOf(HomeUiState.CarbEntry(1, "10:32", "12 g", 1, 12)), ready = true
                ), HomeActions(), graph = {})
            }
        }
        compose.onNodeWithText("COB").performClick()
        compose.onNodeWithText("Carbs on board").assertIsDisplayed()
        compose.onNodeWithText("12 g").assertIsDisplayed()
        compose.onAllNodesWithText("No active profile").assertCountEquals(2)[1].assertIsDisplayed()
        compose.onAllNodesWithText("0 g").assertCountEquals(0)
    }

    @Test fun missingProfileReasonFitsNarrowHomeWithLargeSystemText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                AapsTheme {
                    Box(Modifier.requiredWidth(320.dp)) {
                        HomeScreen(HomeUiState(iob = "--", iobSub = "No active profile", ready = true), HomeActions(), graph = {})
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("No active profile").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Expected rendered text layout", layouts.isNotEmpty())
        val layout = layouts.single()
        assertFalse("The complete reason must fit: size=${layout.size}, lines=${layout.lineCount}, widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}", layout.hasVisualOverflow)
    }
}
