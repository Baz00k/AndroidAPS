package app.aaps.plugins.main.general.overview.compose

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
        val state = HomeUiState(
            bg = "99", cob = "--", basal = "--",
            iob = "--", iobBolus = "--", iobBasal = "--", iobSub = "No active profile",
            recentInsulin = listOf(HomeUiState.InsulinEntry(1, "10:32", "0.30 U", "", 1, 0.3)),
            ready = true
        )
        compose.setContent {
            AapsTheme { HomeScreen(state, HomeActions(), graph = {}) }
        }
        compose.onNodeWithText("No active profile").assertIsDisplayed()
        compose.onNodeWithText("IOB").performClick()
        compose.onNodeWithText("0.30 U").assertIsDisplayed()
        compose.onAllNodesWithText("--").assertCountEquals(6) // three hero plus three sheet values
        compose.onAllNodesWithText("No active profile").assertCountEquals(1) // one shared Home reason, not repeated in the sheet
        compose.onAllNodesWithText("0.00 U").assertCountEquals(0)
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
        assertFalse("The complete reason must fit", layout.hasVisualOverflow)
    }
}
