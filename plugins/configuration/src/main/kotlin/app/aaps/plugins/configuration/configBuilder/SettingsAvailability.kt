package app.aaps.plugins.configuration.configBuilder

import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription

/**
 * Whether opening this plugin's settings shows anything. The preferences screen builds only the
 * settings of an enabled plugin, and in simple mode only of one that is visible there (a development
 * build shows them all). For any other plugin it ends up with no screen at all and fails, so Config
 * Builder must not offer a row that opens it. Keep in step with `MyPreferenceFragment`.
 */
internal fun PluginBase.hasSettingsToOpen(simpleMode: Boolean, isDev: Boolean): Boolean =
    preferencesId != PluginDescription.PREFERENCE_NONE &&
        isEnabled() &&
        (!simpleMode || pluginDescription.preferencesVisibleInSimpleMode || isDev)
