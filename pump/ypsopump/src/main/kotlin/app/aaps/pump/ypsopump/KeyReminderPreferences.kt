package app.aaps.pump.ypsopump

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

internal fun keyDateLabel(time: Long?): String = time?.let {
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it))
}.orEmpty()

/** Native settings. All storage work belongs to the advisory service, never SharedPreferences. */
internal class KeyReminderPreferences(
    context: Context,
    private val service: YpsoProvisioningService,
    private val publish: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : PreferenceCategory(context) {
    private var scope: CoroutineScope? = null
    private var installed: YpsoProvisioningService.InstalledSession? = null
    private val expiry = ListPreference(context).apply {
        key = "ypsopump_key_expiry"
        order = 1
        title = context.getString(R.string.ypsopump_key_expiry_setting)
        isPersistent = false
        entries = arrayOf(context.getString(R.string.ypsopump_key_expiry_auto), context.getString(R.string.ypsopump_key_custom_date))
        entryValues = arrayOf("auto", "custom")
        setOnPreferenceChangeListener { _, choice ->
            if (choice == "auto") save(true, null) else chooseDate(true)
            false
        }
    }
    private val reminder = ListPreference(context).apply {
        key = "ypsopump_key_reminder"
        order = 0
        title = context.getString(R.string.ypsopump_key_reminder_setting)
        isPersistent = false
        entries = arrayOf(context.getString(R.string.ypsopump_key_reminder_default), context.getString(R.string.ypsopump_key_custom_date))
        entryValues = arrayOf("auto", "custom")
        setOnPreferenceChangeListener { _, choice ->
            if (choice == "auto") save(false, null) else chooseDate(false)
            false
        }
    }

    init {
        key = "ypsopump_key_reminders"
        order = 1
        title = context.getString(R.string.ypsopump_key_reminders)
    }

    fun addTo(parent: PreferenceScreen) {
        parent.addPreference(this)
        addPreference(reminder)
        addPreference(expiry)
        addPreference(Preference(context).apply {
            order = 3
            isSelectable = false
            summary = context.getString(R.string.ypsopump_key_dates_help)
        })
        reminder.isEnabled = false
        expiry.isEnabled = false
    }

    override fun onAttached() {
        super.onAttached()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        refresh()
    }

    override fun onDetached() {
        scope?.cancel()
        scope = null
        super.onDetached()
    }

    private fun refresh() {
        scope?.launch {
            val result = withContext(Dispatchers.IO) { runCatching { service.observeKeyTiming() to service.installed() } }
            result.onSuccess { (status, record) ->
                installed = record
                reminder.isEnabled = record != null && status != null
                expiry.isEnabled = reminder.isEnabled
                reminder.value = if (record?.keyTiming?.reminderOverride == null) "auto" else "custom"
                expiry.value = if (record?.keyTiming?.expiryOverride == null) "auto" else "custom"
                reminder.summary = when {
                    record == null -> context.getString(R.string.ypsopump_key_connect_first)
                    record.keyTiming.reminderOverride != null -> context.getString(R.string.ypsopump_key_custom_summary, keyDateLabel(status?.reminderAt))
                    else -> context.getString(R.string.ypsopump_key_reminder_default)
                }
                expiry.summary = when {
                    status?.expiresAt == null -> context.getString(R.string.ypsopump_key_unknown)
                    status.origin == app.aaps.pump.ypsopump.crypto.KeyTiming.Origin.IMPORT_ESTIMATE ->
                        context.getString(R.string.ypsopump_key_estimated_date, keyDateLabel(status.expiresAt))
                    record?.keyTiming?.expiryOverride != null -> context.getString(R.string.ypsopump_key_custom_summary, keyDateLabel(status.expiresAt))
                    else -> keyDateLabel(status.expiresAt)
                }
            }.onFailure {
                reminder.summary = context.getString(R.string.ypsopump_key_load_failed)
                expiry.summary = context.getString(R.string.ypsopump_key_load_failed)
                onFailure(it)
            }
        }
    }

    private fun save(expiryField: Boolean, value: Long?) {
        val generation = installed?.generation ?: return
        reminder.isEnabled = false
        expiry.isEnabled = false
        scope?.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (expiryField) service.setKeyExpiry(generation, value) else service.setKeyReminder(generation, value)
                }
            }
            result.onFailure {
                onFailure(it)
                ToastUtils.warnToast(context, context.getString(R.string.ypsopump_key_dates_failed))
            }
            if (result.isSuccess) ToastUtils.okToast(context, context.getString(R.string.ypsopump_key_saved))
            refresh()
            withContext(Dispatchers.IO) { runCatching(publish).onFailure(onFailure) }
        }
    }

    private fun chooseDate(expiryField: Boolean) {
        val record = installed ?: return
        val status = record.keyTiming.status(record.createdAt?.toEpochMilli(), record.importedAt?.toEpochMilli(), System.currentTimeMillis())
        val initial = if (expiryField) status.expiresAt else status.reminderAt
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(initial ?: System.currentTimeMillis()).atZone(zone)
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                save(expiryField, java.time.LocalDateTime.of(year, month + 1, day, hour, minute).atZone(zone).toInstant().toEpochMilli())
            }, start.hour, start.minute, DateFormat.is24HourFormat(context)).show()
        }, start.year, start.monthValue - 1, start.dayOfMonth).apply {
            setTitle(context.getString(if (expiryField) R.string.ypsopump_key_expiry_setting else R.string.ypsopump_key_reminder_setting))
        }.show()
    }
}
