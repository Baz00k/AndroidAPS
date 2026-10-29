package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.model.GlucoseUnit
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** Simulated sheet interactions: no production persistence or pump is connected here. */
class TempTargetSheetTest {

    @get:Rule val compose = createComposeRule()

    private val presets = listOf(
        TtPreset(TtReason.EATING_SOON, "Eating soon", 90.0, 45),
        TtPreset(TtReason.ACTIVITY, "Activity", 140.0, 60),
        TtPreset(TtReason.HYPO, "Hypo", 100.0, 30)
    )

    private fun show(
        state: TempTargetSheetState = tempTargetSheetState(
            units = GlucoseUnit.MGDL,
            presets = presets,
            initialTarget = 101.0,
            initialDuration = 60,
            activeTargetMgdl = null
        ),
        onCancelActive: () -> Unit = {}
    ): MutableList<TempTargetRequest> {
        val requests = mutableListOf<TempTargetRequest>()
        compose.setContent {
            AapsTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    TempTargetSheet(state, requests::add, onCancelActive, {})
                }
            }
        }
        return requests
    }

    @Test
    fun `a preset starts exactly its configured target and duration`() {
        val requests = show()

        compose.onNodeWithText("Eating soon").performClick()
        compose.onNodeWithText("Start temporary target").assertIsEnabled().performClick()

        compose.runOnIdle {
            assertThat(requests).containsExactly(TempTargetRequest(target = 90.0, durationMin = 45, reason = TtReason.EATING_SOON))
        }
    }

    @Test
    fun `an active target can still be cancelled while the editor is out of range`() {
        var cancelled = false
        val state = tempTargetSheetState(
            units = GlucoseUnit.MGDL,
            presets = presets,
            initialTarget = 101.0,
            initialDuration = 60,
            activeTargetMgdl = 200.0
        )
        show(state) { cancelled = true }

        // A target left from before this range narrowed is shown as it is, never clamped to 180.
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("200")
        compose.onNodeWithText("Start temporary target").assertIsNotEnabled()
        compose.onNodeWithText("Cancel current temporary target").assertIsEnabled().performClick()

        compose.runOnIdle { assertThat(cancelled).isTrue() }
    }
}
