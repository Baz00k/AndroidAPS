package app.aaps.plugins.main.general.overview.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.GhostButton
import app.aaps.core.compose.components.ModalSheet
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.StatusPill
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.notifications.Notification

/*
 * Active notifications on Home: a pill in the hero's top row says how many there are and how severe
 * the worst is, and opens a sheet listing them all. Home's layout does not change with the alerts.
 *
 * Opening or closing the sheet never acts on an alert: only an alert's own button runs its action
 * (snooze, set, select...), which then removes it exactly as before.
 */

/** Most severe first (Notification.URGENT is the lowest level); alerts of one level keep their order. */
internal fun orderedAlerts(alerts: List<HomeUiState.Alert>): List<HomeUiState.Alert> = alerts.sortedBy { it.level }

/** Urgent and warning levels name themselves on the pill; lesser ones are only counted. */
private fun needsAttention(level: Int) = level == Notification.URGENT || level == Notification.NORMAL

/** "Urgent · 3", "Warning · 1", "2 alerts"; null without alerts, when there is no pill. */
internal fun alertsPillLabel(alerts: List<HomeUiState.Alert>): String? {
    val top = alerts.minOfOrNull { it.level } ?: return null
    return if (needsAttention(top)) "${severityLabel(top)} · ${alerts.size}" else "${alerts.size} ${alertsWord(alerts.size)}"
}

/** What TalkBack reads for the pill: "3 alerts, 1 urgent", so the count of urgent ones is never left to colour. */
internal fun alertsPillDescription(alerts: List<HomeUiState.Alert>): String {
    val urgent = alerts.count { it.level == Notification.URGENT }
    return "${alerts.size} ${alertsWord(alerts.size)}" + if (urgent > 0) ", $urgent urgent" else ""
}

private fun alertsWord(n: Int) = if (n == 1) "alert" else "alerts"

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
 * The alerts pill, beside the loop pill: absent without alerts, tinted by the most severe one when it
 * is urgent or a warning. Tapping it opens [AlertsSheet].
 */
@Composable
internal fun AlertsPill(alerts: List<HomeUiState.Alert>, onClick: () -> Unit) {
    val label = alertsPillLabel(alerts) ?: return
    val top = alerts.minOf { it.level }
    val tint = severityTint(top)
    val description = alertsPillDescription(alerts)
    StatusPill(
        label = label,
        modifier = Modifier.semantics { contentDescription = description },
        dotColor = tint,
        labelColor = AapsTheme.colors.textPrimary,
        containerColor = if (needsAttention(top)) tint.copy(alpha = 0.2f) else AapsTheme.colors.controlFill,
        onClickLabel = "Show alerts",
        onClick = onClick
    )
}

/**
 * Every active alert, most severe first, each with its full text, time and own action. The list
 * follows the store while the sheet is open, so several alerts can be handled in one visit; once the
 * last one is gone the sheet slides away, showing it inert on the way out.
 *
 * Every row is keyed by its alert, so a press that began on one alert's button is cancelled, not
 * delivered to another, when that alert moves, resolves or is replaced.
 */
@Composable
internal fun AlertsSheet(alerts: List<HomeUiState.Alert>, onAction: (HomeUiState.Alert) -> Unit, onDismissed: () -> Unit) {
    ModalSheet(onDismissed = onDismissed) { close ->
        var lastShown by remember { mutableStateOf(alerts) }
        SideEffect { if (alerts.isNotEmpty()) lastShown = alerts }
        val resolved = alerts.isEmpty()
        LaunchedEffect(resolved) { if (resolved) close {} }
        val rows = if (resolved) lastShown else alerts

        SheetSurface(title = "Alerts", onClose = { close {} }, scrollContent = true) {
            Column {
                orderedAlerts(rows).forEachIndexed { index, alert ->
                    key(alert.id) {
                        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(AapsTheme.colors.divider))
                        AlertRow(alert, enabled = !resolved, onAction = onAction)
                    }
                }
            }
        }
    }
}

/** One alert: severity and time over the full message, the alert's own action beside it. */
@Composable
private fun AlertRow(alert: HomeUiState.Alert, enabled: Boolean, onAction: (HomeUiState.Alert) -> Unit) {
    val colors = AapsTheme.colors
    val tint = severityTint(alert.level)
    Row(Modifier.fillMaxWidth().padding(vertical = AapsSpacing.cardPadSmall), verticalAlignment = Alignment.Top) {
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
            Text(alert.text, style = AapsTheme.type.body, color = colors.textPrimary)
        }
        GhostButton(alert.buttonText, { onAction(alert) }, enabled = enabled)
    }
}
