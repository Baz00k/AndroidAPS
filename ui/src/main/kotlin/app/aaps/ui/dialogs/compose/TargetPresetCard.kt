package app.aaps.ui.dialogs.compose

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.aaps.core.compose.components.Choice
import app.aaps.core.compose.components.ChoiceRow
import app.aaps.core.compose.components.EntryCard
import app.aaps.core.compose.theme.AapsTheme

/** A quick temporary target from Settings, started together with an entry. */
enum class TargetPreset(val label: String) { NONE("None"), EATING_SOON("Eating soon"), ACTIVITY("Activity"), HYPO("Hypo") }

/** A preset and what it sets, e.g. "90 mg/dL · 45 min". */
data class TargetPresetOption(val preset: TargetPreset, val summary: String)

/** The same target choice on every entry screen: None or one of the configured presets. */
@Composable
fun TargetPresetCard(options: List<TargetPresetOption>, selected: TargetPreset, onSelect: (TargetPreset) -> Unit) {
    EntryCard("Temporary target") {
        ChoiceRow {
            Choice(TargetPreset.NONE.label, selected = selected == TargetPreset.NONE, onClick = { onSelect(TargetPreset.NONE) }, modifier = Modifier.weight(1f))
            options.forEach { option -> Choice(option.preset.label, selected = selected == option.preset, onClick = { onSelect(option.preset) }, modifier = Modifier.weight(1f)) }
        }
        options.firstOrNull { it.preset == selected }?.let {
            Text(it.summary, style = AapsTheme.type.caption, color = AapsTheme.colors.textSecondary)
        }
    }
}
