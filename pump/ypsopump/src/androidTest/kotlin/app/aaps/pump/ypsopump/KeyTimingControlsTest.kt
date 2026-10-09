package app.aaps.pump.ypsopump

import android.widget.DatePicker
import android.widget.TimePicker
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.contrib.PickerActions
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.pump.ypsopump.compose.KeyTimingControls
import app.aaps.pump.ypsopump.compose.keyDateLabel
import app.aaps.pump.ypsopump.crypto.KeyTiming
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionJournal
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** No BLE, dosing or patient data: only synthetic advisory metadata in a test-owned journal. */
class KeyTimingControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editExpiryAndReminderThenRestartJournalAndRenderDueNotification() {
        val now = Instant.parse("2026-10-09T12:00:00Z").toEpochMilli()
        val created = Instant.parse("2026-09-10T12:00:00Z").toEpochMilli()
        val original = PumpSession.Record("synthetic", "00".repeat(32), "test", null, null, null,
            createdAt = created, importedAt = now)
        val storage = object : SessionJournal.Storage {
            var file: String? = null
            val anchors = mutableSetOf<String>()
            override fun read() = file
            override fun anchors() = anchors.toList()
            override fun create(alias: String) { anchors.add(alias) }
            override fun delete(alias: String) { anchors.remove(alias) }
            override fun seal(alias: String, body: String) = body
            override fun open(alias: String, sealed: String) = sealed
            override fun writeAndSync(value: String) { file = value }
        }
        val journal = SessionJournal(storage)
        journal.commit(PumpSession.State(listOf(original), "test"))
        var record by mutableStateOf(journal.load().records.single())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "synthetic-key-timing"
        manager.createNotificationChannel(NotificationChannel(channel, "Synthetic key reminder", NotificationManager.IMPORTANCE_DEFAULT))
        val notificationId = 211
        val notifications = mutableListOf<Pair<Long?, Boolean>>()
        val publisher = KeyExpiryNotification()
        fun notify() {
            publisher.publish(record.keyTiming.status(record.createdAt, record.importedAt, now), { manager.cancel(notificationId) },
                { date, due ->
                    notifications.add(date to due)
                    val message = context.getString(if (due) R.string.ypsopump_key_due_notification else R.string.ypsopump_key_reminder_notification, keyDateLabel(date))
                    manager.notify(notificationId, Notification.Builder(context, channel)
                        .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Synthetic YpsoPump key reminder")
                        .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message)).build())
                })
        }
        compose.setContent {
            AapsTheme {
                Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
                    KeyTimingControls(record.keyTiming.status(record.createdAt, record.importedAt, now), record.keyTiming, true) { expiry, reminder ->
                        record = record.copy(keyTiming = KeyTiming(expiry, reminder).observe(record.createdAt, record.importedAt, now))
                        journal.commit(PumpSession.State(listOf(record), "test"))
                        notify()
                    }
                }
                }
            }
        }
        compose.onNodeWithText("Source-derived: creation timestamp + 28 elapsed days").assertIsDisplayed()
        compose.onNodeWithText("Tracked expiry is due or overdue. The pump may reject remote access.").assertIsDisplayed()
        compose.runOnIdle { notify(); assertEquals(true, notifications.single().second) }
        compose.onNodeWithText("Set expiry date and time").performScrollTo().performClick()
        onView(isAssignableFrom(DatePicker::class.java)).perform(PickerActions.setDate(2026, 10, 20))
        onView(withId(android.R.id.button1)).perform(click())
        onView(isAssignableFrom(TimePicker::class.java)).perform(PickerActions.setTime(12, 0))
        onView(withId(android.R.id.button1)).perform(click())
        compose.onNodeWithText("User-entered expiry date").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Set reminder date and time").performScrollTo().performClick()
        onView(isAssignableFrom(DatePicker::class.java)).perform(PickerActions.setDate(2026, 10, 8))
        onView(withId(android.R.id.button1)).perform(click())
        onView(isAssignableFrom(TimePicker::class.java)).perform(PickerActions.setTime(12, 0))
        onView(withId(android.R.id.button1)).perform(click())
        compose.runOnIdle {
            val restarted = SessionJournal(storage).load().records.single()
            assertEquals(record, restarted)
            assertEquals(created, restarted.createdAt)
            assertTrue(restarted.keyTiming.reminderDue)
            assertFalse(restarted.keyTiming.expiryDue)
            assertEquals(false, notifications.last().second)
            // Backwards clock and fresh notification publisher must still show the due reminder.
            KeyExpiryNotification().publish(restarted.keyTiming.status(created, now, created), {},
                { date, due -> notifications.add(date to due) })
            assertEquals(false, notifications.last().second)
            record = restarted
        }
        compose.onNodeWithText("Key reminder is due.").performScrollTo().assertIsDisplayed()
        assertTrue(manager.activeNotifications.any { it.id == notificationId &&
            it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("YpsoPump key reminder:") })
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.takeScreenshot(java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "key-timing-controls.png"))
        compose.onNodeWithText("Use automatic reminder").performScrollTo().performClick()
        compose.onNodeWithText("Use original tracked expiry").performScrollTo().performClick()
        compose.onNodeWithText("Tracked expiry is due or overdue. The pump may reject remote access.").performScrollTo().assertIsDisplayed()
        assertTrue(manager.activeNotifications.any { it.id == notificationId &&
            it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("due or overdue") })
        device.openNotification()
        assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.text("Synthetic YpsoPump key reminder")), 5000))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "key-timing-notification.png"))
        device.pressBack()
        manager.cancel(notificationId)
        manager.deleteNotificationChannel(channel)
    }
}
