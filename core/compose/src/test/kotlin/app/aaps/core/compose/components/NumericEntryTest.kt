package app.aaps.core.compose.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class NumericEntryTest {
    private val spec = NumericSpec(5.0, 100.0, 0.025, 2)

    @Test fun onlyCompleteInRangeDraftsCommitWithoutChangingTheRequest() {
        var published = 8.0
        listOf("", "1", "1.", "-", ".", "-,", "NaN", "Infinity", "1,2.3", "-5", "101").forEach {
            assertThat(NumericDraft(it).commit(spec) { published = it }).isFalse()
        }
        assertThat(published).isEqualTo(8.0)
        assertThat(NumericDraft("12,5").commit(spec) { published = it }).isTrue()
        assertThat(published).isEqualTo(12.5)
        assertThat(spec.copy(min = -100.0).validate("-12.5").value).isEqualTo(-12.5)
        assertThat(spec.copy(integerOnly = true).validate("12.5").error).isEqualTo(NumericError.INTEGER)
        assertThat(spec.copy(max = 10.0).validate("12.5").value).isNull()
        assertThat(spec.copy(step = Double.NaN).validate("12.5").error).isEqualTo(NumericError.CONFIGURATION)
    }

    @Test fun aButtonBringsAValueBackToTheNearestBoundButNeverFurtherOut() {
        assertThat(spec.stepFrom("200", false)).isEqualTo(100.0)
        assertThat(spec.stepFrom("200", true)).isNull()
        assertThat(spec.stepFrom("1", true)).isEqualTo(5.0)
        assertThat(spec.stepFrom("1", false)).isNull()
        // In range it steps, and does nothing once at the bound it points to.
        assertThat(spec.stepFrom("10", true)).isEqualTo(10.025)
        assertThat(spec.stepFrom("100", true)).isNull()
        assertThat(spec.stepFrom("5", false)).isNull()
        // Not a number yet, or not a number at all: nothing to step from.
        listOf("", "-", "1.", "abc", "1e3").forEach {
            assertThat(spec.stepFrom(it, true)).isNull()
            assertThat(spec.stepFrom(it, false)).isNull()
        }
        assertThat(NumericDraft("200").stepped(spec, false)?.text).isEqualTo("100.00")
    }

    @Test fun decimalSteppingClampsOnlyAtBoundsAndDoesNotQuantizeTypedValues() {
        val dose = spec.copy(min = -0.013, max = 0.063)
        assertThat(dose.increment(0.0, true)).isEqualTo(0.025)
        assertThat(dose.increment(0.025, true)).isEqualTo(0.05)
        assertThat(dose.increment(0.05, false)).isEqualTo(0.025)
        assertThat(dose.increment(0.05, true)).isEqualTo(0.063)
        assertThat(dose.increment(0.0, false)).isEqualTo(-0.013)
        assertThat(dose.validate("0.013").value).isEqualTo(0.013)
        assertThat(dose.increment(0.013, true)).isEqualTo(0.038)
        assertThat(dose.precisionInsufficient).isTrue()
        assertThat(dose.copy(decimals = 3).precisionInsufficient).isFalse()
        assertThat(formatNumeric(0.025, 2, Locale.US)).isEqualTo("0.025")
        assertThat(dose.validate(formatNumeric(0.025, 2, Locale.forLanguageTag("ar-SA"))).value).isEqualTo(0.025)
    }
}
