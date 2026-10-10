package app.aaps.core.interfaces.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale

class BolusFormatTest {

    @Test fun `labels remove arithmetic noise from pump increments remaining dose pulses and estimates`() {
        var virtualDelivered = 0.0
        repeat(3) { virtualDelivered += 0.1 }
        for ((amount, expected) in listOf(
            virtualDelivered to "0.30",
            (0.3 - 20 / 100.0) to "0.10",
            (3 * 0.05) to "0.15",
            (0.025 * 11 / 100.0) to "0.00275"
        )) {
            assertThat(formatBolus(amount, locale = Locale.US)).isEqualTo(expected)
            assertThat(bolusDecimalFormat(locale = Locale.US).format(amount)).isEqualTo(expected)
        }
    }

    @Test fun `noise cleanup cannot hide a meaningful off-step difference or a tiny nonzero dose`() {
        for ((amount, expected) in listOf(
            0.025000000001 to "0.025000000001",
            0.0123456789 to "0.0123456789",
            0.000000000025 to "0.000000000025",
            -0.025 to "-0.025"
        )) {
            assertThat(formatBolus(amount, locale = Locale.US)).isEqualTo(expected)
        }
        val smallest = formatBolus(Double.MIN_VALUE, locale = Locale.US).toBigDecimal()
        assertThat(smallest.signum()).isEqualTo(1)
    }

    @Test fun `display cleanup stays inside the documented binary error budget`() {
        assertThat(formatBolus(Math.nextUp(1.0), locale = Locale.US)).isEqualTo("1.00")
        var outsideBudget = 1.0
        repeat(5) { outsideBudget = Math.nextUp(outsideBudget) }
        assertThat(formatBolus(outsideBudget, locale = Locale.US)).isEqualTo(outsideBudget.toString())
        assertThat(formatBolus(-outsideBudget, locale = Locale.US)).isEqualTo((-outsideBudget).toString())
    }

    @Test fun `decimal operand labels avoid cancellation and accumulation without widening generic tolerance`() {
        assertThat(formatBolus(BigDecimal("0.1").multiply(BigDecimal("60")), locale = Locale.US)).isEqualTo("6.00")
        assertThat(formatBolus(BigDecimal("0.1").multiply(BigDecimal("100")), locale = Locale.US)).isEqualTo("10.00")
        assertThat(formatBolus(BigDecimal("10.0").subtract(BigDecimal("9.90")), locale = Locale.US)).isEqualTo("0.10")
        assertThat(formatBolus(BigDecimal("10.000000000001").subtract(BigDecimal("9.90")), locale = Locale.US)).isEqualTo("0.100000000001")
    }

    @Test fun `labels preserve small off-step historical and large doses`() {
        for ((amount, text) in listOf(0.0 to "0.00", 0.025 to "0.025", 0.0125 to "0.0125", 1.0 to "1.00", 12.345 to "12.345", 1234.5 to "1234.50")) {
            assertThat(formatBolus(amount, locale = Locale.US)).isEqualTo(text)
        }
    }

    @Test fun `decimal labels round trip in dot comma and Arabic locales without grouping`() {
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar-SA"))) {
            val text = formatBolus(1234.025, locale = locale)
            val parser = bolusDecimalFormat(locale = locale).apply { isParseBigDecimal = true }
            assertThat(parser.parse(text)).isEqualTo(BigDecimal("1234.025"))
            assertThat(text).doesNotContain(DecimalFormatSymbols.getInstance(locale).groupingSeparator.toString())
        }
        assertThat(formatBolus(0.025, locale = Locale.GERMANY)).isEqualTo("0,025")
    }

    @Test fun `unknown or invalid pump steps do not hide finer amount precision`() {
        for (step in listOf(0.0, -0.05, Double.NaN, Double.POSITIVE_INFINITY, 0.025, 0.05, 0.1)) {
            assertThat(formatBolus(0.0125, bolusMinimumDecimals(step), Locale.US)).isEqualTo("0.0125")
        }
    }

    @Test fun `nonfinite input is displayed explicitly rather than as a numeric dose`() {
        assertThat(formatBolus(Double.NaN)).isEqualTo("NaN")
        assertThat(formatBolus(Double.POSITIVE_INFINITY)).isEqualTo("Infinity")
        assertThat(formatBolus(Double.NEGATIVE_INFINITY)).isEqualTo("-Infinity")
    }
}
