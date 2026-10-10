package app.aaps.core.interfaces.utils

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.FieldPosition
import java.util.Locale

/** Display only: retain dose precision, removing decimal noise within four Double ULPs. Never quantize to a pump step. */
fun formatBolus(value: Double, minimumDecimals: Int = 2, locale: Locale = Locale.getDefault()): String =
    if (value.isFinite()) bolusDecimalFormat(minimumDecimals, locale).format(value)
    else value.toString()

/** For labels computed from decimal pump operands; do not feed the derived value back into therapy state. */
fun formatBolus(value: BigDecimal, minimumDecimals: Int = 2, locale: Locale = Locale.getDefault()): String =
    bolusDecimalFormat(minimumDecimals, locale).format(value)

fun bolusDecimalFormat(minimumDecimals: Int = 2, locale: Locale = Locale.getDefault()): DecimalFormat =
    object : DecimalFormat("0", DecimalFormatSymbols.getInstance(locale)) {
        override fun format(number: Double, result: StringBuffer, fieldPosition: FieldPosition): StringBuffer =
            if (number.isFinite()) super.format(displayBolusDecimal(number), result, fieldPosition)
            else super.format(number, result, fieldPosition)
    }.apply {
        isGroupingUsed = false
        maximumFractionDigits = 340 // Covers the decimal representation of any finite Double.
        minimumFractionDigits = minimumDecimals.coerceIn(0, 340)
    }

/**
 * Choose the shortest decimal within a small binary-error budget, not a fixed decimal-place limit.
 * Pump arithmetic (pulses * 0.05, requested - remaining, percentage estimates) can introduce a few
 * ULPs of noise. A four-ULP budget removes that noise but keeps differences outside that budget,
 * including off-step historical amounts. Significant-digit rounding never turns a nonzero dose
 * into zero. This derived number is for labels only; do not use it for dosing or persistence.
 */
private fun displayBolusDecimal(value: Double): BigDecimal {
    val exact = BigDecimal.valueOf(value)
    val tolerance = BigDecimal(Math.ulp(value)).multiply(BigDecimal.valueOf(4))
    for (precision in 1 until exact.precision()) {
        val candidate = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
        if (exact.subtract(candidate).abs() <= tolerance) return candidate
    }
    return exact
}

/** Step determines minimum display precision only; historical amounts may have finer precision. */
fun bolusMinimumDecimals(bolusStep: Double): Int =
    maxOf(
        if (bolusStep <= 0.051) 2 else 1,
        if (bolusStep.isFinite() && bolusStep > 0) BigDecimal.valueOf(bolusStep).stripTrailingZeros().scale() else 0
    )
