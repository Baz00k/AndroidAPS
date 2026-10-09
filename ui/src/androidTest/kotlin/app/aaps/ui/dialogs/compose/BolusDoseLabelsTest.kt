package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.ui.activities.history.HistoryScreen
import app.aaps.ui.activities.history.HistoryUiState
import app.aaps.ui.activities.history.toHistoryItem
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

/** Simulated progress and recorded history only: no pump or production database is connected. */
class BolusDoseLabelsTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rh = Mockito.mock(ResourceHelper::class.java) { invocation ->
        context.getString(invocation.arguments[0] as Int, *invocation.arguments.drop(1).toTypedArray())
    }

    @After fun cleanup() = BolusProgressData.set(0.0, false, -1)

    @Test fun progressCompletionAndHistoryDisplayTheExactSmallDose() {
        BolusProgressData.set(0.025, false, 193)
        EventOverviewBolusProgress(rh, delivered = 0.0125, id = 193)
        var status by mutableStateOf(BolusProgressData.status)
        var percent by mutableStateOf(BolusProgressData.percent)
        var history by mutableStateOf(false)
        val row = BS(id = 193, timestamp = System.currentTimeMillis(), amount = 0.025, type = BS.Type.NORMAL).toHistoryItem("Today", "12:30", rh)!!
        compose.setContent { AapsTheme {
            if (history) HistoryScreen(HistoryUiState(loading = false, items = listOf(row)), {})
            else BolusProgressSheet(percent, status) { history = true }
        } }
        compose.onNodeWithText("Delivering 0.0125U").assertIsDisplayed()
        assertThat(BolusProgressData.wearStatus).isEqualTo("0.0125U / 0.025U delivered")
        compose.runOnIdle {
            EventOverviewBolusProgress(rh, percent = 100, id = 193)
            status = BolusProgressData.status
            percent = BolusProgressData.percent
        }
        compose.onNodeWithText("Bolus 0.025U delivered successfully").assertIsDisplayed()
        compose.onNodeWithText("Stop").performClick()
        compose.onNodeWithText("0.025 U").assertIsDisplayed()
        compose.onNodeWithText("0.03 U").assertDoesNotExist()
    }

    @Test fun translatedResourcesAcceptPrecisionPreservingDoseLabels() {
        for (language in listOf("en", "de", "pl", "cs", "fr", "ar")) {
            val locale = java.util.Locale.forLanguageTag(language)
            val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(locale) }
            val localized = context.createConfigurationContext(configuration)
            val amount = app.aaps.core.interfaces.utils.formatBolus(0.025, locale = locale)
            for (id in listOf(
                app.aaps.core.interfaces.R.string.bolus_delivering,
                app.aaps.core.interfaces.R.string.bolus_delivered_successfully,
                app.aaps.core.ui.R.string.bolus_u_min,
                app.aaps.core.ui.R.string.smb_bolus_u,
                app.aaps.core.ui.R.string.goingtodeliver,
                app.aaps.core.ui.R.string.format_insulin_units_label
            )) {
                assertThat(localized.getString(id, amount)).contains(amount)
            }
            assertThat(localized.getString(app.aaps.core.interfaces.R.string.bolus_delivered_so_far, amount, amount)).contains(amount)
            assertThat(localized.getString(app.aaps.core.ui.R.string.extended_bolus_u_min, amount, 30)).contains(amount)
        }
    }
}
