package app.aaps.activities.compose

import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.ListPreference
import androidx.preference.SwitchPreference
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PreferenceScreenComposeTest : TestBaseWithProfile() {

    @Test fun `nested graph groups have no stacked headings and cannot absorb parent siblings`() {
        fun leaf(title: String) = Preference(context).apply { this.title = title; isVisible = true }
        fun category(title: String) = PreferenceCategory(context).apply { this.title = title; isVisible = true }
        val root = preferenceManager.createPreferenceScreen(context)
        val overview = category("Overview")
        root.addPreference(overview)
        val graphs = preferenceManager.createPreferenceScreen(context).apply { title = "Graph settings"; isVisible = true }
        overview.addPreference(graphs)
        val overlays = category("Glucose overlays").apply { order = 0 }
        graphs.addPreference(overlays)
        overlays.addPreference(leaf("Target"))
        val additional = category("Additional graphs").apply { order = 1 }
        graphs.addPreference(additional)
        additional.addPreference(leaf("Sensitivity"))
        graphs.addPreference(leaf("Reset defaults").apply { order = 2 })
        overview.addPreference(leaf("Keep screen on"))

        val rows = flattenPreferences(root)
        assertThat(rows.map {
            when (it) {
                is PrefRow.Section -> "heading:${it.title}"
                is PrefRow.Leaf -> it.preference.title.toString()
                PrefRow.CardBreak -> "break"
            }
        }).containsExactly("heading:Glucose overlays", "Target", "heading:Additional graphs", "Sensitivity",
                           "break", "Reset defaults", "heading:Overview", "Keep screen on").inOrder()
    }

    @Test fun `simple nested screens retain their heading and hidden groups remain hidden`() {
        val root = preferenceManager.createPreferenceScreen(context)
        val nested = preferenceManager.createPreferenceScreen(context).apply { title = "Sound"; isVisible = true }
        root.addPreference(nested)
        nested.addPreference(Preference(context).apply { title = "Volume"; isVisible = true })
        val hidden = PreferenceCategory(context).apply { title = "Hidden"; isVisible = false }
        root.addPreference(hidden)
        hidden.addPreference(Preference(context).apply { title = "Hidden setting"; isVisible = true })
        val rows = flattenPreferences(root)
        assertThat(rows).hasSize(2)
        assertThat(rows.first()).isEqualTo(PrefRow.Section("Sound"))
        assertThat((rows.last() as PrefRow.Leaf).preference.title).isEqualTo("Volume")
    }

    @Test fun `flattening snapshots native switches so reset invalidates Compose rows`() {
        val screen = preferenceManager.createPreferenceScreen(context)
        val toggle = SwitchPreference(context).apply { key = "graph_toggle"; isPersistent = false; isVisible = true; isChecked = true }
        screen.addPreference(toggle)
        val before = flattenPreferences(screen)
        toggle.isChecked = false
        val after = flattenPreferences(screen)
        assertThat(before).isNotEqualTo(after)
        assertThat((before.single() as PrefRow.Leaf).checked).isTrue()
        assertThat((after.single() as PrefRow.Leaf).checked).isFalse()
    }

    @Test fun `flattening snapshots updated picker summaries`() {
        val screen = preferenceManager.createPreferenceScreen(context)
        val picker = ListPreference(context).apply {
            key = "graph_panel"
            isPersistent = false
            isVisible = true
            entries = arrayOf("Hide", "Graph 1")
            entryValues = arrayOf("0", "1")
            value = "0"
            summary = "Hide"
        }
        screen.addPreference(picker)
        val before = flattenPreferences(screen)
        picker.value = "1"
        picker.summary = "Graph 1"
        val after = flattenPreferences(screen)
        assertThat(before).isNotEqualTo(after)
        assertThat((before.single() as PrefRow.Leaf).summary).isEqualTo("Hide")
        assertThat((after.single() as PrefRow.Leaf).summary).isEqualTo("Graph 1")
    }
}
