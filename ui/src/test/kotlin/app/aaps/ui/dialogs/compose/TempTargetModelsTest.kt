package app.aaps.ui.dialogs.compose

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TT
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TempTargetModelsTest {

    private fun mgdl(target: Double, duration: Int = 60) = TempTargetRequest(target, duration, TtReason.CUSTOM, GlucoseUnit.MGDL)
    private fun mmol(target: Double, duration: Int = 60) = TempTargetRequest(target, duration, TtReason.CUSTOM, GlucoseUnit.MMOL)

    @Test
    fun `mg per dL targets run from 72 to 180`() {
        assertThat(mgdl(72.0).isValid).isTrue()
        assertThat(mgdl(180.0).isValid).isTrue()
        assertThat(mgdl(71.0).isValid).isFalse()
        assertThat(mgdl(181.0).isValid).isFalse()
    }

    @Test
    fun `mmol per L targets run from 4 to 10`() {
        assertThat(mmol(4.0).isValid).isTrue()
        assertThat(mmol(10.0).isValid).isTrue()
        assertThat(mmol(3.9).isValid).isFalse()
        assertThat(mmol(10.1).isValid).isFalse()
    }

    @Test
    fun `duration runs from 5 minutes to seven days`() {
        assertThat(mgdl(100.0, duration = 0).isValid).isFalse()
        assertThat(mgdl(100.0, duration = 4).isValid).isFalse()
        assertThat(mgdl(100.0, duration = 5).isValid).isTrue()
        assertThat(mgdl(100.0, duration = 7 * 24 * 60).isValid).isTrue()
        assertThat(mgdl(100.0, duration = 7 * 24 * 60 + 5).isValid).isFalse()
    }

    @Test
    fun `the sheet opens on the first preset`() {
        val state = TempTargetSheetState(
            GlucoseUnit.MGDL,
            listOf(TtPreset(TtReason.EATING_SOON, 90.0, 45), TtPreset(TtReason.ACTIVITY, 140.0, 90), TtPreset(TtReason.HYPO, 160.0, 60)),
            hasActive = false
        )

        assertThat(state.initial).isEqualTo(TtPreset(TtReason.EATING_SOON, 90.0, 45))
        assertThat(state.presets.map { TempTargetRequest(it.target, it.durationMin, it.reason, state.units).isValid }).containsExactly(true, true, true)
    }

    @Test
    fun `an active target above the range is offered for cancelling and does not seed the draft`() {
        val state = TempTargetSheetState(GlucoseUnit.MGDL, listOf(TtPreset(TtReason.EATING_SOON, 90.0, 45)), hasActive = true)

        assertThat(state.hasActive).isTrue()
        assertThat(state.initial.target).isEqualTo(90.0)
        assertThat(state.targetMax).isEqualTo(180.0)
    }

    @Test
    fun `a confirmed target is recorded in mg per dL with its reason and duration`() {
        val now = 1_800_000_000_000L
        val record = TempTargetRequest(5.0, 45, TtReason.EATING_SOON, GlucoseUnit.MMOL).toRecord(now)

        assertThat(record.timestamp).isEqualTo(now)
        assertThat(record.duration).isEqualTo(45 * 60_000L)
        assertThat(record.reason).isEqualTo(TT.Reason.EATING_SOON)
        assertThat(record.lowTarget).isEqualTo(90.0)
        assertThat(record.highTarget).isEqualTo(90.0)
        assertThat(TempTargetRequest(160.0, 60, TtReason.HYPO, GlucoseUnit.MGDL).toRecord(now).reason).isEqualTo(TT.Reason.HYPOGLYCEMIA)
    }
}
