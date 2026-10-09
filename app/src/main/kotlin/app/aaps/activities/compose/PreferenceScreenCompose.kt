package app.aaps.activities.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import app.aaps.core.compose.components.ListCard
import app.aaps.core.compose.components.ListRow
import app.aaps.core.compose.components.NumericInput
import app.aaps.core.compose.components.NumericSpec
import app.aaps.core.compose.components.ToggleRow
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey
import java.math.BigDecimal

/**
 * Renders the native preference tree, preserving visibility, dependencies and typed bounds.
 * Unsupported controls use the native preference click handler and dialog.
 */

/** Preference content plus explicit card boundaries from the native hierarchy. */
sealed interface PrefRow {
    data class Section(val title: String) : PrefRow
    data object CardBreak : PrefRow

    /** Display state is snapshotted so a changed value reaches Compose even if AndroidX reuses the [Preference]. */
    data class Leaf(
        val preference: Preference,
        val summary: String? = (if (preference is ListPreference) preference.entry else preference.summary)?.toString(),
        val checked: Boolean? = (preference as? TwoStatePreference)?.isChecked
    ) : PrefRow
}

/** Flatten a built [PreferenceScreen] into rows, honouring `isVisible` exactly as the legacy list does. */
fun flattenPreferences(group: PreferenceGroup): List<PrefRow> {
    val out = mutableListOf<PrefRow>()
    fun walk(g: PreferenceGroup) {
        var inLeafRun = false
        var headingUsed = false
        var childGroupSeen = false
        for (i in 0 until g.preferenceCount) {
            val p = g.getPreference(i)
            if (!p.isVisible) continue
            if (p is PreferenceGroup) {
                walk(p)
                childGroupSeen = true
                inLeafRun = false
            } else {
                if (!inLeafRun) {
                    // A child group ends its card; parent siblings must not join the last child category.
                    val hasHeading = g is PreferenceCategory || (g is PreferenceScreen && g !== group && !childGroupSeen)
                    val title = if (!headingUsed && hasHeading) g.title?.toString().orEmpty() else ""
                    if (title.isNotBlank()) out += PrefRow.Section(title)
                    else if (out.isNotEmpty()) out += PrefRow.CardBreak
                    headingUsed = true
                    inLeafRun = true
                }
                out += PrefRow.Leaf(p)
            }
        }
    }
    walk(group)
    return out
}

@Composable
fun PreferenceScreenCompose(
    rows: List<PrefRow>,
    preferences: Preferences,
    modifier: Modifier = Modifier
) {
    val colors = AapsTheme.colors
    // The host ScrollView supplies scrolling and unbounded height; do not add a nested scroll container.
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = AapsSpacing.screenH, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)
    ) {
        // Group consecutive leaves under their heading so each section becomes one card.
        var i = 0
        while (i < rows.size) {
            val row = rows[i]
            if (row is PrefRow.CardBreak) {
                i++
            } else if (row is PrefRow.Section) {
                val leaves = mutableListOf<PrefRow.Leaf>()
                var j = i + 1
                while (j < rows.size && rows[j] is PrefRow.Leaf) {
                    leaves += rows[j] as PrefRow.Leaf; j++
                }
                Text(row.title.uppercase(), style = AapsTheme.type.label, color = colors.textSecondary)
                if (leaves.isNotEmpty()) ListCard {
                    leaves.forEach { PreferenceRow(it, preferences) }
                }
                i = j
            } else {
                val leaves = mutableListOf<PrefRow.Leaf>()
                var j = i
                while (j < rows.size && rows[j] is PrefRow.Leaf) {
                    leaves += rows[j] as PrefRow.Leaf; j++
                }
                if (leaves.isNotEmpty()) ListCard {
                    leaves.forEach { PreferenceRow(it, preferences) }
                }
                i = j
            }
        }
    }
}

@Composable
private fun PreferenceRow(row: PrefRow.Leaf, preferences: Preferences) {
    val pref = row.preference
    // Keyless preferences may still open dialogs or invoke actions.
    val keyString = pref.key
    val typed = remember(pref, keyString) { inlinePreferenceKey(pref, preferences) }
    val title = pref.title?.toString().orEmpty().ifBlank { keyString.orEmpty() }
    val sub = row.summary?.takeIf { it.isNotBlank() }
    // Respect dependency and mode gating before writing.
    val editable = pref.isEnabled

    when (typed) {
        is BooleanPreferenceKey -> {
            var on by remember(keyString) { mutableStateOf(preferences.get(typed)) }
            ToggleRow(
                title = title, sub = sub, checked = on,
                onCheckedChange = {
                    // Preserve native change listeners and their cross-preference validation.
                    if (editable && pref.callChangeListener(it)) { preferences.put(typed, it); on = it }
                }
            )
        }

        is DoublePreferenceKey  -> {
            var v by remember(keyString) { mutableStateOf(preferences.get(typed)) }
            val step = pickStep(typed.min, typed.max)
            NumberRow(title, valueSummary(pref, sub)) {
                NumericInput(
                    value = v,
                    onValue = { nv ->
                        val c = nv.coerceIn(typed.min, typed.max)
                        if (editable && pref.callChangeListener(c.toString())) { preferences.put(typed, c); v = c }
                    },
                    // Shown to the step's precision: a 0.1 step reads "0.5", not "0.50".
                    spec = NumericSpec(typed.min, typed.max, step, BigDecimal.valueOf(step).stripTrailingZeros().scale().coerceAtLeast(0)),
                    unit = "",
                    name = title
                )
            }
        }

        is IntPreferenceKey     -> {
            var v by remember(keyString) { mutableStateOf(preferences.get(typed)) }
            NumberRow(title, valueSummary(pref, sub)) {
                NumericInput(
                    value = v.toDouble(),
                    onValue = { nv ->
                        val c = nv.toInt().coerceIn(typed.min, typed.max)
                        if (editable && pref.callChangeListener(c.toString())) { preferences.put(typed, c); v = c }
                    },
                    spec = NumericSpec(typed.min.toDouble(), typed.max.toDouble(), 1.0, 0, integerOnly = true),
                    unit = "",
                    name = title
                )
            }
        }

        is StringPreferenceKey  -> {
            // Keep native string dialogs, including masked editors.
            ListRow(title = title, sub = sub ?: preferences.get(typed), onClick = clickHandler(pref))
        }

        else -> if (pref is TwoStatePreference) {
            // Codec-backed display options need not create a second set of typed storage keys.
            var on by remember(pref, row.checked) { mutableStateOf(pref.isChecked) }
            ToggleRow(title = title, sub = sub, checked = on, onCheckedChange = {
                if (editable && pref.isSelectable && pref.callChangeListener(it)) {
                    pref.isChecked = it
                    on = it
                }
            })
        } else {
            // Unrecognised click actions/list pickers retain their native dialogs.
            ListRow(title = title, sub = sub, onClick = clickHandler(pref))
        }
    }
}

/**
 * A numeric preference as a row of its own: the title in the same style as every other setting, the
 * field under it. Inset like the rows around it, because a field is not a row that can span the card.
 */
@Composable
private fun NumberRow(title: String, sub: String?, field: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AapsSpacing.cardPad, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = AapsTheme.type.listTitle, color = AapsTheme.colors.textOnSurfaceStrong)
        if (sub != null) Text(sub, style = AapsTheme.type.caption, color = AapsTheme.colors.textTertiary)
        field()
    }
}

/**
 * A summary that only restates the stored value — what an edit-text preference shows by default — is
 * dropped, since the field right under it already shows the value. A written description is kept.
 */
internal fun valueSummary(pref: Preference, summary: String?): String? =
    summary?.takeUnless { pref is EditTextPreference && (pref.summaryProvider != null || it == pref.text) }

/** Disabled and non-selectable preferences must not expose click actions. */
internal fun clickHandler(pref: Preference): (() -> Unit)? =
    if (pref.isEnabled && pref.isSelectable) ({ pref.performClick() }) else null

/** A step that feels right across the very different ranges these keys span (0.05 U vs 500 mg/dL). */
private fun pickStep(min: Double, max: Double): Double {
    val span = max - min
    return when {
        span <= 2.0   -> 0.05
        span <= 20.0  -> 0.1
        span <= 200.0 -> 1.0
        else          -> 5.0
    }
}

/** List preferences are named choices even when the stored key is an Int; they keep the native dialog. */
internal fun inlinePreferenceKey(pref: Preference, preferences: Preferences): NonPreferenceKey? =
    if (pref is ListPreference) null
    else pref.key?.let { key -> runCatching { preferences.get(key) }.getOrNull() }
