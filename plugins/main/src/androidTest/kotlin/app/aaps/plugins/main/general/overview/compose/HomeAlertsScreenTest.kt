package app.aaps.plugins.main.general.overview.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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

    private fun alert(id: Int, level: Int) = HomeUiState.Alert(id = id, text = "Alert text $id", time = "12:0$id", level = level, buttonText = "Act $id")

    private val openList = SemanticsMatcher("opens the alert list") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "Show all alerts"
    }

    private fun show(vararg alerts: HomeUiState.Alert) {
        state.value = state.value.copy(notifications = alerts.toList())
        compose.setContent {
            AapsTheme { HomeScreen(state.value, HomeActions(onDismissAlert = { acted += it.id }), graph = {}) }
        }
    }

    private fun update(vararg alerts: HomeUiState.Alert) = compose.runOnIdle { state.value = state.value.copy(notifications = alerts.toList()) }

    @Test fun manyAlertsTakeOneCardHeadedByTheMostSevere() {
        show(alert(1, Notification.INFO), alert(2, Notification.NORMAL), alert(3, Notification.URGENT), alert(4, Notification.LOW), alert(5, Notification.URGENT))

        compose.onNodeWithText("Alert text 3").assertExists()
        compose.onNodeWithText("Urgent").assertExists()
        compose.onNodeWithText("1 more urgent alert · 3 other").assertExists()
        for (id in listOf(1, 2, 4, 5)) compose.onAllNodesWithText("Alert text $id").assertCountEquals(0)
        compose.onNodeWithText("120").assertExists()
    }

    @Test fun openingAndClosingTheListDoesNotActOnAnyAlert() {
        show(alert(1, Notification.NORMAL), alert(2, Notification.URGENT))

        compose.onNode(openList).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Active alerts").assertExists()
        compose.onAllNodesWithText("Alert text 1").assertCountEquals(1)
        compose.onAllNodesWithText("Warning").assertCountEquals(1)
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onAllNodesWithText("Active alerts").assertCountEquals(0)

        assertEquals(emptyList<Int>(), acted)
        compose.onNodeWithText("Alert text 2").assertExists()
    }

    @Test fun actionsInTheListFollowTheirAlertWhileItChanges() {
        show(alert(1, Notification.INFO), alert(2, Notification.NORMAL), alert(3, Notification.LOW))
        compose.onNode(openList).performSemanticsAction(SemanticsActions.OnClick)

        // An urgent alert arrives and another resolves while the list is open.
        update(alert(1, Notification.INFO), alert(3, Notification.LOW), alert(4, Notification.URGENT))
        compose.onAllNodesWithText("Alert text 2").assertCountEquals(0)
        compose.onAllNodesWithText("Act 4").assertCountEquals(2) // summary card and list
        compose.onNodeWithText("Act 3").performClick()
        compose.onNodeWithText("Act 1").performClick()

        assertEquals(listOf(3, 1), acted)
    }

    @Test fun theListClosesWithItsLastAlertAndDoesNotReopenForTheNext() {
        show(alert(1, Notification.NORMAL))
        compose.onNode(openList).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Active alerts").assertExists()

        update()
        compose.onAllNodesWithText("Active alerts").assertCountEquals(0)
        update(alert(2, Notification.URGENT))
        compose.onNodeWithText("Alert text 2").assertExists()
        compose.onAllNodesWithText("Active alerts").assertCountEquals(0)
    }
}
