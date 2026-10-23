package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import app.aaps.core.compose.theme.AapsTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class WizardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun repeatedTapsAtTheSamePositionAddCarbs() {
        var inputs = WizardInputs()
        showCalculator { inputs = it }
        val button = compose.onNodeWithText("+5 g")
        val bounds = button.fetchSemanticsNode().boundsInRoot

        repeat(2) { tap ->
            // A semantic click would follow a moving button and miss the original regression.
            compose.onRoot().performTouchInput { click(bounds.center) }
            compose.waitForIdle()
            assertThat(inputs.carbs).isEqualTo((tap + 1) * 5)
            assertThat(button.fetchSemanticsNode().boundsInRoot).isEqualTo(bounds)
        }
    }

    @Test
    fun collapsingAndReopeningKeepsEditedMealInputs() {
        var inputs = WizardInputs()
        showCalculator(initial = WizardInputs(carbs = 5)) { inputs = it }
        val details = compose.onNodeWithText("Meal details")
        val absorptionPlus = compose.onNodeWithContentDescription("Add 1 h of carb absorption")

        details.performClick()
        compose.onNodeWithContentDescription("5 minutes later").performScrollTo().performClick()
        absorptionPlus.performScrollTo().performClick()
        details.performScrollTo().performClick()
        details.performClick()
        // Edit again after reopening: timing and absorption must not reset with the accordion body.
        absorptionPlus.performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(inputs).isEqualTo(WizardInputs(carbs = 5, carbTime = 5, carbDurationHours = 2))
    }

    @Test
    fun adjustmentIsCollapsedAndEditedInInsulinWithoutTouchingCarbs() {
        var inputs = WizardInputs(carbs = 5)
        showCalculator(initial = WizardInputs(carbs = 5)) { inputs = it }
        val field = compose.onNodeWithContentDescription("of dose adjustment")
        field.assertDoesNotExist()

        openAdjustment()
        compose.onNodeWithContentDescription("Add 0.1 U of dose adjustment").performScrollTo().performClick()
        compose.waitForIdle()
        assertThat(inputs.adjustment).isWithin(0.0001).of(0.1)

        field.performScrollTo().performTextClearance()
        field.performTextInput("-0.3")
        compose.waitForIdle()
        assertThat(inputs.adjustment).isWithin(0.0001).of(-0.3)
        assertThat(inputs.carbs).isEqualTo(5)
    }

    @Test
    fun anOffStepAdjustmentCannotBeReviewed() {
        showCalculator(initial = WizardInputs(carbs = 5)) { }
        openAdjustment()
        val field = compose.onNodeWithContentDescription("of dose adjustment")

        field.performScrollTo().performTextClearance()
        field.performTextInput("0.25")
        compose.waitForIdle()
        compose.onNodeWithText("Use 0.1 U steps").assertExists()
        compose.onNodeWithText("Review").assertIsNotEnabled()
    }

    private fun openAdjustment() {
        compose.onNodeWithText("Adjust dose").performScrollTo().performClick()
    }

    private fun showCalculator(initial: WizardInputs = WizardInputs(), onInputs: (WizardInputs) -> Unit) {
        compose.setContent {
            AapsTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    WizardScreen(
                        compute = {
                            onInputs(it)
                            // Include advice becoming available on the first carb tap in the same regression.
                            WizardResult(
                                available = true,
                                appliedAdjustment = it.adjustment,
                                adjustmentStep = 0.1,
                                adjustmentMax = 10.0,
                                adjustmentDecimals = 1,
                                advisorAvailable = it.carbs > 0,
                                outcome = CalculatorOutcome(insulin = 1.0, carbs = it.carbs)
                            )
                        },
                        onCommit = { _, _, _ -> error("Editing inputs must not commit") },
                        onCancel = {},
                        initialInputs = initial,
                        carbControls = WizardCarbControls.fromOverviewIncrements(listOf(5, 10, 20), maxCarbs = 100)
                    )
                }
            }
        }
    }
}
