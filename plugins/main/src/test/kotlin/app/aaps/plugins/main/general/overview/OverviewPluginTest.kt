package app.aaps.plugins.main.general.overview

import androidx.preference.SwitchPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceGroup
import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.main.general.overview.compose.*
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.plugins.main.general.overview.notifications.NotificationStore
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

class OverviewPluginTest : TestBaseWithProfile() {

    @Mock lateinit var overviewData: OverviewData
    @Mock lateinit var notificationStore: NotificationStore
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var nsSettingsStatus: NSSettingsStatus

    private lateinit var overviewPlugin: OverviewPlugin

    private var graphValue = ""
    private var additionalValue = ""

    @BeforeEach fun prepare() {
        graphValue = ""
        additionalValue = ""
        whenever(preferences.get(StringNonKey.OverviewGlucoseGraphSettings)).thenAnswer { graphValue }
        whenever(preferences.get(StringNonKey.OverviewAdditionalGraphs)).thenAnswer { additionalValue }
        doAnswer { graphValue = it.getArgument(1); null }.whenever(preferences).put(eq(StringNonKey.OverviewGlucoseGraphSettings), any())
        doAnswer { additionalValue = it.getArgument(1); null }.whenever(preferences).put(eq(StringNonKey.OverviewAdditionalGraphs), any())
        overviewPlugin = OverviewPlugin(
            aapsLogger, rh, preferences, notificationStore, fabricPrivacy, rxBus,
            aapsSchedulers, overviewData, context, constraintsChecker, uiInteraction, nsSettingsStatus, config, activePlugin
        )
    }

    @Test
    fun preferenceScreenTest() {
        val screen = preferenceManager.createPreferenceScreen(context)
        overviewPlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }
    @Test fun `graph preferences load existing choices and preserve unrelated toggles`() {
        graphValue = HomeGraphSettings().withOverlay(GlucoseOverlay.TARGET, true).withForecast(PredictionKind.COB, true).encode()
        val screen = overviewGraphPreferences(preferenceManager, context, preferences)
        val target = screen.lookup<SwitchPreference>("overview_graph_overlay_TARGET")!!
        assertThat(target.isChecked).isTrue()
        assertThat(target.isPersistent).isFalse()
        assertThat(target.callChangeListener(false)).isTrue()
        val saved = HomeGraphSettings.decode(graphValue)
        assertThat(saved.visible(GlucoseOverlay.TARGET)).isFalse()
        assertThat(saved.forecasts).containsExactly(PredictionKind.COB)
    }

    @Test fun `graph assignment persists in the existing codec and rejects invalid entries`() {
        additionalValue = AdditionalGraphSettings.decode("IOB=1,DEVIATIONS=2").encode()
        val screen = overviewGraphPreferences(preferenceManager, context, preferences)
        val picker = screen.lookup<ListPreference>("overview_graph_panel_DEVIATIONS")!!
        assertThat(picker.value).isEqualTo("2")
        assertThat(picker.callChangeListener("4")).isTrue()
        assertThat(picker.callChangeListener("5")).isFalse()
        assertThat(picker.callChangeListener("bad")).isFalse()
        val saved = AdditionalGraphSettings.decode(additionalValue)
        assertThat(saved.graph(AdditionalSeries.DEVIATIONS)).isEqualTo(4)
        assertThat(saved.graph(AdditionalSeries.IOB)).isEqualTo(1)
    }

    @Test fun `reset updates storage and native controls without touching therapy preferences`() {
        graphValue = HomeGraphSettings().withForecast(PredictionKind.IOB, true).encode()
        additionalValue = "IOB=2"
        val screen = overviewGraphPreferences(preferenceManager, context, preferences)
        val reset = screen.lookup<Preference>("overview_graph_reset")!!
        assertThat(reset.onPreferenceClickListener!!.onPreferenceClick(reset)).isTrue()
        assertThat(HomeGraphSettings.decode(graphValue)).isEqualTo(HomeGraphSettings.decode(HomeGraphSettings().encode()))
        assertThat(screen.lookup<SwitchPreference>("overview_graph_forecast_IOB")!!.isChecked).isFalse()
        assertThat(screen.lookup<ListPreference>("overview_graph_panel_IOB")!!.value).isEqualTo("0")
    }

    @Test fun `graph screen is included when opened by its preference root key`() {
        val screen = preferenceManager.createPreferenceScreen(context)
        overviewPlugin.addPreferenceScreen(preferenceManager, screen, context, OVERVIEW_GRAPH_SETTINGS)
        assertThat(screen.lookup<PreferenceScreen>(OVERVIEW_GRAPH_SETTINGS)).isNotNull()
    }

    // Android's TextUtils.equals is a stub in these JVM tests; use Kotlin equality for tree lookup.
    private inline fun <reified T : Preference> PreferenceGroup.lookup(key: String): T? =
        allPreferences().firstOrNull { it.key == key } as? T

    private fun PreferenceGroup.allPreferences(): List<Preference> = (0 until preferenceCount).flatMap {
        val child = getPreference(it)
        listOf(child) + if (child is PreferenceGroup) child.allPreferences() else emptyList()
    }

}
