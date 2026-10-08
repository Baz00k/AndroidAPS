package app.aaps.core.compose.components

import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Display precision and button increment are not a dose-quantization policy. */
data class NumericSpec(
    val min: Double,
    val max: Double,
    val step: Double,
    val decimals: Int,
    val integerOnly: Boolean = false
) {
    val valid: Boolean get() = min.isFinite() && max.isFinite() && min <= max && step.isFinite() && step > 0 && decimals in 0..12
    val precisionInsufficient: Boolean get() = valid && BigDecimal.valueOf(step).stripTrailingZeros().scale() > decimals

    fun validate(text: String): NumericValidation {
        if (!valid) return NumericValidation(null, NumericError.CONFIGURATION)
        if (text.isEmpty() || text in listOf("-", "+", ".", ",", "-.", "-,", "+.", "+,") || text.endsWith('.') || text.endsWith(','))
            return NumericValidation(null, NumericError.INCOMPLETE)
        // No exponent, grouping, whitespace, NaN or infinity in a treatment entry.
        if (!Regex("[+-]?(?:[0-9]+(?:[.,][0-9]+)?|[.,][0-9]+)").matches(text)) return NumericValidation(null, NumericError.NUMBER)
        val decimal = text.replace(',', '.').toBigDecimalOrNull() ?: return NumericValidation(null, NumericError.NUMBER)
        val value = decimal.toDouble()
        if (!value.isFinite() || (value == 0.0 && decimal.signum() != 0)) return NumericValidation(null, NumericError.NUMBER)
        if (decimal < BigDecimal.valueOf(min)) return NumericValidation(null, NumericError.MINIMUM)
        if (decimal > BigDecimal.valueOf(max)) return NumericValidation(null, NumericError.MAXIMUM)
        if (integerOnly && decimal.stripTrailingZeros().scale() > 0) return NumericValidation(null, NumericError.INTEGER)
        // Reject values that the Double callback cannot faithfully represent rather than changing the request.
        if (BigDecimal.valueOf(value).compareTo(decimal) != 0) return NumericValidation(null, NumericError.NUMBER)
        return NumericValidation(value, null)
    }

    /** Only button actions clamp; typed and external values never silently change. */
    fun increment(value: Double, increase: Boolean): Double? {
        if (!valid || !value.isFinite()) return null
        val result = BigDecimal.valueOf(value).add(BigDecimal.valueOf(step).let { if (increase) it else it.negate() })
        return result.max(BigDecimal.valueOf(min)).min(BigDecimal.valueOf(max)).toDouble()
    }
}

enum class NumericError { INCOMPLETE, NUMBER, MINIMUM, MAXIMUM, INTEGER, CONFIGURATION }

data class NumericValidation(val value: Double?, val error: NumericError?)

/** Immutable draft: a bounds change revalidates it; an external value replaces it, even when invalid. */
data class NumericDraft(val text: String) {
    fun validated(spec: NumericSpec): Double? = spec.validate(text).value
    fun commit(spec: NumericSpec, publish: (Double) -> Unit): Boolean {
        val value = validated(spec) ?: return false
        publish(value)
        return true
    }

    fun stepped(spec: NumericSpec, increase: Boolean): NumericDraft? =
        validated(spec)?.let { spec.increment(it, increase) }?.let { from(it, spec.decimals) }

    companion object {
        fun from(value: Double, decimals: Int) = NumericDraft(formatNumeric(value, decimals))
    }
}

/** [decimals] is a minimum display precision. Extra significant digits are retained, never rounded. */
fun formatNumeric(value: Double, decimals: Int, locale: Locale = Locale.getDefault()): String {
    if (!value.isFinite()) return value.toString()
    val number = BigDecimal.valueOf(value).stripTrailingZeros()
    val text = number.setScale(maxOf(decimals.coerceIn(0, 12), number.scale(), 0)).toPlainString()
    // The entry grammar accepts comma and dot. Do not generate an unparseable initial draft on
    // locales whose decimal separator is neither (for example Arabic U+066B).
    val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator.let { if (it == ',') ',' else '.' }
    return text.replace('.', separator)
}
