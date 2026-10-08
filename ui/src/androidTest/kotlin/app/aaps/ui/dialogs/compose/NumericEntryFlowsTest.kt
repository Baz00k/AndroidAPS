package app.aaps.ui.dialogs.compose

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.aaps.core.compose.theme.AapsTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** Simulated submit boundaries: no production persistence or pump is connected to these sheets. */
class NumericEntryFlowsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun carbsCannotSubmitClearedFractionalOrIncompleteAbsorptionDrafts() {
        val submissions = mutableListOf<CarbsInputs>()
        compose.setContent { AapsTheme {
            CarbsSheet(CarbsSheetState(100.0, listOf(5, 10, 20), 8, emptyList(), TargetPreset.NONE, false, false), submissions::add, {})
        } }
        val carbs = compose.onAllNodes(hasSetTextAction())[0]
        carbs.performTextReplacement("12")
        compose.onNodeWithText("Log 12 g").assertIsEnabled()
        carbs.performTextReplacement("")
        compose.onNodeWithText("Log 12 g").assertIsNotEnabled().performClick()
        carbs.performTextReplacement("-5")
        val absorption = compose.onAllNodes(hasSetTextAction())[1]
        absorption.performScrollTo().performTextReplacement("")
        compose.onNodeWithText("Log -5 g").assertIsNotEnabled()
        absorption.performTextReplacement("2")
        compose.onNodeWithText("Log -5 g").performClick().performClick()
        compose.runOnIdle {
            assertThat(submissions).hasSize(1)
            assertThat(submissions.single().carbs).isEqualTo(-5)
            assertThat(submissions.single().durationHours).isEqualTo(2)
        }
    }

    @Test fun insulinPublishesExactOffStepRequestAndDoesNotSubmitAStaleValue() {
        val submissions = mutableListOf<InsulinInputs>()
        compose.setContent { AapsTheme {
            InsulinSheet(InsulinSheetState(5.0, 0.025, 2, listOf(0.025), null, emptyList(), false), submissions::add, {})
        } }
        val amount = compose.onAllNodes(hasSetTextAction())[0]
        amount.performTextReplacement("0,025")
        compose.onNodeWithText("Deliver 0.025 U").assertIsEnabled()
        amount.performTextReplacement("0.")
        compose.onNodeWithText("Deliver 0.025 U").assertIsNotEnabled().performClick()
        amount.performTextReplacement("0.013")
        compose.onNodeWithText("Deliver 0.013 U").performClick().performClick()
        compose.runOnIdle { assertThat(submissions.map { it.amount }).containsExactly(0.013) }
    }

    @Test fun calculatorCannotReviewInvalidCarbsGlucoseOrAbsorption() {
        var computations = WizardInputs()
        compose.setContent { AapsTheme {
            WizardScreen(
                compute = { computations = it; WizardResult(available = true, outcome = CalculatorOutcome(carbs = it.carbs)) },
                onCommit = { _, _, _ -> error("Editing must not commit") }, onCancel = {}, initialInputs = WizardInputs(carbs = 5),
                carbControls = WizardCarbControls.fromOverviewIncrements(listOf(5, 10, 20), 100)
            )
        } }
        val carbs = compose.onAllNodes(hasSetTextAction())[0]
        carbs.performTextReplacement("")
        compose.onNodeWithText("Review").assertIsNotEnabled()
        compose.runOnIdle { assertThat(computations.carbs).isEqualTo(5) }
        carbs.performTextReplacement("12")
        compose.onNodeWithContentDescription("Enter glucose").performScrollTo().performClick()
        val glucose = compose.onAllNodes(hasSetTextAction())[0]
        glucose.performTextReplacement("-")
        compose.onNodeWithText("Review").assertIsNotEnabled()
        glucose.performTextReplacement("12,5")
        compose.onNodeWithText("Review").assertIsEnabled()
        compose.runOnIdle { assertThat(computations.manualBg).isEqualTo(12.5) }
        compose.onNodeWithText("Meal details").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextReplacement("")
        compose.onNodeWithText("Review").assertIsNotEnabled()
    }
}
