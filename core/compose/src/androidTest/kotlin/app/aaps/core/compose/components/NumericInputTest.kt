package app.aaps.core.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsUiMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class NumericInputTest {
    @get:Rule val compose = createComposeRule()

    @Test fun draftsSurviveRestorationAndReconcileWithBoundsAndExternalValues() {
        val restoration = StateRestorationTester(compose)
        var value by mutableStateOf(8.0)
        var max by mutableStateOf(100.0)
        var valid = false
        val commits = mutableListOf<Double>()
        restoration.setContent {
            AapsTheme { NumberField("Amount", value, { value = it; commits.add(it) }, 1.0, 5.0, max, 0, unit = "g", onValidityChange = { valid = it }) }
        }
        val field = compose.onNodeWithContentDescription("Amount")
        field.performTextReplacement("")
        field.performTextReplacement("1")
        compose.runOnIdle { assertThat(valid).isFalse(); assertThat(commits).isEmpty(); assertThat(value).isEqualTo(8.0) }
        field.assertTextEquals("1")
        restoration.emulateSavedInstanceStateRestore()
        field.assertTextEquals("1")
        compose.runOnIdle { assertThat(valid).isFalse(); assertThat(commits).isEmpty() }
        field.performTextReplacement("12")
        compose.runOnIdle { assertThat(valid).isTrue(); assertThat(commits).containsExactly(12.0) }
        field.assertTextEquals("12")
        compose.runOnIdle { max = 7.0 }
        compose.runOnIdle { assertThat(valid).isFalse(); assertThat(value).isEqualTo(12.0) }
        field.assertTextEquals("12")
        field.performTextReplacement("-")
        compose.runOnIdle { value = 6.0 }
        field.assertTextEquals("6")
        compose.runOnIdle { assertThat(valid).isTrue() }
        field.performTextReplacement("12")
        compose.runOnIdle { assertThat(valid).isFalse(); max = 20.0 }
        compose.runOnIdle { assertThat(value).isEqualTo(12.0); assertThat(valid).isTrue() }
        field.assertTextEquals("12")
    }

    @Test fun unitAndNamedBoundSurviveTwoHundredPercentFont() {
        var value by mutableStateOf(0.0)
        var precision = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AapsTheme(mode = AapsUiMode.DARK) { Column(Modifier.width(280.dp)) {
                    NumericInput(value, { value = it }, NumericSpec(0.0, 0.063, 0.025, 2), "U", "Insulin", onPrecisionInsufficient = { precision = it })
                } }
            }
        }
        val plus = compose.onNodeWithContentDescription("Add 0.025 U Insulin")
        plus.performClick()
        compose.runOnIdle { assertThat(value).isEqualTo(0.025); assertThat(precision).isTrue() }
        val field = compose.onNodeWithContentDescription("Insulin")
        field.assertTextEquals("0.025")
        field.performTextReplacement("0.063")
        plus.assertIsNotEnabled()
        compose.onNodeWithText("Max 0.063 U").assertIsDisplayed()
        compose.onNodeWithText("U").assertIsDisplayed()
        val unit = compose.onNodeWithText("U").fetchSemanticsNode().boundsInRoot
        val entry = field.fetchSemanticsNode().boundsInRoot
        assertThat(entry.right).isAtMost(unit.left)
    }

    @Test fun valueAndUnitReadAsOneCentredGroupAndTheWholeFieldTakesTheTap() {
        compose.setContent {
            // At the default font scale the field's 44 dp minimum is taller than one line of digits.
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                AapsTheme { Column(Modifier.width(360.dp)) {
                    NumericInput(2.5, {}, NumericSpec(0.0, 10.0, 0.5, 1), "U", "Insulin")
                } }
            }
        }
        val minus = compose.onNodeWithContentDescription("Subtract 0.5 U Insulin").fetchSemanticsNode().boundsInRoot
        val plus = compose.onNodeWithContentDescription("Add 0.5 U Insulin").fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithContentDescription("Insulin")
        val node = field.fetchSemanticsNode()
        val entry = node.boundsInRoot
        val unit = compose.onNodeWithText("U").fetchSemanticsNode().boundsInRoot
        val dp = compose.density.density
        // Where the digits are drawn, not the field's bounds: a field can span the gap with its
        // number pushed against one end.
        val layout = mutableListOf<TextLayoutResult>().also { node.config[SemanticsActions.GetTextLayoutResult].action!!(it) }.single()
        val digitsLeft = entry.left + layout.getLineLeft(0)
        val digitsRight = entry.left + layout.getLineRight(0)
        // The unit follows the number rather than sitting at the far end of the field...
        assertThat(unit.left - digitsRight).isAtMost(8 * dp)
        // ...and the two are centred together between − and +.
        val groupCentre = (digitsLeft + unit.right) / 2
        val gapCentre = (minus.right + plus.left) / 2
        assertThat(groupCentre).isWithin(4 * dp).of(gapCentre)
        // The number sits level with − and +, not riding at the top of a field taller than it.
        val lineCentre = entry.top + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2
        assertThat(lineCentre).isWithin(3 * dp).of(minus.center.y)
        // Tapping the fill beside the digits still lands in the field.
        compose.onRoot().performTouchInput { click(Offset(plus.left - 24 * dp, entry.center.y)) }
        field.assertIsFocused()
    }
}
