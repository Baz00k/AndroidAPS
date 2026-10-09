package app.aaps.configuration.configBuilder

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.plugins.configuration.configBuilder.hasSettingsToOpen
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import app.aaps.core.interfaces.resources.ResourceHelper

class SettingsAvailabilityTest : TestBase() {

    private fun plugin(preferencesId: Int, enabled: Boolean, visibleInSimpleMode: Boolean = true): PluginBase {
        val description = PluginDescription()
            .mainType(PluginType.GENERAL)
            .pluginName(1)
            .preferencesId(preferencesId)
            .preferencesVisibleInSimpleMode(visibleInSimpleMode)
        return object : PluginBase(description, aapsLogger, mock(ResourceHelper::class.java)) {
            override fun isEnabled() = enabled
        }
    }

    @Test fun onlyAnEnabledPluginWithSettingsOpensThem() {
        assertThat(plugin(preferencesId = 1, enabled = true).hasSettingsToOpen(simpleMode = false, isDev = false)).isTrue()
        assertThat(plugin(preferencesId = PluginDescription.PREFERENCE_SCREEN, enabled = true).hasSettingsToOpen(simpleMode = false, isDev = false)).isTrue()
        // A disabled plugin contributes nothing to the preferences screen, which then has no screen to show.
        assertThat(plugin(preferencesId = 1, enabled = false).hasSettingsToOpen(simpleMode = false, isDev = false)).isFalse()
        assertThat(plugin(preferencesId = PluginDescription.PREFERENCE_NONE, enabled = true).hasSettingsToOpen(simpleMode = false, isDev = false)).isFalse()
    }

    @Test fun simpleModeHidesSettingsThatAreNotVisibleThereExceptInADevelopmentBuild() {
        val hidden = plugin(preferencesId = 1, enabled = true, visibleInSimpleMode = false)
        assertThat(hidden.hasSettingsToOpen(simpleMode = true, isDev = false)).isFalse()
        assertThat(hidden.hasSettingsToOpen(simpleMode = true, isDev = true)).isTrue()
        assertThat(hidden.hasSettingsToOpen(simpleMode = false, isDev = false)).isTrue()
        assertThat(plugin(preferencesId = 1, enabled = true).hasSettingsToOpen(simpleMode = true, isDev = false)).isTrue()
    }
}
