package app.aaps.plugins.main.general.overview

import android.content.Context
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.overview.compose.*

internal const val OVERVIEW_GRAPH_SETTINGS = "overview_graph_settings"

/** Native preference tree, backed by the existing codecs so moving the UI loses no saved choices. */
internal fun overviewGraphPreferences(manager: PreferenceManager, context: Context, preferences: Preferences): PreferenceScreen =
    manager.createPreferenceScreen(context).apply {
        key = OVERVIEW_GRAPH_SETTINGS
        title = context.getString(R.string.overview_graph_settings)
        fun graphSettings() = HomeGraphSettings.decode(preferences.get(StringNonKey.OverviewGlucoseGraphSettings))
        fun additionalSettings() = AdditionalGraphSettings.decode(preferences.get(StringNonKey.OverviewAdditionalGraphs))
        fun save(value: HomeGraphSettings) = preferences.put(StringNonKey.OverviewGlucoseGraphSettings, value.encode())
        fun saveAdditional(value: AdditionalGraphSettings) = preferences.put(StringNonKey.OverviewAdditionalGraphs, value.encode())
        val resets = mutableListOf<() -> Unit>()
        fun category(label: Int) = PreferenceCategory(context).also { it.setTitle(label); addPreference(it) }
        fun toggle(parent: PreferenceCategory, id: String, label: Int, read: () -> Boolean, write: (Boolean) -> Unit) {
            parent.addPreference(SwitchPreference(context).apply {
                key = id
                isPersistent = false // The encoded graph settings remain the sole source of truth.
                setTitle(label)
                isChecked = read()
                setOnPreferenceChangeListener { _, value -> write(value as Boolean); true }
                resets += { isChecked = read() }
            })
        }
        val overlays = category(R.string.overview_graph_overlays)
        GlucoseOverlay.entries.forEach { kind ->
            toggle(overlays, "overview_graph_overlay_${kind.name}", kind.labelResource(),
                   { graphSettings().visible(kind) }, { save(graphSettings().withOverlay(kind, it)) })
        }
        val forecasts = category(R.string.overview_show_predictions)
        PredictionKind.entries.forEach { kind ->
            toggle(forecasts, "overview_graph_forecast_${kind.name}", kind.labelResource(),
                   { kind in graphSettings().forecasts }, { save(graphSettings().withForecast(kind, it)) })
        }
        val additional = category(R.string.overview_additional_graphs)
        AdditionalSeries.entries.forEach { kind ->
            additional.addPreference(ListPreference(context).apply {
                key = "overview_graph_panel_${kind.name}"
                isPersistent = false
                setTitle(kind.labelResource())
                dialogTitle = title
                entries = (listOf(context.getString(R.string.overview_graph_hidden)) +
                    (1..4).map { context.getString(R.string.overview_graph_number, it) }).toTypedArray()
                entryValues = (0..4).map { it.toString() }.toTypedArray()
                value = additionalSettings().graph(kind).toString()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                setOnPreferenceChangeListener { _, value ->
                    val graph = (value as? String)?.toIntOrNull()
                    if (graph == null || graph !in 0..4) false
                    else { saveAdditional(additionalSettings().withGraph(kind, graph)); true }
                }
                resets += { value = additionalSettings().graph(kind).toString() }
            })
        }
        addPreference(Preference(context).apply {
            key = "overview_graph_reset"
            setTitle(R.string.overview_graph_reset)
            setOnPreferenceClickListener {
                save(HomeGraphSettings())
                saveAdditional(AdditionalGraphSettings.decode(""))
                resets.forEach { it() }
                true
            }
        })
    }
