package app.aaps.activities

import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup

/** Search may hide eligible settings, but must never override mode or dependency visibility. */
internal class PreferenceVisibilityFilter {

    private val unfilteredVisibility = mutableMapOf<Preference, Boolean>()

    // Restore before changing the query or recalculating native visibility after a setting changes.
    fun restore() {
        unfilteredVisibility.forEach { (preference, visible) -> preference.isVisible = visible }
        unfilteredVisibility.clear()
    }

    fun apply(query: String, root: Preference) {
        if (query.isEmpty()) return

        fun filter(preference: Preference): Boolean {
            val eligible = preference.isVisible
            unfilteredVisibility[preference] = eligible
            val matches = if (preference is PreferenceGroup) {
                var childMatches = false
                for (i in 0 until preference.preferenceCount) {
                    childMatches = filter(preference.getPreference(i)) || childMatches
                }
                childMatches
            } else {
                preference.key?.contains(query, ignoreCase = true) == true ||
                    preference.title?.contains(query, ignoreCase = true) == true ||
                    preference.summary?.contains(query, ignoreCase = true) == true
            }
            val visible = eligible && matches
            preference.isVisible = visible
            if (visible && preference is PreferenceCategory) preference.initialExpandedChildrenCount = Int.MAX_VALUE
            return visible
        }

        filter(root)
    }
}
