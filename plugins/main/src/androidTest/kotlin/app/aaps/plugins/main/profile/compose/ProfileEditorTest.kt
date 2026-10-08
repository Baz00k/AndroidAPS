package app.aaps.plugins.main.profile.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.aaps.core.compose.theme.AapsTheme
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProfileEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingProfilesDiscardsRejectedDraftEvenWhenStoredValuesAreEqual() {
        var selected by mutableIntStateOf(0)
        val callbacks = mock<ProfileEditorCallbacks>()
        doAnswer { selected = it.getArgument(0); null }.whenever(callbacks).onSelectProfile(any())
        val limits = CategoryConstraints(1.0, 30.0, step = 1.0, decimals = 1, unitLabel = "mmol/L")
        val block = listOf(EditableBlock(0, 0, "00:00", 10.0))
        compose.setContent { AapsTheme {
            ProfileEditor(
                ProfileEditState(
                    loading = false, profileNames = listOf("A", "B"), selectedProfileIndex = selected,
                    basal = block, isf = block, basalC = limits,
                    isfC = if (selected == 0) limits else limits.copy(max1 = 300.0, unitLabel = "mg/dL"),
                    icC = limits, targetC = limits
                ), callbacks, {}, {}
            )
        } }
        compose.onNodeWithText("ISF").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("10.0")).performScrollTo().performTextReplacement("100")
        compose.onNodeWithText("B").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("10.0")).performScrollTo().assertTextEquals("10.0")
        compose.runOnIdle { verify(callbacks, never()).onValue1(any(), any(), any()) }
    }
}
