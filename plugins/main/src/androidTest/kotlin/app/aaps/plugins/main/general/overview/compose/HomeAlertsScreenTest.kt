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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.notifications.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Standalone display test: no app database, notification store or pump access. */
class HomeAlertsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val acted = mutableListOf<Int>()
    private val state = mutableStateOf(HomeUiState(bg = "120", ready = true))

    private fun alert(id: Int, level: Int, text: String = "Alert text $id") = HomeUiState.Alert(id = id, text = text, time = "12:0$id", level = level, buttonText = "Act $id")

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

    private fun openAlerts(description: String) {
        compose.onNodeWithContentDescription(description).performClick()
        compose.onNodeWithText("Alerts").assertIsDisplayed()
    }

    @Test fun withoutAlertsThereIsNoPill() {
        show()
        compose.onAllNodesWithText("alert", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Urgent", substring = true).assertCountEquals(0)
    }

    @Test fun manyAlertsAreOnePillNamedByTheMostSevere() {
        show(alert(1, Notification.INFO), alert(2, Notification.NORMAL), alert(3, Notification.URGENT), alert(4, Notification.LOW), alert(5, Notification.URGENT))

        compose.onNodeWithText("Urgent · 5").assertIsDisplayed()
        compose.onNodeWithContentDescription("5 alerts, 2 urgent").assertIsDisplayed()
        for (id in 1..5) compose.onAllNodesWithText("Alert text $id").assertCountEquals(0)
        compose.onNodeWithText("120").assertIsDisplayed()
    }

    @Test fun alertsArrivingAndResolvingDoNotMoveGlucose() {
        show()
        val before = compose.onNodeWithText("120").fetchSemanticsNode().boundsInRoot

        update(alert(1, Notification.URGENT, "Application needs location permission for BT scan and WiFi identification"), alert(2, Notification.NORMAL))
        compose.onNodeWithText("Urgent · 2").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("120").fetchSemanticsNode().boundsInRoot)

        update()
        assertEquals(before, compose.onNodeWithText("120").fetchSemanticsNode().boundsInRoot)
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

    @Test fun theSheetListsEveryAlertMostSevereFirst() {
        show(alert(1, Notification.LOW), alert(2, Notification.URGENT), alert(3, Notification.NORMAL))
        openAlerts("3 alerts, 1 urgent")

        val tops = listOf(2, 3, 1).map { compose.onNodeWithText("Alert text $it").fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        compose.onNodeWithText("Urgent · 12:02").assertIsDisplayed()
        compose.onNodeWithText("Warning · 12:03").assertIsDisplayed()
        compose.onNodeWithText("Notice · 12:01").assertIsDisplayed()
    }

    @Test fun aSingleLongAlertIsShownInFull() {
        val long = List(8) { "Long urgent message with more than three lines." }.joinToString(" ")
        show(alert(1, Notification.URGENT, long))
        compose.onAllNodesWithText(long).assertCountEquals(0)
        openAlerts("1 alert, 1 urgent")

        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(long).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertFalse(layout.single().hasVisualOverflow)
    }

    @Test fun openingAndClosingTheSheetDoesNotActOnAnyAlert() {
        show(alert(1, Notification.NORMAL), alert(2, Notification.URGENT))
        openAlerts("2 alerts, 1 urgent")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onAllNodesWithText("Alerts").assertCountEquals(0)

        openAlerts("2 alerts, 1 urgent")
        pressBack()
        compose.onAllNodesWithText("Alerts").assertCountEquals(0)

        assertEquals(emptyList<Int>(), acted)
        compose.onNodeWithText("Urgent · 2").assertIsDisplayed()
    }

    @Test fun actionsInTheSheetFollowTheirAlertWhileTheListChanges() {
        show(alert(1, Notification.INFO), alert(2, Notification.NORMAL), alert(3, Notification.LOW))
        openAlerts("3 alerts")

        // An urgent alert arrives and another resolves while the sheet is open.
        update(alert(1, Notification.INFO), alert(3, Notification.LOW), alert(4, Notification.URGENT))
        compose.onAllNodesWithText("Alert text 2").assertCountEquals(0)
        compose.onNodeWithText("Act 4").assertIsDisplayed()
        compose.onNodeWithText("Act 3").performClick()
        compose.onNodeWithText("Act 1").performClick()

        // The sheet stays open, so several alerts can be handled in one visit.
        compose.onNodeWithText("Alerts").assertIsDisplayed()
        assertEquals(listOf(3, 1), acted)
    }

    @Test fun aPressInTheSheetIsDroppedWhenItsAlertResolvesAndAnotherTakesItsPlace() {
        show(alert(1, Notification.URGENT), alert(2, Notification.LOW), alert(3, Notification.LOW))
        openAlerts("3 alerts, 1 urgent")
        compose.onNodeWithText("Act 2").performTouchInput { down(center) } // second row

        // Alert 2 resolves while the finger is down on its button, and a new warning takes the second row.
        update(alert(1, Notification.URGENT), alert(3, Notification.LOW), alert(4, Notification.NORMAL))
        compose.onNodeWithText("Act 4").performTouchInput { up() }

        assertEquals(emptyList<Int>(), acted)
    }

    @Test fun theSheetClosesOnceTheLastAlertIsGoneAndCannotActOnTheWayOut() {
        show(alert(1, Notification.URGENT))
        openAlerts("1 alert, 1 urgent")
        compose.mainClock.autoAdvance = false
        update()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Act 1").assertIsNotEnabled()
        compose.mainClock.autoAdvance = true

        compose.waitForIdle()
        compose.onAllNodesWithText("Alerts").assertCountEquals(0)
        compose.onAllNodesWithText("Urgent", substring = true).assertCountEquals(0)
        assertEquals(emptyList<Int>(), acted)
    }
}
