package app.aaps.ui.dialogs.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import app.aaps.core.compose.theme.AapsTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class TherapyEntrySheetsTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun fillRecordsAPickedChangeWithoutInsulinAndSubmitsOnce() {
        val submissions = mutableListOf<FillInputs>()
        compose.setContent {
            AapsTheme {
                FillSheet(FillSheetState(5.0, 0.1, 1, presets = emptyList(), showNotes = false), submissions::add) {}
            }
        }
        compose.onNodeWithText("Confirm").assertIsNotEnabled()
        compose.onNodeWithText("CHANGED").assertDoesNotExist()

        compose.onNodeWithText("Pump site change").performScrollTo().performClick()
        compose.onNodeWithText("CHANGED").assertExists()
        compose.onNodeWithContentDescription("5 minutes later").assertIsNotEnabled()
        val before = System.currentTimeMillis()
        compose.onNodeWithContentDescription("5 minutes earlier").performScrollTo().performClick()
        val after = System.currentTimeMillis()

        tapTwice("Confirm")

        compose.runOnIdle {
            assertThat(submissions).hasSize(1)
            val submitted = submissions.single()
            assertThat(submitted.insulin).isEqualTo(0.0)
            assertThat(submitted.siteChange).isTrue()
            assertThat(submitted.insulinChange).isFalse()
            assertThat(submitted.changedAt!!).isAtLeast(before - 5 * 60_000L)
            assertThat(submitted.changedAt).isAtMost(after - 5 * 60_000L)
        }
    }

    @Test
    fun fillDoesNotAttachAPickedTimeToAPrimeOnlyEntry() {
        val submissions = mutableListOf<FillInputs>()
        compose.setContent {
            AapsTheme {
                FillSheet(FillSheetState(5.0, 0.1, 1, presets = emptyList(), showNotes = false), submissions::add) {}
            }
        }
        compose.onNodeWithText("Pump site change").performScrollTo().performClick()
        compose.onNodeWithContentDescription("5 minutes earlier").performScrollTo().performClick()
        compose.onNodeWithText("Pump site change").performScrollTo().performClick()
        compose.onNodeWithText("CHANGED").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add 0.1 U of insulin").performScrollTo().performClick()

        tapTwice("Confirm")

        compose.runOnIdle {
            assertThat(submissions).hasSize(1)
            assertThat(submissions.single().insulin).isWithin(0.0001).of(0.1)
            assertThat(submissions.single().siteChange).isFalse()
            assertThat(submissions.single().changedAt).isNull()
        }
    }

    @Test
    fun careShowsOnlyTheEventFieldsAndKeepsTheSensorDefault() {
        val submissions = mutableListOf<CareInputs>()
        compose.setContent {
            AapsTheme {
                CareSheet(careState(showBg = true, showDuration = false, mmol = true), submissions::add) {}
            }
        }
        compose.onNodeWithText("GLUCOSE").assertExists()
        compose.onNodeWithText("DURATION").assertDoesNotExist()
        compose.onNodeWithText("Sensor").assertIsSelected()

        compose.onNodeWithContentDescription("of glucose").performTextReplacement("5.5")
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithText("OK").performClick()

        compose.runOnIdle {
            assertThat(submissions).hasSize(1)
            with(submissions.single()) {
                assertThat(bg).isEqualTo(5.5)
                assertThat(meter).isEqualTo(CareMeter.MANUAL)
            }
        }
    }

    @Test
    fun careAllowsAZeroDurationWithoutShowingGlucose() {
        val submissions = mutableListOf<CareInputs>()
        compose.setContent {
            AapsTheme { CareSheet(careState(showBg = false, showDuration = true), submissions::add) {} }
        }
        compose.onNodeWithText("GLUCOSE").assertDoesNotExist()
        compose.onNodeWithText("DURATION").assertExists()
        compose.onNodeWithText("OK").assertIsEnabled().performClick()

        compose.runOnIdle {
            assertThat(submissions).hasSize(1)
            assertThat(submissions.single().durationMin).isEqualTo(0)
        }
    }

    @Test
    fun calibrationNeverSendsAnOutOfRangeMgdlInitialValue() {
        val sent = mutableListOf<Double>()
        compose.setContent {
            AapsTheme {
                CalibrationSheet(CalibrationSheetState(initial = 600.0, min = 36.0, max = 500.0, step = 1.0, decimals = 0, unitLabel = "mg/dL"), sent::add) {}
            }
        }
        val field = compose.onNodeWithContentDescription("of blood glucose")
        compose.onNodeWithText("Max 500 mg/dL").assertIsDisplayed()
        compose.onNodeWithText("Send calibration").assertIsNotEnabled()
        field.assertTextEquals("600")

        field.performTextReplacement("120")
        compose.onNodeWithText("Send calibration").assertIsEnabled().performClick()

        compose.runOnIdle { assertThat(sent).containsExactly(120.0) }
    }

    @Test
    fun calibrationKeepsAMissingMmolValueInvalidUntilCorrected() {
        val sent = mutableListOf<Double>()
        compose.setContent {
            AapsTheme {
                CalibrationSheet(CalibrationSheetState(initial = 0.0, min = 2.0, max = 30.0, step = 0.1, decimals = 1, unitLabel = "mmol/L"), sent::add) {}
            }
        }
        val field = compose.onNodeWithContentDescription("of blood glucose")
        compose.onNodeWithText("Min 2.0 mmol/L").assertIsDisplayed()
        compose.onNodeWithText("Send calibration").assertIsNotEnabled()
        field.assertTextEquals("0.0")

        field.performTextReplacement("5.5")
        compose.onNodeWithText("Send calibration").assertIsEnabled().performClick()

        compose.runOnIdle { assertThat(sent).containsExactly(5.5) }
    }

    /** Both taps in one batch: the latch, not a recomposed disabled button, must reject the second. */
    private fun tapTwice(label: String) {
        val bounds = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            click(bounds.center)
            click(bounds.center)
        }
        compose.waitForIdle()
    }

    private fun careState(showBg: Boolean, showDuration: Boolean, mmol: Boolean = false) = CareSheetState(
        title = "Care event",
        submitLabel = "OK",
        showBg = showBg,
        bgInitial = if (showBg) (if (mmol) 6.0 else 110.0) else 0.0,
        bgMin = if (mmol) 2.0 else 36.0,
        bgMax = if (mmol) 30.0 else 500.0,
        bgStep = if (mmol) 0.1 else 1.0,
        bgDecimals = if (mmol) 1 else 0,
        bgUnit = if (mmol) "mmol/L" else "mg/dL",
        showDuration = showDuration,
        durationMax = 1440.0,
        durationStep = 10.0,
        showNotes = false
    )
}
