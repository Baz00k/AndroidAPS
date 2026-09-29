package app.aaps.activities.compose

import android.text.TextUtils
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreference
import app.aaps.core.keys.IntKey
import app.aaps.core.validators.preferences.AdaptiveListIntPreference
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import java.util.Objects

class PreferenceScreenComposeTest : TestBaseWithProfile() {

    private lateinit var textUtils: MockedStatic<TextUtils>

    // Android stubs return false from TextUtils.equals, so ListPreference could never resolve a selected entry.
    @BeforeEach fun stubTextUtils() {
        textUtils = Mockito.mockStatic(TextUtils::class.java)
        textUtils.`when`<Boolean> { TextUtils.equals(anyOrNull(), anyOrNull()) }
            .thenAnswer { Objects.equals(it.getArgument<CharSequence?>(0)?.toString(), it.getArgument<CharSequence?>(1)?.toString()) }
    }

    @AfterEach fun closeTextUtils() = textUtils.close()

    // Adding a preference to a screen applies its default value, so tests select a choice after attaching it.
    private fun protectionChoice(): AdaptiveListIntPreference {
        whenever(preferences.get(IntKey.ProtectionTypeApplication.key)).thenReturn(IntKey.ProtectionTypeApplication)
        return AdaptiveListIntPreference(
            ctx = context,
            intKey = IntKey.ProtectionTypeApplication,
            title = null,
            entries = arrayOf("No protection", "Biometric"),
            entryValues = arrayOf("0", "1")
        ).apply { isPersistent = false; isVisible = true }
    }

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

    @Test fun `protection modes keep their choice dialog while the protection timeout stays numeric`() {
        val timeout = Preference(context).apply { key = IntKey.ProtectionTimeout.key }
        whenever(preferences.get(IntKey.ProtectionTimeout.key)).thenReturn(IntKey.ProtectionTimeout)

        assertThat(inlinePreferenceKey(protectionChoice(), preferences)).isNull()
        assertThat(inlinePreferenceKey(timeout, preferences)).isEqualTo(IntKey.ProtectionTimeout)
    }

    @Test fun `flattening shows the selected choice label and refreshes it after a new choice`() {
        val screen = preferenceManager.createPreferenceScreen(context)
        val picker = protectionChoice().apply { summary = "stale description" }
        screen.addPreference(picker)
        picker.value = "1"
        val before = flattenPreferences(screen)
        picker.value = "0"
        val after = flattenPreferences(screen)

        assertThat((before.single() as PrefRow.Leaf).summary).isEqualTo("Biometric")
        assertThat((after.single() as PrefRow.Leaf).summary).isEqualTo("No protection")
        assertThat(before).isNotEqualTo(after)
    }

    @Test fun `an unrecognised stored choice is not shown as a valid label`() {
        val screen = preferenceManager.createPreferenceScreen(context)
        val picker = protectionChoice().apply { summary = "No protection" }
        screen.addPreference(picker)
        picker.value = "9"

        assertThat((flattenPreferences(screen).single() as PrefRow.Leaf).summary).isNull()
    }

    @Test fun `only enabled and selectable rows hand taps to the native preference`() {
        var clicks = 0
        val pref = Preference(context).apply {
            isEnabled = true
            isSelectable = true
            setOnPreferenceClickListener { clicks++; true }
        }

        clickHandler(pref)!!.invoke()
        assertThat(clicks).isEqualTo(1)

        for ((enabled, selectable) in listOf(false to true, true to false, false to false)) {
            pref.isEnabled = enabled
            pref.isSelectable = selectable
            assertThat(clickHandler(pref)).isNull()
        }
    }
}
