package app.aaps.core.interfaces.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale

class BolusFormatTest {

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
