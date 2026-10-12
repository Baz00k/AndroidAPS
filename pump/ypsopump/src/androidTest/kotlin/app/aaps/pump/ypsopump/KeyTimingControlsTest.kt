package app.aaps.pump.ypsopump

import android.os.Bundle
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.contrib.PickerActions
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.provisioning.KeyTimingRepository
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Test-owned native preferences; the session store rejects all therapy writes. */
class KeyPreferencesTestActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, KeyPreferencesTestFragment()).commitNow()
    }
}

class KeyPreferencesTestFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(state: Bundle?, root: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        val settings = KeyReminderPreferences(requireContext(), KeyTimingControlsTest.service, {}, { throw AssertionError(it) })
        settings.addTo(preferenceScreen)
        // The production toggle uses AAPS's adaptive preference; this isolated host has no DI.
        settings.addPreference(androidx.preference.SwitchPreferenceCompat(requireContext()).apply {
            title = getString(R.string.ypsopump_key_show_overview)
            order = 2
            isPersistent = false
            isChecked = false
        })
    }
}

class KeyTimingControlsTest {
    companion object { lateinit var service: YpsoProvisioningService }

    private fun owner(): PumpSession {
        val record = PumpSession.Record("synthetic", "00".repeat(32), "test", null, null, null,
            createdAt = System.currentTimeMillis() - 10 * 86_400_000L, importedAt = System.currentTimeMillis())
        return PumpSession(object : PumpSession.Store {
            override fun load() = PumpSession.State(listOf(record), "test")
            override fun commit(state: PumpSession.State) { error("Reminder UI must never write therapy storage") }
        })
    }

    @Test fun nativeAutomaticDefaultAndIntentionalCustomDatePersistAcrossRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.noBackupFilesDir, "synthetic-native-key-${System.nanoTime()}.json")
        val owner = owner()
        fun restart() = YpsoProvisioningService(owner, YpsoPumpState(), keyDates = KeyTimingRepository(KeyTimingRepository.FileStore(file)))
        service = restart()
        try {
            ActivityScenario.launch(KeyPreferencesTestActivity::class.java).use {
                val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
                assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By.text("Automatic · 3 days before expiry")), 5000))
                assertNull(service.installed()!!.keyTiming.reminderOverride)
                device.takeScreenshot(File(context.getExternalFilesDir(null), "key-preferences-automatic.png"))
                onView(withText("Key reminder")).perform(click())
                onView(withText("Custom date…")).perform(click())
                onView(withId(android.R.id.button2)).perform(click())
                assertNull(service.installed()!!.keyTiming.reminderOverride)
                onView(withText("Key reminder")).perform(click())
                onView(withText("Custom date…")).perform(click())
                onView(isAssignableFrom(DatePicker::class.java)).perform(PickerActions.setDate(2026, 12, 20))
                onView(withId(android.R.id.button1)).perform(click())
                onView(isAssignableFrom(TimePicker::class.java)).perform(PickerActions.setTime(12, 0))
                onView(withId(android.R.id.button1)).perform(click())
                assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By.textContains("Dec 20")), 5000))
                val saved = service.installed()!!.keyTiming.reminderOverride
                assertNotNull(saved)
                val restored = restart().apply { observeKeyTiming() }
                assertEquals(saved, restored.installed()!!.keyTiming.reminderOverride)
                device.takeScreenshot(File(context.getExternalFilesDir(null), "key-preferences-custom.png"))
                onView(withText("Key reminder")).perform(click())
                onView(withText("Automatic · 3 days before expiry")).perform(click())
                assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By.text("Automatic · 3 days before expiry")), 5000))
                assertNull(service.installed()!!.keyTiming.reminderOverride)
            }
        } finally { file.delete(); File(file.path + ".new").delete() }
    }

    @Test fun corruptAdvisoryStorageLeavesTherapyOwnershipUntouched() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "synthetic-corrupt-${System.nanoTime()}.json")
        try {
            file.writeText("invalid")
            val owner = owner()
            val before = owner.committedRecord()
            val service = YpsoProvisioningService(owner, YpsoPumpState(), keyDates = KeyTimingRepository(KeyTimingRepository.FileStore(file)))
            assertNotNull(service.installed())
            assertTrue(runCatching { service.observeKeyTiming() }.isFailure)
            assertEquals(before, owner.committedRecord())
        } finally { file.delete() }
    }
}
