package app.aaps.plugins.main.general.overview.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.plugins.main.R

@Composable
fun HomeGraphSettingsControl(
    settings: HomeGraphSettings,
    additional: AdditionalGraphSettings,
    onSettings: (HomeGraphSettings) -> Unit,
    onAdditional: (AdditionalGraphSettings) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.overview_graph_settings)) }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.overview_graph_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.overview_graph_settings_hint), style = AapsTheme.type.caption)
                Text(stringResource(R.string.overview_graph_overlays), style = AapsTheme.type.listTitle)
                GlucoseOverlay.entries.forEach { overlay ->
                    GraphToggle(stringResource(overlay.labelResource()), settings.visible(overlay)) { onSettings(settings.withOverlay(overlay, it)) }
                }
                Text(stringResource(R.string.overview_show_predictions), style = AapsTheme.type.listTitle)
                Text(stringResource(R.string.overview_predictions_estimates), style = AapsTheme.type.caption)
                PredictionKind.entries.forEach { kind ->
                    GraphToggle(stringResource(kind.labelResource()), kind in settings.forecasts) { onSettings(settings.withForecast(kind, it)) }
                }
                Text(stringResource(R.string.overview_additional_graphs), style = AapsTheme.type.listTitle)
                Text(stringResource(R.string.overview_graph_choose), style = AapsTheme.type.caption)
                AdditionalSeries.entries.forEach { kind ->
                    Text(stringResource(kind.labelResource()))
                    SegmentedControl(
                        options = listOf(stringResource(R.string.overview_graph_hidden), "1", "2", "3", "4"),
                        selectedIndex = additional.graph(kind),
                        onSelect = { onAdditional(additional.withGraph(kind, it)) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = {
            TextButton(onClick = { onSettings(HomeGraphSettings()); onAdditional(AdditionalGraphSettings.decode("")) }) {
                Text(stringResource(R.string.overview_graph_reset))
            }
        }
    )
}

@Composable
private fun GraphToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun GlucoseOverlay.labelResource(): Int = when (this) {
    GlucoseOverlay.TARGET -> R.string.overview_graph_target
    GlucoseOverlay.BASAL -> R.string.overview_show_basals
    GlucoseOverlay.TREATMENTS -> R.string.overview_show_treatments
    GlucoseOverlay.RAW_READINGS -> R.string.overview_graph_raw_readings
}
