package app.aaps.ui.dialogs.compose

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CalculatorModelsTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun `a sensor reading up to 9 minutes old drives the correction`() {
        assertThat(CalculatorGlucose.resolve(null, 150.0, now, now)).isEqualTo(CalculatorGlucose.Sensor(150.0, now))
        val almostNine = now - 9 * minute + 1
        assertThat(CalculatorGlucose.resolve(null, 150.0, almostNine, now).usedMgdl).isEqualTo(150.0)
    }

    @Test
    fun `an older sensor reading is shown but never corrects`() {
        val glucose = CalculatorGlucose.resolve(null, 150.0, now - 9 * minute, now)
        assertThat(glucose).isEqualTo(CalculatorGlucose.Stale(150.0, now - 9 * minute))
        assertThat(glucose.usedMgdl).isNull()
    }

    @Test
    fun `a manual value replaces the sensor, fresh or not`() {
        assertThat(CalculatorGlucose.resolve(120.0, 150.0, now, now)).isEqualTo(CalculatorGlucose.Manual(120.0))
        assertThat(CalculatorGlucose.resolve(120.0, null, null, now).usedMgdl).isEqualTo(120.0)
    }

    @Test
    fun `a reading stamped in the future is never trusted to correct`() {
        assertThat(CalculatorGlucose.resolve(null, 150.0, now + minute, now).usedMgdl).isEqualTo(150.0)
        val ahead = CalculatorGlucose.resolve(null, 150.0, now + 2 * minute, now)
        assertThat(ahead).isInstanceOf(CalculatorGlucose.Stale::class.java)
        assertThat(ahead.usedMgdl).isNull()
    }

    @Test
    fun `a nonsensical manual value is not used`() {
        assertThat(CalculatorGlucose.resolve(Double.POSITIVE_INFINITY, 150.0, now, now)).isEqualTo(CalculatorGlucose.None)
        assertThat(CalculatorGlucose.resolve(0.0, 150.0, now, now)).isEqualTo(CalculatorGlucose.None)
    }

    @Test
    fun `no reading, or a nonsensical one, is none`() {
        assertThat(CalculatorGlucose.resolve(null, null, null, now)).isEqualTo(CalculatorGlucose.None)
        assertThat(CalculatorGlucose.resolve(null, 0.0, now, now)).isEqualTo(CalculatorGlucose.None)
        assertThat(CalculatorGlucose.resolve(null, Double.NaN, now, now)).isEqualTo(CalculatorGlucose.None)
        assertThat(CalculatorGlucose.resolve(null, Double.POSITIVE_INFINITY, now, now)).isEqualTo(CalculatorGlucose.None)
    }

    @Test
    fun `a positive dose is delivered, and a cap is reported only when it changed the dose`() {
        val plain = CalculatorOutcome.of(calculatedInsulin = 2.4, insulinAfterConstraints = 2.4, carbsEquivalent = 0.0, carbs = 30, bolusStep = 0.05)
        assertThat(plain.commit).isEqualTo(CalculatorOutcome.Commit.DELIVER)
        assertThat(plain.uncappedInsulin).isNull()
        val capped = CalculatorOutcome.of(calculatedInsulin = 4.0, insulinAfterConstraints = 3.0, carbsEquivalent = 0.0, carbs = 30, bolusStep = 0.05)
        assertThat(capped.insulin).isEqualTo(3.0)
        assertThat(capped.uncappedInsulin).isEqualTo(4.0)
    }

    @Test
    fun `carbs without insulin are logged, not delivered`() {
        val outcome = CalculatorOutcome.of(calculatedInsulin = 0.0, insulinAfterConstraints = 0.0, carbsEquivalent = 0.0, carbs = 15, bolusStep = 0.05)
        assertThat(outcome.commit).isEqualTo(CalculatorOutcome.Commit.LOG_CARBS)
    }

    @Test
    fun `a negative result is expressed as a carb equivalent with nothing to confirm`() {
        val outcome = CalculatorOutcome.of(calculatedInsulin = 0.0, insulinAfterConstraints = 0.0, carbsEquivalent = 12.4, carbs = 0, bolusStep = 0.05)
        assertThat(outcome.carbEquivalent).isEqualTo(12)
        assertThat(outcome.commit).isEqualTo(CalculatorOutcome.Commit.NONE)
        assertThat(CalculatorOutcome.of(0.0, 0.0, 0.3, 0, 0.05).carbEquivalent).isNull()
    }

    @Test
    fun `a change of one pump step or more, or in carbs, is drift`() {
        val reviewed = CalculatorOutcome(insulin = 2.40, carbs = 30)
        assertThat(DoseDrift.changed(reviewed, reviewed.copy(insulin = 2.40 + 1e-9), 0.05)).isFalse()
        assertThat(DoseDrift.changed(reviewed, reviewed.copy(insulin = 2.35), 0.05)).isTrue()
        assertThat(DoseDrift.changed(reviewed, reviewed.copy(insulin = 2.50), 0.1)).isTrue()
        assertThat(DoseDrift.changed(reviewed, reviewed.copy(carbs = 25), 0.05)).isTrue()
    }
}
