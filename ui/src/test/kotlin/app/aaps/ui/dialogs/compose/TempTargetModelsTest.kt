package app.aaps.ui.dialogs.compose

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TT
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TempTargetModelsTest {

    @ParameterizedTest
    @CsvSource(
        "MGDL, 72.0, 60, true",
        "MGDL, 180.0, 60, true",
        "MGDL, 71.0, 60, false",
        "MGDL, 181.0, 60, false",
        "MMOL, 4.0, 60, true",
        "MMOL, 10.0, 60, true",
        "MMOL, 3.9, 60, false",
        "MMOL, 10.1, 60, false",
        "MGDL, 100.0, 4, false",
        "MGDL, 100.0, 5, true",
        "MGDL, 100.0, 10080, true",
        "MGDL, 100.0, 10085, false"
    )
    fun `only targets and durations inside the upstream range are valid`(units: GlucoseUnit, target: Double, duration: Int, valid: Boolean) {
        assertThat(TempTargetRequest(target, duration, TtReason.CUSTOM, units).isValid).isEqualTo(valid)
    }

    @Test
    fun `a confirmed mmol target is recorded in mg per dL with its reason and duration`() {
        val now = 1_800_000_000_000L
        val record = TempTargetRequest(5.0, 45, TtReason.HYPO, GlucoseUnit.MMOL).toRecord(now)

        assertThat(record.timestamp).isEqualTo(now)
        assertThat(record.duration).isEqualTo(45 * 60_000L)
        assertThat(record.reason).isEqualTo(TT.Reason.HYPOGLYCEMIA)
        assertThat(record.lowTarget).isEqualTo(90.0)
        assertThat(record.highTarget).isEqualTo(90.0)
    }
}
