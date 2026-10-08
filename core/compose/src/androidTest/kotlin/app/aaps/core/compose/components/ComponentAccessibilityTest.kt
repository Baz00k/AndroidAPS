package app.aaps.core.compose.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * The accessibility contract in Controls.kt, checked through the semantics tree TalkBack reads:
 * one node and one action per control, the right role and state, and passive things with no action.
 */
class ComponentAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun show(content: @Composable () -> Unit) = compose.setContent { AapsTheme { Column { content() } } }

    @Test
    fun toggleRowIsOneSwitchNamedByItsTitle() {
        var changes = 0
        show {
            var on by remember { mutableStateOf(false) }
            ToggleRow("Remind me to eat", on, { on = it; changes++ }, sub = "In 15 minutes")
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        val row = compose.onNode(role(Role.Switch))
        row.assert(hasText("Remind me to eat")).assert(hasText("In 15 minutes")).assertIsOff()
        row.performClick()
        row.assertIsOn()
        assertThat(changes).isEqualTo(1)
    }

    @Test
    fun checkboxAndRadioRowsAreOneControlEach() {
        var checked = false
        var picked = false
        show {
            CheckboxRow("Insulin cartridge change", checked, { checked = it })
            RadioRow("Closed loop", false, { picked = true })
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        compose.onNode(role(Role.Checkbox)).assert(hasText("Insulin cartridge change")).performClick()
        compose.onNode(role(Role.RadioButton)).assert(hasText("Closed loop")).assertIsNotSelected().performClick()
        assertThat(checked).isTrue()
        assertThat(picked).isTrue()
    }

    @Test
    fun aDisabledRowIsAnnouncedAsDisabledAndIgnoresTaps() {
        var changes = 0
        show { CheckboxRow("Fixed plugin", true, { changes++ }, enabled = false) }
        compose.onNode(role(Role.Checkbox)).assertIsNotEnabled().performClick()
        assertThat(changes).isEqualTo(0)
    }

    @Test
    fun aReadOnlyRowAnnouncesItsStateWithoutBeingAnActionOrDisabled() {
        // An answered exam question: it cannot change, but it is information to read, not an unavailable option.
        var changes = 0
        show { CheckboxRow("Insulin acts for about 5 h", true, { changes++ }, readOnly = true) }
        compose.onNodeWithText("Insulin acts for about 5 h")
            .assert(hasNoClickAction())
            .assertIsEnabled()
            .assertIsOn()
            .performClick()
        assertThat(changes).isEqualTo(0)
    }

    @Test
    fun aChoiceIsASelectableOptionAndAnActionChipIsNot() {
        show {
            Choice("Eating soon", selected = true, onClick = {})
            Choice("Activity", selected = false, onClick = {})
            ActionChip("+5 g", onClick = {}, clickLabel = "Add 5 grams of carbs")
        }
        compose.onNodeWithText("Eating soon").assert(role(Role.RadioButton)).assertIsSelected()
        compose.onNodeWithText("Activity").assertIsNotSelected()
        compose.onNodeWithText("+5 g")
            .assert(role(Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    @Test
    fun aTagIsNeitherAnActionNorADisabledOne() {
        show { Tag("Glucose > 180") }
        compose.onNodeWithText("Glucose > 180").assert(hasNoClickAction()).assertIsEnabled()
    }

    @Test
    fun everyButtonChipAndSegmentIsAtLeastATouchTargetTall() {
        show {
            PrimaryButton("Primary", {})
            SecondaryButton("Secondary", {})
            TonalButton("Tonal", {})
            DangerButton("Danger", {})
            GhostButton("Ghost", {})
            Choice("Choice", selected = false, onClick = {})
            ActionChip("Action", {})
            RoundIconButton(Icons.Rounded.Add, "Icon", {})
            SegmentedControl(listOf("3h", "6h"), selectedIndex = 0, onSelect = {})
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(10)
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().indices.forEach {
            compose.onAllNodes(hasClickAction())[it].assertHeightIsAtLeast(AapsSpacing.minTap)
        }
    }

    @Test
    fun aChipInAScrollingRowIsSizedToItsLabel() {
        // An unbounded row once squeezed the label into the chip's minimum width, shrinking it to fit.
        // The label is wider than that minimum, so a chip sized to it must be wider too.
        show {
            Row(Modifier.horizontalScroll(rememberScrollState())) { ActionChip("30 minutes ago", {}) }
        }
        val label = compose.onNodeWithText("30 minutes ago").fetchSemanticsNode()
        assertThat(label.size.width).isGreaterThan(with(compose.density) { AapsSpacing.minTap.roundToPx() })
    }

    @Test
    fun aDisabledButtonIsAnnouncedAsDisabledAndIgnoresTaps() {
        var clicks = 0
        show {
            SecondaryButton("Refresh", { clicks++ }, enabled = false)
            DangerButton("Stop", { clicks++ }, enabled = false)
        }
        compose.onAllNodes(role(Role.Button)).assertCountEquals(2)
        listOf("Refresh", "Stop").forEach { compose.onNodeWithText(it).assertIsNotEnabled().performClick() }
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun aTextFieldCarriesItsLabelAndError() {
        show {
            var name by remember { mutableStateOf("") }
            LabeledTextField("Name", name, { name = it }, error = "Enter a name")
        }
        compose.onNode(hasSetTextAction())
            .assert(hasText("NAME"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Enter a name"))
    }

    @Test
    fun aSectionLabelIsAHeading() {
        show { SectionLabel("Suspend loop") }
        compose.onNodeWithText("SUSPEND LOOP").assert(isHeading())
    }
}
