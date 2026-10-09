package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.interfaces.notifications.Notification
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HomeAlertsTest {

    private fun alert(id: Int, level: Int) = HomeUiState.Alert(id = id, text = "alert $id", time = "12:00", level = level, buttonText = "Snooze")

    @Test fun `no alerts gives no summary`() {
        assertThat(alertSummary(emptyList())).isNull()
    }

    @Test fun `a single alert is shown alone`() {
        val summary = alertSummary(listOf(alert(1, Notification.INFO)))!!
        assertThat(summary.top.id).isEqualTo(1)
        assertThat(moreAlertsLabel(summary)).isNull()
    }

    @Test fun `an urgent alert is shown ahead of setup notices that arrived before it`() {
        val summary = alertSummary(
            listOf(alert(1, Notification.INFO), alert(2, Notification.NORMAL), alert(3, Notification.LOW), alert(4, Notification.URGENT))
        )!!
        assertThat(summary.top.id).isEqualTo(4)
        assertThat(moreAlertsLabel(summary)).isEqualTo("3 more alerts")
    }

    @Test fun `other urgent alerts are counted by name, not folded into the total`() {
        val summary = alertSummary(
            listOf(alert(1, Notification.INFO), alert(2, Notification.URGENT), alert(3, Notification.URGENT), alert(4, Notification.ANNOUNCEMENT))
        )!!
        // The first urgent alert to arrive stays on top; a later one does not displace it.
        assertThat(summary.top.id).isEqualTo(2)
        assertThat(summary.moreUrgent).isEqualTo(1)
        assertThat(summary.moreOther).isEqualTo(2)
        assertThat(moreAlertsLabel(summary)).isEqualTo("1 more urgent alert · 2 other")
    }

    @Test fun `only urgent alerts behind the shown one`() {
        val summary = alertSummary(List(3) { alert(it, Notification.URGENT) })!!
        assertThat(moreAlertsLabel(summary)).isEqualTo("2 more urgent alerts")
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
