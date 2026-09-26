package app.aaps.activities.compose

import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.validators.preferences.AdaptiveListIntPreference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class PreferenceScreenComposeTest {

    @Test
    fun `protection modes use their choice dialog rather than an inline number editor`() {
        val preferences = mock<Preferences>()
        for (key in listOf(IntKey.ProtectionTypeApplication, IntKey.ProtectionTypeSettings, IntKey.ProtectionTypeBolus)) {
            val preference = mock<AdaptiveListIntPreference>()
            whenever(preference.key).thenReturn(key.key)
            whenever(preferences.get(key.key)).thenReturn(key)

            assertNull(inlinePreferenceKey(preference, preferences), key.key)
        }
        verifyNoInteractions(preferences)
    }

    @Test
    fun `protection timeout remains numeric`() {
        val preferences = mock<Preferences>()
        val preference = mock<Preference>()
        whenever(preference.key).thenReturn(IntKey.ProtectionTimeout.key)
        whenever(preferences.get(IntKey.ProtectionTimeout.key)).thenReturn(IntKey.ProtectionTimeout)

        assertEquals(IntKey.ProtectionTimeout, inlinePreferenceKey(preference, preferences))
    }

    @Test
    fun `refreshed rows snapshot the selected choice label`() {
        val preference = mock<AdaptiveListIntPreference>()
        val screen = mock<PreferenceScreen>()
        whenever(screen.preferenceCount).thenReturn(1)
        whenever(screen.getPreference(0)).thenReturn(preference)
        whenever(preference.isVisible).thenReturn(true)
        whenever(preference.summary).thenReturn("stale summary")
        var previous: List<PrefRow>? = null

        for (label in listOf("No protection", "Biometric", "Master password", "Custom password", "Custom PIN")) {
            whenever(preference.entry).thenReturn(label)
            val rows = flattenPreferences(screen)
            assertEquals(label, (rows.single() as PrefRow.Leaf).summary)
            assertNotEquals(previous, rows)
            previous = rows
        }
        // Existing snapshots must not change when the underlying preference changes.
        whenever(preference.entry).thenReturn("No protection")
        assertEquals("Custom PIN", (previous!!.single() as PrefRow.Leaf).summary)
    }

    @Test
    fun `unrecognised saved choice is not displayed as no protection`() {
        val preference = mock<ListPreference>()
        whenever(preference.entry).thenReturn(null)
        whenever(preference.summary).thenReturn("No protection")

        assertNull(PrefRow.Leaf(preference).summary)
    }

    @Test
    fun `choice clicks go through the original preference`() {
        val preference = mock<ListPreference>()
        whenever(preference.isEnabled).thenReturn(true)
        whenever(preference.isSelectable).thenReturn(true)

        clickHandler(preference)!!.invoke()

        verify(preference).performClick()
    }

    @Test
    fun `disabled or non selectable choices cannot open a dialog`() {
        val preference = mock<ListPreference>()
        for ((enabled, selectable) in listOf(false to true, true to false, false to false)) {
            whenever(preference.isEnabled).thenReturn(enabled)
            whenever(preference.isSelectable).thenReturn(selectable)

            assertNull(clickHandler(preference))
        }
    }
}
