package app.aaps.pump.ypsopump.compose

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.GhostButton
import app.aaps.core.compose.components.SecondaryButton
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.pump.ypsopump.R
import app.aaps.pump.ypsopump.crypto.KeyTiming
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun keyDateLabel(time: Long?): String = time?.let {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
}.orEmpty()

/** Date and time are chosen in the displayed local zone, then stored as an absolute instant. */
private fun chooseKeyDate(context: Context, initial: Long?, onSelected: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(initial ?: System.currentTimeMillis()).atZone(zone)
    DatePickerDialog(context, { _, year, month, day ->
        TimePickerDialog(context, { _, hour, minute ->
            onSelected(java.time.LocalDateTime.of(year, month + 1, day, hour, minute).atZone(zone).toInstant().toEpochMilli())
        }, start.hour, start.minute, DateFormat.is24HourFormat(context)).show()
    }, start.year, start.monthValue - 1, start.dayOfMonth).show()
}

/** Controls edit installed-key metadata only; credential save/verification is a separate action. */
@Composable
internal fun KeyTimingControls(
    status: KeyTiming.Status,
    timing: KeyTiming,
    enabled: Boolean,
    onDates: (expiry: Long?, reminder: Long?) -> Unit,
) {
    val context = LocalContext.current
    val colors = AapsTheme.colors
    fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    val origin = when (status.origin) {
        KeyTiming.Origin.SOURCE -> R.string.ypsopump_key_source
        KeyTiming.Origin.IMPORT_ESTIMATE -> R.string.ypsopump_key_estimate
        KeyTiming.Origin.USER -> R.string.ypsopump_key_user_date
        KeyTiming.Origin.UNKNOWN -> R.string.ypsopump_key_unknown
    }
    val remainingMinutes = status.remainingMs?.let { it / 60_000 + if (it % 60_000 > 0) 1 else 0 }
    AapsCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text(R.string.ypsopump_key_timing), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(text(origin), color = colors.textSecondary)
            Text(status.expiresAt?.let { text(R.string.ypsopump_key_deadline, keyDateLabel(it)) }
                ?: text(R.string.ypsopump_key_unknown), color = colors.textPrimary)
            Text(when {
                status.expiryDue -> text(R.string.ypsopump_key_due)
                remainingMinutes != null -> text(R.string.ypsopump_key_remaining, remainingMinutes / 60, remainingMinutes % 60)
                else -> text(R.string.ypsopump_key_unknown)
            }, color = if (status.expiryDue) MaterialTheme.colorScheme.error else colors.textPrimary)
            Text(status.reminderAt?.let { text(
                if (timing.reminderOverride != null) R.string.ypsopump_key_reminder_user else R.string.ypsopump_key_reminder_auto,
                keyDateLabel(it)) } ?: text(R.string.ypsopump_key_reminder_unknown), color = colors.textSecondary)
            if (status.reminderDue) Text(text(R.string.ypsopump_key_reminder_due), color = MaterialTheme.colorScheme.error)
            Text(text(R.string.ypsopump_key_advisory), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            SecondaryButton(text(R.string.ypsopump_key_set_expiry), onClick = {
                chooseKeyDate(context, status.expiresAt) { onDates(it, timing.reminderOverride) }
            }, enabled = enabled)
            if (timing.expiryOverride != null) GhostButton(text(R.string.ypsopump_key_reset_expiry), onClick = {
                onDates(null, timing.reminderOverride)
            }, enabled = enabled)
            SecondaryButton(text(R.string.ypsopump_key_set_reminder), onClick = {
                chooseKeyDate(context, status.reminderAt) { onDates(timing.expiryOverride, it) }
            }, enabled = enabled)
            if (timing.reminderOverride != null) GhostButton(text(R.string.ypsopump_key_reset_reminder), onClick = {
                onDates(timing.expiryOverride, null)
            }, enabled = enabled)
        }
    }
}
