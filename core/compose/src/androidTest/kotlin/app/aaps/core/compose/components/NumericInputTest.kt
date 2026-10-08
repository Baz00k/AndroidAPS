package app.aaps.core.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
}
