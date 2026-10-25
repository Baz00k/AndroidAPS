package app.aaps.ui.dialogs.compose

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TT
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class TempTargetSheetStateTest {

    private val presets = listOf(
        TtPreset(TtReason.EATING_SOON, "Eating soon", 90.0, 45),
        TtPreset(TtReason.ACTIVITY, "Activity", 140.0, 60),
        TtPreset(TtReason.HYPO, "Hypo", 100.0, 30)
    )

    private fun state(units: GlucoseUnit = GlucoseUnit.MGDL, activeTargetMgdl: Double? = null) =
        tempTargetSheetState(
            units = units,
            presets = presets,
            initialTarget = if (units == GlucoseUnit.MMOL) 5.6 else 101.0,
            initialDuration = 60,
            activeTargetMgdl = activeTargetMgdl
        )

    @Test
    fun `mgdl editor uses the upstream temporary target range`() {
        val state = state(GlucoseUnit.MGDL)
        assertThat(state.targetMin).isEqualTo(Constants.MIN_TT_MGDL)
        assertThat(state.targetMax).isEqualTo(Constants.MAX_TT_MGDL)
        assertThat(state.unitStep).isEqualTo(1.0)
        assertThat(state.decimals).isEqualTo(0)
    }

    @Test
    fun `mmol editor uses the upstream temporary target range`() {
        val state = state(GlucoseUnit.MMOL)
        assertThat(state.targetMin).isEqualTo(Constants.MIN_TT_MMOL)
        assertThat(state.targetMax).isEqualTo(Constants.MAX_TT_MMOL)
        assertThat(state.unitStep).isEqualTo(0.1)
        assertThat(state.decimals).isEqualTo(1)
    }

    @Test
    fun `duration range is the profile switch maximum with the existing step`() {
        val state = state()
        assertThat(state.durationStep).isEqualTo(5)
        assertThat(state.durationMax).isEqualTo(Constants.MAX_PROFILE_SWITCH_DURATION.toInt())
    }

    @Test
    fun `only an in-range target with a positive duration becomes a request`() {
        val state = state()
        assertThat(state.request(72.0, 60, TtReason.CUSTOM)).isNotNull()
        assertThat(state.request(180.0, 60, TtReason.CUSTOM)).isNotNull()
        assertThat(state.request(71.9, 60, TtReason.CUSTOM)).isNull()
        assertThat(state.request(180.1, 60, TtReason.CUSTOM)).isNull()
        assertThat(state.request(101.0, 0, TtReason.CUSTOM)).isNull()
        assertThat(state.request(101.0, state.durationMax, TtReason.CUSTOM)).isNotNull()
        assertThat(state.request(101.0, state.durationMax + 1, TtReason.CUSTOM)).isNull()
    }

    @Test
    fun `an active target above the new maximum is not loaded or clamped into the editor`() {
        val state = state(activeTargetMgdl = 200.0)
        assertThat(state.hasActive).isTrue()
        assertThat(state.initialTarget).isEqualTo(101.0)
    }

    @Test
    fun `a confirmed request is stored in mgdl with its reason and duration`() {
        val request = state().request(150.0, 45, TtReason.HYPO)
        val temporaryTarget = request!!.toTempTarget(timestamp = 1_000L, units = GlucoseUnit.MGDL)

        assertThat(temporaryTarget.timestamp).isEqualTo(1_000L)
        assertThat(temporaryTarget.duration).isEqualTo(TimeUnit.MINUTES.toMillis(45))
        assertThat(temporaryTarget.reason).isEqualTo(TT.Reason.HYPOGLYCEMIA)
        assertThat(temporaryTarget.lowTarget).isEqualTo(150.0)
        assertThat(temporaryTarget.highTarget).isEqualTo(150.0)
    }

    @Test
    fun `an mmol target is stored in mgdl`() {
        val request = state(GlucoseUnit.MMOL).request(5.5, 60, TtReason.EATING_SOON)
        val temporaryTarget = request!!.toTempTarget(timestamp = 0L, units = GlucoseUnit.MMOL)

        assertThat(temporaryTarget.lowTarget).isEqualTo(99.0)
        assertThat(temporaryTarget.highTarget).isEqualTo(99.0)
        assertThat(temporaryTarget.reason).isEqualTo(TT.Reason.EATING_SOON)
    }

    @Test
    fun `presets keep the values configured in settings`() {
        assertThat(state().presets).isEqualTo(presets)
    }
}
