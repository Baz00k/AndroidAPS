package app.aaps.core.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Labeled presentation of the shared numeric entry. [onValue] receives only validated commits. */
@Composable
fun NumberField(
    label: String,
    value: Double,
    onValue: (Double) -> Unit,
    step: Double,
    min: Double,
    max: Double,
    decimals: Int,
    modifier: Modifier = Modifier,
    unit: String = "",
    onValidityChange: (Boolean) -> Unit = {},
    integerOnly: Boolean = false,
    onPrecisionInsufficient: (Boolean) -> Unit = {}
) {
    Column(modifier) {
        if (label.isNotBlank()) SectionLabel(label)
        NumericInput(value, onValue, NumericSpec(min, max, step, decimals, integerOnly), unit, label,
                     onValidityChange = onValidityChange, onPrecisionInsufficient = onPrecisionInsufficient)
    }
}
