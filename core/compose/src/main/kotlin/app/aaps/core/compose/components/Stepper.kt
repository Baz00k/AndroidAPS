package app.aaps.core.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme

/**
 * A circular −/+ stepper with a big centered value + caption. [value] is pre-formatted by the caller.
 */
@Composable
fun Stepper(
    value: String,
    caption: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AapsTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StepButton(plus = false, contentDescription = "Decrease", onClick = onMinus, size = 52.dp)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = AapsTheme.type.bigValue, color = colors.textPrimary)
            Text(caption, style = AapsTheme.type.caption, color = colors.textTertiary)
        }
        StepButton(plus = true, contentDescription = "Increase", onClick = onPlus, size = 52.dp)
    }
}
