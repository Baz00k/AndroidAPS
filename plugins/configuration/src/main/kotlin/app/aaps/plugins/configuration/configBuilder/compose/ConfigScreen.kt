package app.aaps.plugins.configuration.configBuilder.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.CheckboxRow
import app.aaps.core.compose.components.Dot
import app.aaps.core.compose.components.ListCard
import app.aaps.core.compose.components.ListRow
import app.aaps.core.compose.components.RadioRow
import app.aaps.core.compose.components.SectionLabel
import app.aaps.core.compose.components.ToggleRow
import app.aaps.core.compose.icons.AapsIcons
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * Redesigned Config Builder (handoff Section 5): a "Your loop, right now" summary card + a list of
 * toggleable general plugins. Toggling reuses `ConfigBuilder.performPluginSwitch` in the fragment.
 */
@Composable
fun ConfigScreen(
    state: ConfigUiState,
    onToggle: (index: Int, enabled: Boolean) -> Unit,
    onOpenPrefs: (index: Int) -> Unit,
    onSelect: (index: Int, enabled: Boolean) -> Unit
) {
    val colors = AapsTheme.colors
    Column(
        // The screen's title is the toolbar's (or the tab's); it is not repeated here.
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState())
            .padding(horizontal = AapsSpacing.screenH).padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)
    ) {
        if (state.summary.isNotEmpty()) Section("Your loop, right now") {
            ListCard(Modifier.fillMaxWidth()) {
                state.summary.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = AapsSpacing.cardPad, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Dot(if (s.ok) colors.inRange else colors.high, size = 9.dp)
                        Text(s.label, style = AapsTheme.type.listTitle, color = colors.textSecondary, modifier = Modifier.padding(start = 12.dp).weight(1f))
                        Text(s.value, style = AapsTheme.type.listTitle, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        state.categories.forEach { category ->
            Section(category.title, category.description) {
                ListCard(Modifier.fillMaxWidth()) {
                    Column(if (category.multiple) Modifier else Modifier.selectableGroup()) {
                        category.options.forEach { option ->
                            val sub = option.description.ifBlank { null }
                            if (category.multiple)
                                CheckboxRow(option.name, option.selected, { onSelect(option.index, it) }, sub = sub, enabled = !option.fixed)
                            else
                                // Choosing the active option again would ask to switch to it again (and reconnect a pump).
                                RadioRow(option.name, option.selected, { if (!option.selected) onSelect(option.index, true) }, sub = sub, enabled = !option.fixed)
                        }
                    }
                }
            }
        }

        if (state.plugins.isNotEmpty()) Section("Plugins") {
            ListCard(Modifier.fillMaxWidth()) {
                state.plugins.forEach { p -> ToggleRow(p.name, p.enabled, { onToggle(p.index, it) }, sub = p.sub.ifBlank { null }) }
            }
        }

        if (state.prefs.isNotEmpty()) Section("Settings") {
            state.prefs.groupBy { it.group }.forEach { (group, rows) ->
                Text(group, style = AapsTheme.type.caption, color = colors.textTertiary)
                ListCard(Modifier.fillMaxWidth()) {
                    rows.forEach { p ->
                        ListRow(p.name, onClick = { onOpenPrefs(p.index) }) {
                            Icon(AapsIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A heading, an optional line saying what it is for, and its content — spaced the same everywhere. */
@Composable
private fun Section(title: String, description: String = "", content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionLabel(title)
            if (description.isNotBlank()) Text(description, style = AapsTheme.type.caption, color = AapsTheme.colors.textTertiary)
        }
        content()
    }
}
