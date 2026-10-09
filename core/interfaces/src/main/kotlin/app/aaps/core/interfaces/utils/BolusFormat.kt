package app.aaps.core.interfaces.utils

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Display the supplied amount without quantizing it to a pump step or rounding away digits. */
fun formatBolus(value: Double, minimumDecimals: Int = 2, locale: Locale = Locale.getDefault()): String =
    if (value.isFinite()) bolusDecimalFormat(minimumDecimals, locale).format(BigDecimal.valueOf(value))
    else value.toString()

fun bolusDecimalFormat(minimumDecimals: Int = 2, locale: Locale = Locale.getDefault()): DecimalFormat =
    DecimalFormat("0", DecimalFormatSymbols.getInstance(locale)).apply {
        isGroupingUsed = false
        maximumFractionDigits = 340 // Covers the decimal representation of any finite Double.
        minimumFractionDigits = minimumDecimals.coerceIn(0, 340)
    }

/** Step determines minimum display precision only; historical amounts may have finer precision. */
fun bolusMinimumDecimals(bolusStep: Double): Int =
    maxOf(
        if (bolusStep <= 0.051) 2 else 1,
        if (bolusStep.isFinite() && bolusStep > 0) BigDecimal.valueOf(bolusStep).stripTrailingZeros().scale() else 0
    )
