package app.aaps.plugins.main.general.overview.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.GhostButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.Tag
import app.aaps.core.compose.icons.AapsIcons
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.notifications.Notification

/*
 * Active notifications on Home. However many there are, Home shows one summary card: the most severe
 * alert with its own action, and how many more are waiting (urgent ones counted on their own). The
 * full list is a sheet. Glucose and the graph stay on screen whether there is one alert or ten.
 *
 * Opening or closing the list never acts on an alert: only an alert's own button runs its action
 * (snooze, set, select...), which then removes it exactly as before.
 */

/** The bounded summary: the alert shown in full and what else is waiting behind it. */
internal data class AlertSummary(
    val top: HomeUiState.Alert,
    /** Other urgent alerts, which must not be hidden behind a plain count. */
    val moreUrgent: Int,
    val moreOther: Int
) {

    val more get() = moreUrgent + moreOther
}

/** Most severe first (Notification.URGENT is the lowest level); alerts of one level keep their order. */
internal fun orderedAlerts(alerts: List<HomeUiState.Alert>): List<HomeUiState.Alert> = alerts.sortedBy { it.level }

internal fun alertSummary(alerts: List<HomeUiState.Alert>): AlertSummary? {
    val ordered = orderedAlerts(alerts)
    val top = ordered.firstOrNull() ?: return null
    val urgent = ordered.drop(1).count { it.level == Notification.URGENT }
    return AlertSummary(top, moreUrgent = urgent, moreOther = ordered.size - 1 - urgent)
}

/** "1 more urgent alert · 2 other", "3 more alerts"; null when the summary is the only alert. */
internal fun moreAlertsLabel(summary: AlertSummary): String? = when {
    summary.more == 0       -> null
    summary.moreUrgent == 0 -> "${summary.more} more ${alerts(summary.more)}"
    summary.moreOther == 0  -> "${summary.moreUrgent} more urgent ${alerts(summary.moreUrgent)}"
    else                    -> "${summary.moreUrgent} more urgent ${alerts(summary.moreUrgent)} · ${summary.moreOther} other"
}

private fun alerts(n: Int) = if (n == 1) "alert" else "alerts"

/** Severity in words, so it is never carried by colour alone. */
internal fun severityLabel(level: Int): String = when (level) {
    Notification.URGENT       -> "Urgent"
    Notification.NORMAL       -> "Warning"
    Notification.LOW          -> "Notice"
    Notification.INFO         -> "Info"
    Notification.ANNOUNCEMENT -> "Announcement"
    else                      -> "Alert"
}

@Composable
private fun severityTint(level: Int): Color {
    val colors = AapsTheme.colors
    return when (level) {
        Notification.URGENT -> colors.low
        Notification.NORMAL -> colors.high
        Notification.LOW    -> colors.inRange
        else                -> colors.accent
    }
}

/**
 * The one alert card above the hero. The card opens the full list; the button is the shown alert's
 * own action. Keyed by the alert, so a press that began on one alert's button is cancelled, not
 * delivered to another, when a more severe alert takes its place.
 */
@Composable
internal fun AlertsSummary(alerts: List<HomeUiState.Alert>, onAction: (HomeUiState.Alert) -> Unit, onOpenList: () -> Unit) {
    val summary = alertSummary(alerts) ?: return
    val more = moreAlertsLabel(summary)
    key(summary.top.id) {
        AapsCard(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AapsTheme.shape.cardSmall)
                .clickable(role = Role.Button, onClickLabel = "Show all alerts", onClick = onOpenList),
            shape = AapsTheme.shape.cardSmall
        ) {
            AlertBody(summary.top, maxLines = 3) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (more != null) {
                        val urgent = summary.moreUrgent > 0
                        Text(
                            more,
                            style = AapsTheme.type.label,
                            color = if (urgent) AapsTheme.colors.low else AapsTheme.colors.textSecondary,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(AapsIcons.ChevronRight, contentDescription = null, tint = AapsTheme.colors.textTertiary, modifier = Modifier.size(18.dp))
                    }
                }
                GhostButton(summary.top.buttonText, { onAction(summary.top) })
            }
        }
    }
}

/** Every active alert, most severe first, each with its full text and own action. */
@Composable
internal fun AlertsSheet(alerts: List<HomeUiState.Alert>, onAction: (HomeUiState.Alert) -> Unit, onClose: () -> Unit) {
    HomeSheet(onClose) { close ->
        SheetSurface(title = "Active alerts", onClose = { close {} }, scrollContent = true) {
            orderedAlerts(alerts).forEach { alert ->
                key(alert.id) {
                    AapsCard(Modifier.fillMaxWidth(), shape = AapsTheme.shape.cardSmall) {
                        AlertBody(alert, maxLines = Int.MAX_VALUE) {
                            Spacer(Modifier.weight(1f))
                            GhostButton(alert.buttonText, { onAction(alert) })
                        }
                    }
                }
            }
        }
    }
}

/** Severity and time, the message, then [footer]; a severity-coloured rule runs down the left edge. */
@Composable
private fun AlertBody(alert: HomeUiState.Alert, maxLines: Int, footer: @Composable RowScope.() -> Unit) {
    val colors = AapsTheme.colors
    val tint = severityTint(alert.level)
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val width = 3.dp.toPx()
                drawRoundRect(tint, size = Size(width, size.height), cornerRadius = CornerRadius(width / 2))
            }
            .padding(start = 13.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(severityLabel(alert.level), tint = tint)
            Text(alert.time, style = AapsTheme.type.caption, color = colors.textTertiary)
        }
        Text(alert.text, style = AapsTheme.type.body, color = colors.textPrimary, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = footer)
    }
}
