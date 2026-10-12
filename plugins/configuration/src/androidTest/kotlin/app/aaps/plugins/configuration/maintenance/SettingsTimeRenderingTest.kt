package app.aaps.plugins.configuration.maintenance

import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.maintenance.PrefMetadata
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.configuration.R
import app.aaps.plugins.configuration.maintenance.compose.PrefsFileListScreen
import app.aaps.plugins.configuration.maintenance.compose.PrefsFileRow
import app.aaps.plugins.configuration.maintenance.data.Prefs
import app.aaps.plugins.configuration.maintenance.data.PrefsStatusImpl
import app.aaps.plugins.configuration.maintenance.dialogs.PrefImportSummaryDialog
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Synthetic metadata only: no application preferences, clock changes, pump or import. */
class SettingsTimeRenderingTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun fixedWarsawAgeRendersInFileListAndWarningSummaryCanBeCancelled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rh = mock<ResourceHelper>().apply {
            whenever(gs(any())).thenAnswer { context.getString(it.arguments[0] as Int) }
            whenever(gs(any(), any<String>())).thenAnswer { context.getString(it.arguments[0] as Int, it.arguments[1]) }
            whenever(gq(any(), any(), any<Int>())).thenAnswer {
                context.resources.getQuantityString(it.arguments[0] as Int, it.arguments[1] as Int, it.arguments[2])
            }
        }
        val provider = FileListProviderImpl(rh, Lazy { mock() }, mock(), mock(), mock(), Lazy { mock() }, context, mock())
        provider.clockProvider = { Clock.fixed(Instant.parse("2026-10-25T11:00:00Z"), ZoneId.of("Europe/Warsaw")) }
        val age = provider.formatExportedAgo("2026-10-24T12:00") // 25 elapsed hours, one local day.
        val entry = PrefMetadata("2026-08-25T12:00Z", PrefsStatusImpl.OK)
        val metadata = provider.checkMetadata(mapOf(PrefsMetadataKeyImpl.CREATED_AT to entry))
        assertThat(entry.status).isEqualTo(PrefsStatusImpl.WARN)
        assertThat(entry.info).isEqualTo(context.getString(R.string.metadata_warning_old_export, "61"))
        var applied = false
        var cancelled = false
        compose.setContent {
            val activityContext: Context = LocalContext.current
            AapsTheme {
                PrefsFileListScreen("Synthetic settings", listOf(PrefsFileRow("DST fixture.json", "full", true, "test", true, age, "Isolated test")), {
                    PrefImportSummaryDialog.showSummary(ContextThemeWrapper(activityContext, app.aaps.core.ui.R.style.AppTheme), false, true, Prefs(emptyMap(), metadata), { applied = true }, { cancelled = true })
                }, {})
            }
        }
        compose.onNodeWithText(age).assertExists()
        screenshot("settings-time-list.png", context)
        compose.onNodeWithText("DST fixture.json").performClick()
        onView(withText("2026-08-25 12:00 (UTC)")).check(matches(isDisplayed()))
        onView(withText(R.string.check_preferences_import_anyway_btn)).check(matches(isDisplayed()))
        screenshot("settings-time-warning.png", context)
        onView(withText(android.R.string.cancel)).perform(click())
        compose.runOnIdle {
            assertThat(applied).isFalse()
            assertThat(cancelled).isTrue()
        }
    }

    private fun screenshot(name: String, context: Context) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.waitForIdle()
        assertThat(device.takeScreenshot(File(context.cacheDir, name))).isTrue()
    }
}
