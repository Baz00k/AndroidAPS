package app.aaps.core.compose.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * Sheet and dialog chrome: the action stays on screen however long the content is, and a hosted sheet
 * is dragged away by its header only.
 */
class SheetSurfacesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun rows(count: Int) = List(count) { "Row ${it + 1}" }

    /** A host window shorter than its content, as on a small phone at 200% font. */
    private fun inShortWindow(content: @Composable () -> Unit) =
        compose.setContent { AapsTheme { Box(Modifier.height(360.dp)) { content() } } }

    @Test
    fun aLongSheetScrollsItsBodyUnderAPinnedFooter() {
        inShortWindow {
            SheetSurface(title = "Temp basal", footer = { PrimaryButton("Set temp basal", {}) }) {
                rows(30).forEach { Text(it) }
            }
        }
        compose.onNodeWithText("Set temp basal").assertIsDisplayed()
        compose.onNodeWithText("Row 30").assertIsNotDisplayed().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Set temp basal").assertIsDisplayed()
        compose.onNodeWithText("Temp basal").assertIsDisplayed()
    }

    @Test
    fun theLibraryScrollsASheetWithoutAFooter() {
        inShortWindow {
            SheetSurface(title = "Insulin on board", scrollContent = true) {
                rows(30).forEach { Text(it) }
            }
        }
        compose.onNodeWithText("Row 30").assertIsNotDisplayed().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Insulin on board").assertIsDisplayed()
    }

    @Test
    fun aLongAlertMessageScrollsAndKeepsTheActionsOnScreen() {
        inShortWindow {
            AlertContent(
                title = "Confirmation",
                message = rows(40).joinToString("\n"),
                actions = listOf(AlertAction("OK", {}, primary = true), AlertAction("Cancel", {}))
            )
        }
        compose.onNodeWithText("OK").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNode(hasScrollAction()).assertIsDisplayed()
    }

    @Test
    fun aModalSheetIsDraggedAwayByItsHeaderOnly() {
        var dismissed = 0
        showModalSheet(onDismissed = { dismissed++ }) { close ->
            SheetSurface(title = "Recent carbs", onClose = { close {} }, scrollContent = true) {
                rows(40).forEach { Text(it) }
            }
        }

        // Pulling a scrolled-to-top body down is the body's overscroll, not a drag of the sheet.
        compose.onNodeWithText("Row 2").performTouchInput { pullDown() }
        compose.waitForIdle()
        assertThat(dismissed).isEqualTo(0)
        compose.onNodeWithText("Recent carbs").assertIsDisplayed()

        compose.onNodeWithText("Recent carbs").performTouchInput { pullDown() }
        compose.waitForIdle()
        assertThat(dismissed).isEqualTo(1)
        compose.onNodeWithText("Recent carbs").assertDoesNotExist()
    }

    @Test
    fun aDragOnContentThatDoesNotScrollStaysWithTheContent() {
        var dismissed = 0
        showModalSheet(onDismissed = { dismissed++ }) { close ->
            SheetSurface(title = "Recent carbs", onClose = { close {} }) {
                Text("No carb entries in the last few hours.")
            }
        }

        compose.onNodeWithText("No carb entries in the last few hours.").performTouchInput { pullDown() }
        compose.waitForIdle()
        assertThat(dismissed).isEqualTo(0)
        compose.onNodeWithText("Recent carbs").assertIsDisplayed()
    }

    @Test
    fun closingRunsTheActionOnceAfterTheSheetIsGone() {
        var dismissed = 0
        var actions = 0
        var ranBeforeDismissal = true
        showModalSheet(onDismissed = { dismissed++ }) { close ->
            SheetSurface(title = "Recent carbs", onClose = { close {} }) {
                PrimaryButton("Remove", {
                    // A callback delivered twice: only the first close counts.
                    repeat(2) {
                        close {
                            actions++
                            ranBeforeDismissal = dismissed == 0
                        }
                    }
                })
            }
        }

        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()

        assertThat(actions).isEqualTo(1)
        assertThat(dismissed).isEqualTo(1)
        assertThat(ranBeforeDismissal).isFalse()
    }

    private fun showModalSheet(onDismissed: () -> Unit, content: @Composable (close: (after: () -> Unit) -> Unit) -> Unit) =
        compose.setContent {
            AapsTheme {
                var open by remember { mutableStateOf(true) }
                if (open) ModalSheet(onDismissed = { open = false; onDismissed() }, content = content)
            }
        }

    /** A firm downward drag, far past the sheet's dismiss threshold. */
    private fun TouchInjectionScope.pullDown() =
        swipe(start = center, end = center + Offset(0f, 1600f), durationMillis = 250)
}
