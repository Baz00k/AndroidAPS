package app.aaps.plugins.main.general.overview.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
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
import app.aaps.core.compose.components.ListCard
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.Tag
import app.aaps.core.compose.icons.AapsIcons
import app.aaps.core.compose.theme.AapsSpacing
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

/** "3 more · 1 urgent", "2 more urgent", "3 more"; null when the summary is the only alert. */
internal fun moreAlertsLabel(summary: AlertSummary): String? = when {
    summary.more == 0       -> null
    summary.moreUrgent == 0 -> "${summary.more} more"
    summary.moreOther == 0  -> "${summary.moreUrgent} more urgent"
    else                    -> "${summary.more} more · ${summary.moreUrgent} urgent"
}

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
                .clip(AapsTheme.shape.card)
                .clickable(role = Role.Button, onClickLabel = "Show all alerts", onClick = onOpenList),
            contentPadding = PaddingValues(start = AapsSpacing.cardPadSmall, end = 4.dp, top = AapsSpacing.cardPadSmall, bottom = AapsSpacing.cardPadSmall)
        ) {
            AlertRow(summary.top, maxLines = 3, onAction = onAction) {
                if (more != null)
                    Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                        Text(more, style = AapsTheme.type.label, color = if (summary.moreUrgent > 0) AapsTheme.colors.low else AapsTheme.colors.textSecondary)
                        Icon(AapsIcons.ChevronRight, contentDescription = null, tint = AapsTheme.colors.textTertiary, modifier = Modifier.size(18.dp))
                    }
            }
        }
    }
}

/** Every active alert, most severe first, each with its full text and own action. */
@Composable
internal fun AlertsSheet(alerts: List<HomeUiState.Alert>, onAction: (HomeUiState.Alert) -> Unit, onClose: () -> Unit) {
    HomeSheet(onClose) { close ->
        SheetSurface(title = "Active alerts", onClose = { close {} }, scrollContent = true) {
            ListCard(Modifier.fillMaxWidth()) {
                orderedAlerts(alerts).forEach { alert ->
                    key(alert.id) {
                        Box(Modifier.padding(start = AapsSpacing.cardPad, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                            AlertRow(alert, maxLines = Int.MAX_VALUE, onAction = onAction)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Severity, time and [heading] extras over the message, with the alert's action beside it. A
 * severity-coloured rule runs down the left edge.
 */
@Composable
private fun AlertRow(
    alert: HomeUiState.Alert,
    maxLines: Int,
    onAction: (HomeUiState.Alert) -> Unit,
    heading: @Composable FlowRowScope.() -> Unit = {}
) {
    val colors = AapsTheme.colors
    val tint = severityTint(alert.level)
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val width = 3.dp.toPx()
                drawRoundRect(tint, size = Size(width, size.height), cornerRadius = CornerRadius(width / 2))
            }
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Wraps, so a long count drops to its own line at a large font instead of being squeezed.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Tag(severityLabel(alert.level), Modifier.align(Alignment.CenterVertically), tint = tint)
                Text(alert.time, style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.align(Alignment.CenterVertically))
                heading()
            }
            Text(alert.text, style = AapsTheme.type.body, color = colors.textPrimary, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
        GhostButton(alert.buttonText, { onAction(alert) })
    }
}
