package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.interfaces.notifications.Notification
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HomeAlertsTest {

    private fun alerts(vararg levels: Int) = levels.mapIndexed { id, level -> HomeUiState.Alert(id = id, text = "alert $id", time = "12:00", level = level, buttonText = "Snooze") }

    private val urgent = Notification.URGENT
    private val warning = Notification.NORMAL
    private val notice = Notification.LOW
    private val info = Notification.INFO

    @Test fun `the pill counts the most severe level by name and the rest after it`() {
        assertThat(alertsPillLabel(alerts())).isNull()
        assertThat(alertsPillLabel(alerts(urgent))).isEqualTo("1 urgent")
        assertThat(alertsPillLabel(alerts(info, warning, notice, urgent))).isEqualTo("1 urgent · +3")
        assertThat(alertsPillLabel(alerts(notice, warning, warning))).isEqualTo("2 warnings · +1")
        assertThat(alertsPillLabel(alerts(info))).isEqualTo("1 alert")
        assertThat(alertsPillLabel(alerts(notice, Notification.ANNOUNCEMENT))).isEqualTo("2 alerts")
    }

    @Test fun `a second urgent alert changes the pill even when the total stays the same`() {
        assertThat(alertsPillLabel(alerts(urgent, info, info))).isNotEqualTo(alertsPillLabel(alerts(urgent, urgent, info)))
    }

    @Test fun `TalkBack hears the urgent and warning counts`() {
        assertThat(alertsPillDescription(alerts(urgent, warning, urgent, info, notice))).isEqualTo("5 alerts: 2 urgent, 1 warning")
        assertThat(alertsPillDescription(alerts(warning, warning))).isEqualTo("2 alerts: 2 warnings")
        assertThat(alertsPillDescription(alerts(info, notice))).isEqualTo("2 alerts")
    }

    @Test fun `the list is ordered by severity and keeps arrival order within a level`() {
        val ordered = orderedAlerts(alerts(notice, urgent, notice, warning, urgent))
        assertThat(ordered.map { it.id }).containsExactly(1, 4, 3, 0, 2).inOrder()
    }
}
