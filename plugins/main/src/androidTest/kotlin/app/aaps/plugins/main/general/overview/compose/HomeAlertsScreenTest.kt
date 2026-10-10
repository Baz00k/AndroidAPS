package app.aaps.plugins.main.general.overview.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.notifications.Notification
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Standalone display test: no app database, notification store or pump access. */
class HomeAlertsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val acted = mutableListOf<Int>()
    private val state = mutableStateOf(HomeUiState(bg = "120", ready = true))

    private fun alert(id: Int, level: Int, text: String = "Alert text $id") = HomeUiState.Alert(id = id, text = text, time = "12:0$id", level = level, buttonText = "Act $id")

    private val unfold = SemanticsMatcher("unfolds the alert stack") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "Show all alerts"
    }

    private fun show(vararg alerts: HomeUiState.Alert) {
        state.value = state.value.copy(notifications = alerts.toList())
        compose.setContent {
            AapsTheme {
                // Stands in for the GraphView, at roughly its height on a phone.
                HomeScreen(state.value, HomeActions(onDismissAlert = { acted += it.id }), graph = {
                    Box(Modifier.fillMaxWidth().height(240.dp)) { Box(Modifier.align(Alignment.Center).size(1.dp).testTag("graph middle")) }
                })
            }
        }
    }

    private fun update(vararg alerts: HomeUiState.Alert) = compose.runOnIdle { state.value = state.value.copy(notifications = alerts.toList()) }

    @Test fun manyAlertsTakeOneCardHeadedByTheMostSevere() {
        show(alert(1, Notification.INFO), alert(2, Notification.NORMAL), alert(3, Notification.URGENT), alert(4, Notification.LOW), alert(5, Notification.URGENT))

        compose.onNodeWithText("Alert text 3").assertExists()
        compose.onNodeWithText("Urgent · 12:03").assertExists()
        compose.onNodeWithText("4 more alerts · 1 urgent").assertExists()
        for (id in listOf(1, 2, 4, 5)) compose.onAllNodesWithText("Alert text $id").assertCountEquals(0)
        compose.onNodeWithText("120").assertExists()
    }

    @Test fun glucoseAndTheGraphStayOnScreenUnderTheIssuesFiveAlerts() {
        // The five alerts from #197, as the app raises them.
        show(
            alert(1, Notification.URGENT, "Application needs location permission for BT scan and WiFi identification"),
            alert(2, Notification.NORMAL, "Running dev version. Closed loop is disabled."),
            alert(3, Notification.NORMAL, "Master password not set"),
            alert(4, Notification.LOW, "AAPS directory not selected"),
            alert(5, Notification.INFO, "Identification not set in dev mode")
        )

        compose.onNodeWithText("120").assertIsDisplayed()
        compose.onNodeWithTag("graph middle").assertIsDisplayed()
    }

    @Test fun unfoldingAndFoldingTheStackDoesNotActOnAnyAlert() {
        show(alert(1, Notification.NORMAL), alert(2, Notification.URGENT))
        compose.onAllNodesWithText("Alert text 1").assertCountEquals(0)

        compose.onNode(unfold).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Alert text 1").assertIsDisplayed()
        compose.onNodeWithText("Warning · 12:01").assertIsDisplayed()
        compose.onNodeWithText("Show less").performClick()
        compose.onAllNodesWithText("Alert text 1").assertCountEquals(0)

        assertEquals(emptyList<Int>(), acted)
        compose.onNodeWithText("Alert text 2").assertExists()
    }

    @Test fun actionsInTheUnfoldedStackFollowTheirAlertWhileItChanges() {
        show(alert(1, Notification.INFO), alert(2, Notification.NORMAL), alert(3, Notification.LOW))
        compose.onNode(unfold).performSemanticsAction(SemanticsActions.OnClick)

        // An urgent alert arrives and another resolves while the stack is unfolded.
        update(alert(1, Notification.INFO), alert(3, Notification.LOW), alert(4, Notification.URGENT))
        compose.onAllNodesWithText("Alert text 2").assertCountEquals(0)
        compose.onNodeWithText("Act 4").assertIsDisplayed()
        compose.onNodeWithText("Act 3").performClick()
        compose.onNodeWithText("Act 1").performClick()

        assertEquals(listOf(3, 1), acted)
    }

    @Test fun aPressOnTheSummaryIsDroppedWhenAnotherAlertTakesItsPlace() {
        show(alert(1, Notification.NORMAL))
        compose.onNodeWithText("Act 1").performTouchInput { down(center) }

        // An urgent alert takes the summary while the finger is still down on alert 1's button.
        update(alert(1, Notification.NORMAL), alert(2, Notification.URGENT))
        compose.onNodeWithText("Act 2").performTouchInput { up() }
        assertEquals(emptyList<Int>(), acted)

        compose.onNodeWithText("Act 2").performClick()
        assertEquals(listOf(2), acted)
    }

    @Test fun aPressInTheUnfoldedStackIsDroppedWhenItsAlertResolvesAndAnotherTakesItsPlace() {
        show(alert(1, Notification.URGENT), alert(2, Notification.LOW), alert(3, Notification.LOW))
        compose.onNode(unfold).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Act 2").performTouchInput { down(center) } // second card

        // Alert 2 resolves while the finger is down on its button, and a new warning takes the second card.
        update(alert(1, Notification.URGENT), alert(3, Notification.LOW), alert(4, Notification.NORMAL))
        compose.onNodeWithText("Act 4").performTouchInput { up() }

        assertEquals(emptyList<Int>(), acted)
    }

    @Test fun theStackFoldsWhenOneAlertIsLeftAndStaysFoldedForTheNext() {
        show(alert(1, Notification.NORMAL), alert(2, Notification.LOW))
        compose.onNode(unfold).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Show less").assertExists()

        update(alert(1, Notification.NORMAL))
        compose.onAllNodesWithText("Show less").assertCountEquals(0)
        update(alert(1, Notification.NORMAL), alert(3, Notification.LOW))
        compose.onAllNodesWithText("Alert text 3").assertCountEquals(0)
        compose.onNodeWithText("1 more alert").assertExists()
    }
}
