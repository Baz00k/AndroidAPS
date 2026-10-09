package app.aaps.implementation.utils

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

class DecimalFormatterTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper

    private lateinit var sut: DecimalFormatter

    @BeforeEach
    fun setup() {
        sut = DecimalFormatterImpl(rh)
    }

    @Test fun to0DecimalTest() {
        assertThat(sut.to0Decimal(1.33).replace(",", ".")).isEqualTo("1")
        assertThat(sut.to0Decimal(1.33, "U").replace(",", ".")).isEqualTo("1U")
    }

    @Test fun to1DecimalTest() {
        assertThat(sut.to1Decimal(1.33).replace(",", ".")).isEqualTo("1.3")
        assertThat(sut.to1Decimal(1.33, "U").replace(",", ".")).isEqualTo("1.3U")
    }

    @Test fun to2DecimalTest() {
        assertThat(sut.to2Decimal(1.3333).replace(",", ".")).isEqualTo("1.33")
        assertThat(sut.to2Decimal(1.3333, "U").replace(",", ".")).isEqualTo("1.33U")
    }

    @Test fun to3DecimalTest() {
        assertThat(sut.to3Decimal(1.3333).replace(",", ".")).isEqualTo("1.333")
        assertThat(sut.to3Decimal(1.3333, "U").replace(",", ".")).isEqualTo("1.333U")
    }

    @Test fun pumpBolusLabelsRetainTheAmountRatherThanRoundingToTheStep() {
        for (step in listOf(0.025, 0.05, 0.1)) {
            assertThat(sut.toPumpSupportedBolus(0.025, step).replace(",", ".")).isEqualTo("0.025")
            assertThat(sut.toPumpSupportedBolus(0.0125, step).replace(",", ".")).isEqualTo("0.0125")
            assertThat(sut.pumpSupportedBolusFormat(step).format(0.025).replace(",", ".")).isEqualTo("0.025")
        }
    }

    @Test fun pumpBolusLabelsKeepMinimumPrecisionForTheStep() {
        assertThat(sut.toPumpSupportedBolus(1.0, 0.025).replace(",", ".")).isEqualTo("1.000")
        assertThat(sut.toPumpSupportedBolus(1.0, 0.05).replace(",", ".")).isEqualTo("1.00")
        assertThat(sut.toPumpSupportedBolus(1.0, 0.1).replace(",", ".")).isEqualTo("1.0")
    }

    @Test fun pumpBolusUnitsWrapAnAlreadyFormattedAmount() {
        val amount = sut.toPumpSupportedBolus(0.025, 0.025)
        whenever(rh.gs(app.aaps.core.ui.R.string.format_insulin_units_label, amount)).thenReturn("$amount U")
        assertThat(sut.toPumpSupportedBolusWithUnits(0.025, 0.025).replace(',', '.')).isEqualTo("0.025 U")
    }
}
