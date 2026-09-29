package app.aaps.activities

import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import app.aaps.activities.compose.PrefRow
import app.aaps.activities.compose.flattenPreferences
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PreferenceVisibilityFilterTest : TestBaseWithProfile() {

    @Test fun `search and clearing it preserve hidden rows and hidden parent groups`() {
        val root = preferenceManager.createPreferenceScreen(context).apply { isVisible = true }
        val mode = Preference(context).apply { title = "Application protection"; isVisible = true }
        val pin = Preference(context).apply { title = "Application PIN"; isVisible = false }
        val hiddenGroup = PreferenceCategory(context).apply { title = "Hidden mode"; isVisible = false }
        root.addPreference(mode)
        root.addPreference(pin)
        root.addPreference(hiddenGroup)
        hiddenGroup.addPreference(Preference(context).apply { title = "Application hidden option"; isVisible = true })
        val filter = PreferenceVisibilityFilter()

        filter.apply("Application", root)
        assertThat(flattenPreferences(root).filterIsInstance<PrefRow.Leaf>().map { it.preference }).containsExactly(mode)
        filter.restore()
        filter.apply("PIN", root)
        assertThat(flattenPreferences(root)).isEmpty()
        filter.restore()
        filter.apply("", root)

        assertThat(mode.isVisible).isTrue()
        assertThat(pin.isVisible).isFalse()
        assertThat(hiddenGroup.isVisible).isFalse()
        assertThat(hiddenGroup.getPreference(0).isVisible).isTrue()
    }

    @Test fun `visibility changes while searching survive clearing the query`() {
        val root = preferenceManager.createPreferenceScreen(context).apply { isVisible = true }
        val pin = Preference(context).apply { title = "Bolus PIN"; isVisible = false }
        val password = Preference(context).apply { title = "Bolus password"; isVisible = true }
        root.addPreference(pin)
        root.addPreference(password)
        val filter = PreferenceVisibilityFilter()
        filter.apply("PIN", root)

        // The fragment restores native state before recalculating visibility for the new mode.
        filter.restore()
        pin.isVisible = true
        password.isVisible = false
        filter.apply("PIN", root)
        assertThat(flattenPreferences(root).filterIsInstance<PrefRow.Leaf>().map { it.preference }).containsExactly(pin)
        filter.restore()
        filter.apply("", root)

        assertThat(pin.isVisible).isTrue()
        assertThat(password.isVisible).isFalse()
    }
}
