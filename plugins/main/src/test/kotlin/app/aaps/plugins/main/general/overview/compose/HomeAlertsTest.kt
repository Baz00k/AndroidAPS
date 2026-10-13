package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.interfaces.notifications.Notification
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HomeAlertsTest {

    private fun alert(id: Int, level: Int) = HomeUiState.Alert(id = id, text = "alert $id", time = "12:00", level = level, buttonText = "Snooze")

    @Test fun `no alerts gives no pill`() {
        assertThat(alertsPillLabel(emptyList())).isNull()
    }

    @Test fun `an urgent alert names the pill ahead of setup notices that arrived before it`() {
        val alerts = listOf(alert(1, Notification.INFO), alert(2, Notification.NORMAL), alert(3, Notification.LOW), alert(4, Notification.URGENT))
        assertThat(alertsPillLabel(alerts)).isEqualTo("Urgent · 4")
        assertThat(alertsPillDescription(alerts)).isEqualTo("4 alerts, 1 urgent")
    }

    @Test fun `a warning names the pill when nothing is urgent`() {
        val alerts = listOf(alert(1, Notification.LOW), alert(2, Notification.NORMAL))
        assertThat(alertsPillLabel(alerts)).isEqualTo("Warning · 2")
        assertThat(alertsPillDescription(alerts)).isEqualTo("2 alerts")
    }

    @Test fun `lesser alerts are only counted`() {
        assertThat(alertsPillLabel(listOf(alert(1, Notification.INFO)))).isEqualTo("1 alert")
        assertThat(alertsPillLabel(listOf(alert(1, Notification.LOW), alert(2, Notification.ANNOUNCEMENT)))).isEqualTo("2 alerts")
    }

    @Test fun `every urgent alert is counted for TalkBack`() {
        val alerts = List(3) { alert(it, Notification.URGENT) } + alert(3, Notification.INFO)
        assertThat(alertsPillDescription(alerts)).isEqualTo("4 alerts, 3 urgent")
    }

    @Test fun `the list is ordered by severity and keeps arrival order within a level`() {
        val ordered = orderedAlerts(
            listOf(alert(1, Notification.LOW), alert(2, Notification.URGENT), alert(3, Notification.LOW), alert(4, Notification.NORMAL), alert(5, Notification.URGENT))
        )
        assertThat(ordered.map { it.id }).containsExactly(2, 5, 4, 1, 3).inOrder()
    }

    @Test fun `every level is named`() {
        assertThat(
            listOf(Notification.URGENT, Notification.NORMAL, Notification.LOW, Notification.INFO, Notification.ANNOUNCEMENT).map(::severityLabel)
        ).containsExactly("Urgent", "Warning", "Notice", "Info", "Announcement").inOrder()
    }
}
