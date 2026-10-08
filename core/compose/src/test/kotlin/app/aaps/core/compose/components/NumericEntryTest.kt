package app.aaps.core.compose.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class NumericEntryTest {
    private val spec = NumericSpec(5.0, 100.0, 0.025, 2)

    @Test fun positiveMinimumDoesNotRewriteIncrementalTyping() {
        var published = 8.0
        listOf("", "1", "1.").forEach {
            val draft = NumericDraft(it)
            assertThat(draft.commit(spec) { published = it }).isFalse()
            assertThat(draft.text).isEqualTo(it)
            assertThat(published).isEqualTo(8.0)
        }
        assertThat(NumericDraft("12.5").commit(spec) { published = it }).isTrue()
        assertThat(published).isEqualTo(12.5)
    }

    @Test fun invalidAndIncompleteNumbersNeverCommit() {
        val signed = spec.copy(min = -100.0)
        listOf("", "-", "+", ".", ",", "-.", "-,", "5.", "5,", "NaN", "Infinity", "-Infinity", "1e2", "1,2.3", " 5", "five").forEach {
            assertThat(NumericDraft(it).commit(signed) { error("Published $it") }).isFalse()
        }
        assertThat(NumericDraft("9".repeat(400)).validated(signed)).isNull()
        assertThat(NumericDraft("0." + "0".repeat(400) + "1").validated(signed)).isNull()
    }

    @Test fun commaDotAndNegativesAreExplicit() {
        val signed = spec.copy(min = -100.0)
        assertThat(signed.validate("-12,5").value).isEqualTo(-12.5)
        assertThat(signed.validate("-12.5").value).isEqualTo(-12.5)
        assertThat(spec.validate("-12.5").error).isEqualTo(NumericError.MINIMUM)
        assertThat(signed.validate("-.5").value).isEqualTo(-0.5)
        assertThat(signed.validate("+5").value).isEqualTo(5.0)
    }

    @Test fun boundsChangesRevalidateWithoutClampingDraftOrExternalValue() {
        val draft = NumericDraft("12.5")
        assertThat(draft.validated(spec.copy(max = 10.0))).isNull()
        assertThat(draft.text).isEqualTo("12.5")
        assertThat(draft.validated(spec)).isEqualTo(12.5)
        assertThat(NumericDraft.from(200.0, 2).validated(spec)).isNull()
        assertThat(NumericDraft.from(Double.NaN, 2).validated(spec)).isNull()
    }

    @Test fun incrementIsDecimalArithmeticNotQuantization() {
        val dose = spec.copy(min = 0.0, max = 1.0)
        var value = 0.0
        repeat(3) { value = dose.increment(value, true)!! }
        assertThat(value).isEqualTo(0.075)
        assertThat(dose.increment(value, false)).isEqualTo(0.05)
        assertThat(dose.validate("0.013").value).isEqualTo(0.013)
        assertThat(dose.increment(0.013, true)).isEqualTo(0.038)
        assertThat(dose.precisionInsufficient).isTrue()
        assertThat(dose.copy(decimals = 3).precisionInsufficient).isFalse()
        assertThat(formatNumeric(0.025, 2, Locale.US)).isEqualTo("0.025")
        assertThat(formatNumeric(0.025, 2, Locale.GERMANY)).isEqualTo("0,025")
    }

    @Test fun buttonActionsClampAtNonAlignedBoundsAndTypingDoesNot() {
        val bounds = NumericSpec(-0.013, 0.063, 0.025, 2)
        assertThat(bounds.increment(0.05, true)).isEqualTo(0.063)
        assertThat(bounds.increment(0.0, false)).isEqualTo(-0.013)
        assertThat(bounds.increment(0.063, true)).isEqualTo(0.063)
        assertThat(bounds.increment(-0.013, false)).isEqualTo(-0.013)
        assertThat(bounds.validate("0.064").value).isNull()
        assertThat(bounds.validate("-0.014").value).isNull()
    }

    @Test fun integerDomainIsSeparateFromDisplayPrecisionAndStep() {
        val general = spec.copy(min = 0.0, step = 5.0, decimals = 0)
        assertThat(general.validate("2.5").value).isEqualTo(2.5)
        assertThat(general.copy(integerOnly = true).validate("2.5").error).isEqualTo(NumericError.INTEGER)
        assertThat(general.copy(integerOnly = true).validate("2.0").value).isEqualTo(2.0)
    }

    @Test fun invalidConfigurationFailsClosed() {
        listOf(spec.copy(min = 101.0), spec.copy(max = Double.NaN), spec.copy(step = 0.0), spec.copy(step = Double.POSITIVE_INFINITY), spec.copy(decimals = -1)).forEach {
            assertThat(it.validate("10").error).isEqualTo(NumericError.CONFIGURATION)
            assertThat(it.increment(10.0, true)).isNull()
        }
    }
}
