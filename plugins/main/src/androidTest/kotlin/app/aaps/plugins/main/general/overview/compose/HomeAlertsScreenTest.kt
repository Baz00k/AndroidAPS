package app.aaps.plugins.main.general.overview.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.notifications.Notification
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Standalone display test: no app database, notification store or pump access. */
class HomeAlertsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val acted = mutableListOf<Int>()

    // Real-length hero labels, so the top row is as full as it gets on a phone.
    private val state = mutableStateOf(
        HomeUiState(bg = "120", ready = true, loopStateLabel = "Closed loop", loopSubLabel = "· 4m ago", timeAgo = "2 min ago")
    )

    private fun alert(id: Int, level: Int, text: String = "Alert text $id") = HomeUiState.Alert(id = id, text = text, time = "12:0$id", level = level, buttonText = "Act $id")

    private val opensAlerts = SemanticsMatcher("opens the alerts sheet") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Show alerts" }

    private fun show(vararg alerts: HomeUiState.Alert) {
        state.value = state.value.copy(notifications = alerts.toList())
        compose.setContent {
            AapsTheme {
                // A narrow phone: 360 dp is the smallest common portrait width.
                Box(Modifier.width(360.dp)) {
                    // An action resolves its alert, as the notification store does.
                    HomeScreen(state.value, HomeActions(onDismissAlert = { alert ->
                        acted += alert.id
                        state.value = state.value.copy(notifications = state.value.notifications.filter { it.id != alert.id })
                    }), graph = { Box(Modifier.fillMaxWidth().height(240.dp)) })
                }
            }
        }
    }

    private fun update(vararg alerts: HomeUiState.Alert) = compose.runOnIdle { state.value = state.value.copy(notifications = alerts.toList()) }

    private fun openAlerts() {
        compose.onNode(opensAlerts).performClick()
        compose.onNodeWithText("Alerts").assertIsDisplayed()
    }

    @Test fun alertsArrivingAndResolvingDoNotMoveGlucoseOrShowTheirText() {
        show()
        val before = compose.onNodeWithText("120").fetchSemanticsNode().boundsInRoot

        update(alert(1, Notification.URGENT, "Application needs location permission for BT scan and WiFi identification"), alert(2, Notification.NORMAL))
        compose.onNodeWithText("1 urgent · +1").assertIsDisplayed()
        compose.onNodeWithContentDescription("2 alerts: 1 urgent, 1 warning").assertIsDisplayed()
        compose.onAllNodesWithText("Application needs", substring = true).assertCountEquals(0)
        assertEquals(before, compose.onNodeWithText("120").fetchSemanticsNode().boundsInRoot)

        update()
        compose.onAllNodes(opensAlerts).assertCountEquals(0)
        assertEquals(before, compose.onNodeWithText("120").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun theSheetShowsEveryAlertInFullMostSevereFirst() {
        val long = List(30) { "Long urgent message that runs past the bottom of the screen." }.joinToString(" ")
        show(alert(1, Notification.LOW), alert(2, Notification.URGENT, long), alert(3, Notification.NORMAL))
        openAlerts()

        val tops = listOf(long, "Alert text 3", "Alert text 1").map { compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }
        assertEquals(tops.sorted(), tops)
        compose.onNodeWithText("Urgent · 12:02").assertIsDisplayed()
        // The end of a message taller than the screen, and the alerts below it, can be scrolled to and acted on.
        compose.onNodeWithText("Act 1").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(1), acted)
    }

    @Test fun openingAndClosingTheSheetDoesNotActOnAnyAlert() {
        show(alert(1, Notification.NORMAL), alert(2, Notification.URGENT))
        openAlerts()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onAllNodesWithText("Alerts").assertCountEquals(0)

        openAlerts()
        pressBack()
        compose.onAllNodesWithText("Alerts").assertCountEquals(0)

        assertEquals(emptyList<Int>(), acted)
        compose.onNodeWithText("1 urgent · +1").assertIsDisplayed()
    }

    @Test fun theSheetStaysOpenAndFollowsTheListAsAlertsAreHandled() {
        show(alert(1, Notification.INFO), alert(2, Notification.NORMAL), alert(3, Notification.LOW))
        openAlerts()

        // An urgent alert arrives and another resolves while the sheet is open.
        update(alert(1, Notification.INFO), alert(3, Notification.LOW), alert(4, Notification.URGENT))
        compose.onAllNodesWithText("Alert text 2").assertCountEquals(0)
        compose.onNodeWithText("Act 3").performClick()
        compose.onAllNodesWithText("Alert text 3").assertCountEquals(0)
        compose.onNodeWithText("Act 1").performClick()

        compose.onNodeWithText("Alerts").assertIsDisplayed()
        compose.onNodeWithText("Alert text 4").assertIsDisplayed()
        assertEquals(listOf(3, 1), acted)
    }

    @Test fun aPressInTheSheetIsDroppedWhenItsAlertResolvesAndAnotherTakesItsPlace() {
        show(alert(1, Notification.URGENT), alert(2, Notification.LOW), alert(3, Notification.LOW))
        openAlerts()
        compose.onNodeWithText("Act 2").performTouchInput { down(center) } // second row

        // Alert 2 resolves while the finger is down on its button, and a new warning takes the second row.
        update(alert(1, Notification.URGENT), alert(3, Notification.LOW), alert(4, Notification.NORMAL))
        compose.onNodeWithText("Act 4").performTouchInput { up() }

        assertEquals(emptyList<Int>(), acted)
    }

    @Test fun theSheetClosesOnceTheLastAlertIsGoneWithItsRowInertOnTheWayOut() {
        show(alert(1, Notification.URGENT))
        openAlerts()
        compose.mainClock.autoAdvance = false
        update()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Act 1").assertIsNotEnabled()
        compose.mainClock.autoAdvance = true

        compose.waitForIdle()
        compose.onAllNodesWithText("Alerts").assertCountEquals(0)
        compose.onAllNodes(opensAlerts).assertCountEquals(0)
    }
}
