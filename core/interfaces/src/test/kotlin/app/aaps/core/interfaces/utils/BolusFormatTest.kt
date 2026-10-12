package app.aaps.core.interfaces.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class BolusFormatTest {
    @Test fun `retain dose precision while removing pump arithmetic noise`() {
        for ((amount, expected) in listOf(
            0.025 to "0.025", 0.0125 to "0.0125", 0.025000000001 to "0.025000000001",
            (0.1 + 0.1 + 0.1) to "0.30", (0.3 - 0.2) to "0.10",
            (3 * 0.05) to "0.15", (0.025 * 11 / 100.0) to "0.00275"
        )) {
            assertThat(formatBolus(amount, locale = Locale.US)).isEqualTo(expected)
            assertThat(bolusDecimalFormat(locale = Locale.US).format(amount)).isEqualTo(expected)
        }
    }

    @Test fun `cleanup stays within its error budget and never erases a nonzero dose`() {
        assertThat(formatBolus(Math.nextUp(1.0), locale = Locale.US)).isEqualTo("1.00")
        var outsideBudget = 1.0
        repeat(5) { outsideBudget = Math.nextUp(outsideBudget) }
        assertThat(formatBolus(outsideBudget, locale = Locale.US)).isEqualTo(outsideBudget.toString())
        assertThat(formatBolus(Double.MIN_VALUE, locale = Locale.US).toBigDecimal().signum()).isEqualTo(1)
        assertThat(formatBolus(Double.NaN)).isEqualTo("NaN")
    }

    @Test fun `dose labels retain localized decimals without grouping`() {
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar-SA"))) {
            val parser = bolusDecimalFormat(locale = locale).apply { isParseBigDecimal = true }
            assertThat(parser.parse(formatBolus(1234.025, locale = locale))).isEqualTo("1234.025".toBigDecimal())
        }
        assertThat(formatBolus(0.025, locale = Locale.GERMANY)).isEqualTo("0,025")
    }
}
