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

    @Test
    fun tempBasalKeepsEachModesValueAndSubmitsAZeroRateOnce() {
        val submissions = mutableListOf<TempBasalInputs>()
        compose.setContent { AapsTheme { TempBasalSheet(tempBasalState(), submissions::add) {} } }
        val rate = compose.onNodeWithContentDescription("of basal rate")
        rate.assertTextEquals("100")

        compose.onNodeWithContentDescription("Add 10 % of basal rate").performClick()
        compose.onNodeWithText("Absolute").performClick()
        rate.assertTextEquals("0.80")
        compose.onNodeWithText("Percent").performClick()
        rate.assertTextEquals("110")

        rate.performTextReplacement("0")
        tapTwice("Set temp basal")

        compose.runOnIdle { assertThat(submissions).containsExactly(TempBasalInputs(isPercent = true, value = 0.0, durationMin = 30)) }
    }

    @Test
    fun tempBasalNeverSubmitsAPreviousRateOrDurationBelowOneStep() {
        val submissions = mutableListOf<TempBasalInputs>()
        compose.setContent { AapsTheme { TempBasalSheet(tempBasalState(), submissions::add) {} } }
        compose.onNodeWithContentDescription("Subtract 30 min of duration").assertIsNotEnabled()
        compose.onNodeWithContentDescription("of duration").performTextReplacement("0")
        compose.onNodeWithText("Set temp basal").assertIsNotEnabled()
        compose.onNodeWithContentDescription("of duration").performTextReplacement("720")

        compose.onNodeWithText("Absolute").performClick()
        compose.onNodeWithContentDescription("of basal rate").performTextReplacement("0.")
        compose.onNodeWithText("Set temp basal").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("of basal rate").performTextReplacement("2.5")
        compose.onNodeWithText("Set temp basal").assertIsEnabled().performClick()

        compose.runOnIdle { assertThat(submissions).containsExactly(TempBasalInputs(isPercent = false, value = 2.5, durationMin = 720)) }
    }

    @Test
    fun extendedBolusStartsAtOneStepAndNeedsPositiveInsulinAndDuration() {
        val submissions = mutableListOf<ExtendedBolusInputs>()
        compose.setContent {
            AapsTheme { ExtendedBolusSheet(ExtendedBolusSheetState(maxInsulin = 5.0, insulinStep = 0.05, insulinDecimals = 2, durationStep = 30.0, maxDuration = 720.0), submissions::add) {} }
        }
        compose.onNodeWithContentDescription("of insulin").assertTextEquals("0.05")
        compose.onNodeWithContentDescription("of duration").assertTextEquals("30")

        compose.onNodeWithContentDescription("Subtract 0.05 U of insulin").performClick()
        compose.onNodeWithText("Set extended bolus").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Add 0.05 U of insulin").performClick()
        compose.onNodeWithContentDescription("Add 0.05 U of insulin").performClick()
        compose.onNodeWithContentDescription("Subtract 30 min of duration").performClick()
        compose.onNodeWithText("Set extended bolus").assertIsNotEnabled()
        compose.onNodeWithContentDescription("of duration").performTextReplacement("90")
        tapTwice("Set extended bolus")

        compose.runOnIdle { assertThat(submissions).containsExactly(ExtendedBolusInputs(insulin = 0.1, durationMin = 90)) }
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

    private fun tempBasalState() = TempBasalSheetState(
        percentAllowed = true,
        absoluteAllowed = true,
        defaultIsPercent = true,
        percentDefault = 100.0,
        percentMax = 200.0,
        percentStep = 10.0,
        absoluteDefault = 0.8,
        absoluteMax = 10.0,
        absoluteStep = 0.05,
        absoluteDecimals = 2,
        durationDefault = 30.0,
        durationMax = 720.0,
        durationStep = 30.0
    )
}
