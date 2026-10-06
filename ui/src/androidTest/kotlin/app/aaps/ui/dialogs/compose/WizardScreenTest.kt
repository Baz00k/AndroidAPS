package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import app.aaps.core.compose.theme.AapsTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class WizardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun repeatedCarbTapsKeepMealDetailsCollapsedAndButtonsAnchored() {
        checkRepeatedCarbTaps(advisorAvailable = false)
    }

    @Test
    fun repeatedCarbTapsKeepButtonsAnchoredWithBolusAdvisorAvailable() {
        checkRepeatedCarbTaps(advisorAvailable = true)
    }

    private fun checkRepeatedCarbTaps(advisorAvailable: Boolean) {
        var latestInputs = WizardInputs()
        compose.setContent {
            AapsTheme {
                // Match the host's bottom anchoring: a wrap-content sheet grows upward.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    WizardScreen(
                        compute = {
                            latestInputs = it
                            WizardResult(advisorAvailable = advisorAvailable && it.carbs > 0)
                        },
                        onCommit = { _, _, _ -> error("Entry must not commit while adding carbs") },
                        onCancel = {},
                        carbControls = WizardCarbControls.fromOverviewIncrements(listOf(5, 10, 20), maxCarbs = 20)
                    )
                }
            }
        }

        val buttons = listOf("+5 g", "+10 g", "+20 g")
        val originalBounds = buttons.associateWith { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        val tapPosition = originalBounds.getValue("+5 g").center

        repeat(3) { tap ->
            // Use the original physical position, not a semantic click which would follow a moving button.
            compose.onRoot().performTouchInput { click(tapPosition) }
            compose.waitForIdle()
            assertThat(latestInputs.carbs).isEqualTo((tap + 1) * 5)
            buttons.forEach {
                assertThat(compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot).isEqualTo(originalBounds.getValue(it))
            }
        }
        compose.onNodeWithText("When").assertDoesNotExist()
        compose.onNodeWithText("Absorption").assertDoesNotExist()
        if (advisorAvailable) compose.onNodeWithText("Advisor", useUnmergedTree = true).assertExists()
        // Returning to zero must also leave the controls anchored.
        repeat(3) { compose.onNodeWithContentDescription("Subtract 5 g of carbs").performClick() }
        compose.waitForIdle()
        assertThat(latestInputs.carbs).isEqualTo(0)
        compose.onNodeWithText("When").assertDoesNotExist()
        buttons.forEach {
            assertThat(compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot).isEqualTo(originalBounds.getValue(it))
        }
        repeat(4) { compose.onRoot().performTouchInput { click(tapPosition) } }
        compose.waitForIdle()
        assertThat(latestInputs.carbs).isEqualTo(20)
        compose.onNodeWithText("+5 g").assertIsNotEnabled()
        compose.onRoot().performTouchInput { click(tapPosition) }
        compose.waitForIdle()
        assertThat(latestInputs.carbs).isEqualTo(20)
        compose.onNodeWithText("Review").assertIsDisplayed()
        compose.onNodeWithText("Meal details").performClick()
        compose.onNodeWithText("When").assertExists()
        if (advisorAvailable) {
            compose.onNodeWithText("Eat once glucose falls").assertExists()
            compose.onNodeWithText("Advisor", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("Bolus advisor").assertDoesNotExist()
        }
        compose.onNodeWithText("Absorption").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("15-min trend").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review").assertIsDisplayed()
    }

    @Test
    fun collapsingMealDetailsKeepsTimingAbsorptionAndEatLater() {
        var latestInputs = WizardInputs()
        compose.setContent {
            AapsTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    WizardScreen(
                        compute = {
                            latestInputs = it
                            WizardResult(advisorAvailable = true)
                        },
                        onCommit = { _, _, _ -> error("Editing meal details must not commit") },
                        onCancel = {},
                        carbControls = WizardCarbControls.fromOverviewIncrements(listOf(5, 10, 20), maxCarbs = 20)
                    )
                }
            }
        }

        compose.onNodeWithText("Meal details").performClick()
        compose.onNodeWithContentDescription("5 minutes later").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Add 1 h of carb absorption").performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(latestInputs.carbTime).isEqualTo(5)
        assertThat(latestInputs.carbDurationHours).isEqualTo(1)

        compose.onNodeWithText("Meal details").performClick()
        compose.onNodeWithText("When").assertDoesNotExist()
        compose.onNodeWithText("Absorption 1 h", substring = true).assertExists()
        assertThat(latestInputs.carbTime).isEqualTo(5)
        assertThat(latestInputs.carbDurationHours).isEqualTo(1)

        compose.onNodeWithText("Meal details").performClick()
        compose.onNodeWithText("When").assertExists()
        val advisorLabel = compose.onNodeWithText("Eat once glucose falls").performScrollTo().fetchSemanticsNode().boundsInRoot
        val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        // ToggleRow's switch is trailing; its text is not itself a toggle target.
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(rootWidth * 0.87f, advisorLabel.center.y + advisorLabel.height / 2)) }
        compose.waitForIdle()
        assertThat(latestInputs.eatLater).isTrue()

        compose.onNodeWithText("Meal details").performClick()
        compose.onNodeWithText("Carbs not logged", substring = true).assertIsDisplayed()
        assertThat(latestInputs.eatLater).isTrue()
        assertThat(latestInputs.carbDurationHours).isEqualTo(1)
    }
}
