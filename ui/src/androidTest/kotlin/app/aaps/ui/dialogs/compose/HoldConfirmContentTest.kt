package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme
import org.junit.Rule
import org.junit.Test

class HoldConfirmContentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aLongDeliverySummaryKeepsHoldAndCancelOnScreen() {
        // A window shorter than the summary, as on a small phone at 200% font with constraint warnings.
        compose.setContent {
            AapsTheme {
                Box(Modifier.height(400.dp)) {
                    HoldConfirmContent(
                        title = "Insulin",
                        message = List(40) { "Summary line ${it + 1}" }.joinToString("\n"),
                        action = "Deliver 2.00 U",
                        onConfirm = {},
                        onCancel = {}
                    )
                }
            }
        }
        compose.onNodeWithText("Deliver 2.00 U").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
