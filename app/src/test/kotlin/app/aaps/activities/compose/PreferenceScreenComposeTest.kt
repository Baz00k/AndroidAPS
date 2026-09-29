package app.aaps.activities.compose

import androidx.preference.ListPreference
import androidx.preference.SwitchPreference
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PreferenceScreenComposeTest : TestBaseWithProfile() {

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
