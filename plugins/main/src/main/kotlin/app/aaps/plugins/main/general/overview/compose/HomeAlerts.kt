package app.aaps.plugins.main.general.overview.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.Dp
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

/** "5 more alerts · 1 urgent", "2 more urgent alerts", "1 more alert"; null when the summary is the only alert. */
internal fun moreAlertsLabel(summary: AlertSummary): String? = when {
    summary.more == 0       -> null
    summary.moreUrgent == 0 -> "${summary.more} more ${alerts(summary.more)}"
    summary.moreOther == 0  -> "${summary.moreUrgent} more urgent ${alerts(summary.moreUrgent)}"
    else                    -> "${summary.more} more ${alerts(summary.more)} · ${summary.moreUrgent} urgent"
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
 *
 * Alerts waiting behind it must not be missed, so they are shown twice: a pill naming how many (in
 * the urgent colour when any of them is urgent), and the edges of the cards stacked under this one.
 */
@Composable
internal fun AlertsSummary(alerts: List<HomeUiState.Alert>, onAction: (HomeUiState.Alert) -> Unit, onOpenList: () -> Unit) {
    val summary = alertSummary(alerts) ?: return
    val more = moreAlertsLabel(summary)
    key(summary.top.id) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AapsCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AapsTheme.shape.card)
                    .clickable(role = Role.Button, onClickLabel = "Show all alerts", onClick = onOpenList),
                contentPadding = PaddingValues(start = AapsSpacing.cardPadSmall, end = 4.dp, top = AapsSpacing.cardPadSmall, bottom = AapsSpacing.cardPadSmall)
            ) {
                // The time gives way to the count here; every alert's time is in the list.
                AlertRow(summary.top, maxLines = 3, onAction = onAction, showTime = more == null) {
                    if (more != null) MorePill(more, urgent = summary.moreUrgent > 0, Modifier.align(Alignment.CenterVertically))
                }
            }
            if (summary.more > 0) StackEdge(inset = 10.dp, alpha = 1f)
            if (summary.more > 1) StackEdge(inset = 22.dp, alpha = 0.6f)
        }
    }
}

@Composable
private fun MorePill(label: String, urgent: Boolean, modifier: Modifier) {
    val tint = if (urgent) AapsTheme.colors.low else AapsTheme.colors.accent
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp)) // a rounded rectangle, so a wrapped label at a large font still reads as one tag
            .background(tint.copy(alpha = 0.14f))
            .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = AapsTheme.type.label, color = tint)
        Icon(AapsIcons.ChevronRight, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** The bottom edge of a card stacked under the summary. */
@Composable
private fun StackEdge(inset: Dp, alpha: Float) =
    Box(
        Modifier
            .padding(horizontal = inset)
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .background(AapsTheme.colors.surface.copy(alpha = alpha))
    )

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
 * Severity, time (unless [showTime] is off) and [heading] extras over the message, with the alert's action beside it. A
 * severity-coloured rule runs down the left edge.
 */
@Composable
private fun AlertRow(
    alert: HomeUiState.Alert,
    maxLines: Int,
    onAction: (HomeUiState.Alert) -> Unit,
    showTime: Boolean = true,
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
                if (showTime) Text(alert.time, style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.align(Alignment.CenterVertically))
                heading()
            }
            Text(alert.text, style = AapsTheme.type.body, color = colors.textPrimary, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
        GhostButton(alert.buttonText, { onAction(alert) })
    }
}
