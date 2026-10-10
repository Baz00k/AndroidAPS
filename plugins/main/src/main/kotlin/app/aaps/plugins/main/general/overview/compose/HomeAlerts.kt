package app.aaps.plugins.main.general.overview.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.GhostButton
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.notifications.Notification

/*
 * Active notifications on Home, as a stack: folded to one card however many there are, so glucose
 * and the graph stay on screen, and unfolded in place to show every alert.
 *
 * Folding or unfolding never acts on an alert: only an alert's own button runs its action (snooze,
 * set, select...), which then removes it exactly as before.
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
 * The alerts above the hero, as a stack. Folded, it is one card, the most severe alert with its own
 * action, with the edges of the others showing beneath it and a line saying how many wait there.
 * Tapping the card unfolds the stack in place into one card per alert; "Show less" folds it again,
 * as does the stack shrinking to a single alert.
 *
 * Every card is keyed by its alert, so a press that began on one alert's button is cancelled, not
 * delivered to another, when that alert moves, resolves or is replaced.
 */
@Composable
internal fun AlertsStack(alerts: List<HomeUiState.Alert>, onAction: (HomeUiState.Alert) -> Unit) {
    val summary = alertSummary(alerts) ?: return
    val more = moreAlertsLabel(summary)
    var unfolded by rememberSaveable { mutableStateOf(false) }
    val canUnfold = summary.more > 0
    // Folds once a single alert is left, so the next one to arrive joins a folded stack.
    LaunchedEffect(canUnfold) { if (!canUnfold) unfolded = false }
    val toggle = { unfolded = !unfolded }

    Column(Modifier.animateContentSize()) {
        AnimatedVisibility(unfolded, enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().padding(start = AapsSpacing.cardPad), verticalAlignment = Alignment.CenterVertically) {
                Text("${alerts.size} alerts", style = AapsTheme.type.label, color = AapsTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                GhostButton("Show less", toggle)
            }
        }
        orderedAlerts(alerts).forEachIndexed { index, alert ->
            key(alert.id) {
                if (index == 0) {
                    AlertCard(
                        alert,
                        maxLines = if (unfolded) Int.MAX_VALUE else 3,
                        onAction = onAction,
                        onClick = if (canUnfold) toggle else null,
                        onClickLabel = if (unfolded) "Show fewer alerts" else "Show all alerts",
                        footer = if (unfolded || more == null) null else ({ MoreLine(more, urgent = summary.moreUrgent > 0) })
                    )
                } else {
                    AnimatedVisibility(unfolded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        AlertCard(alert, maxLines = Int.MAX_VALUE, onAction = onAction, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        AnimatedVisibility(!unfolded && summary.more > 0, enter = fadeIn(), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StackEdge(inset = 12.dp, alpha = 0.75f)
                if (summary.more > 1) StackEdge(inset = 24.dp, alpha = 0.45f)
            }
        }
    }
}

/** "5 more alerts · 1 urgent": in the urgent colour when any waiting alert is urgent, as the count must not hide one. */
@Composable
private fun MoreLine(label: String, urgent: Boolean) =
    Text(label, style = AapsTheme.type.caption, color = if (urgent) AapsTheme.colors.low else AapsTheme.colors.textSecondary)

/** The bottom edge of a card stacked under the folded one. */
@Composable
private fun StackEdge(inset: Dp, alpha: Float) =
    Box(
        Modifier
            .padding(horizontal = inset)
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
            .background(AapsTheme.colors.surface.copy(alpha = alpha))
    )

/** One alert: severity and time over the message, the alert's own action beside it. */
@Composable
private fun AlertCard(
    alert: HomeUiState.Alert,
    maxLines: Int,
    onAction: (HomeUiState.Alert) -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    footer: (@Composable () -> Unit)? = null
) {
    val colors = AapsTheme.colors
    val tint = severityTint(alert.level)
    AapsCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(AapsTheme.shape.card)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else Modifier),
        contentPadding = PaddingValues(start = AapsSpacing.cardPad, end = 4.dp, top = AapsSpacing.cardPadSmall, bottom = AapsSpacing.cardPadSmall)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Severity in words beside its colour, so it is never carried by colour alone.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = tint)) { append(severityLabel(alert.level)) }
                            append(" · ${alert.time}")
                        },
                        style = AapsTheme.type.label, color = colors.textTertiary
                    )
                }
                Text(alert.text, style = AapsTheme.type.body, color = colors.textPrimary, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
                footer?.invoke()
            }
            GhostButton(alert.buttonText, { onAction(alert) })
        }
    }
}
