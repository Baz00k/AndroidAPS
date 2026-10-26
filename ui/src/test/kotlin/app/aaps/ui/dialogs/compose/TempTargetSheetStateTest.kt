package app.aaps.ui.dialogs.compose

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
    fun `editor uses the upstream bounds and duration cap in both units`() {
        val mgdl = state(GlucoseUnit.MGDL)
        assertThat(mgdl.unitStep).isEqualTo(1.0)
        assertThat(mgdl.unitLabel).isEqualTo("mg/dL")
        assertThat(mgdl.targetMin).isEqualTo(72.0)
        assertThat(mgdl.targetMax).isEqualTo(180.0)
        assertThat(mgdl.decimals).isEqualTo(0)
        assertThat(mgdl.durationStep).isEqualTo(5)
        assertThat(mgdl.durationMax).isEqualTo(10_080)

        val mmol = state(GlucoseUnit.MMOL)
        assertThat(mmol.unitStep).isEqualTo(0.1)
        assertThat(mmol.unitLabel).isEqualTo("mmol/L")
        assertThat(mmol.targetMin).isEqualTo(4.0)
        assertThat(mmol.targetMax).isEqualTo(10.0)
        assertThat(mmol.decimals).isEqualTo(1)
    }

    @Test
    fun `only an in-range target with a positive duration becomes a request`() {
        val mgdl = state(GlucoseUnit.MGDL)
        assertThat(mgdl.request(72.0, 60, TtReason.CUSTOM)).isNotNull()
        assertThat(mgdl.request(180.0, 60, TtReason.CUSTOM)).isNotNull()
        assertThat(mgdl.request(71.9, 60, TtReason.CUSTOM)).isNull()
        assertThat(mgdl.request(180.1, 60, TtReason.CUSTOM)).isNull()

        val mmol = state(GlucoseUnit.MMOL)
        assertThat(mmol.request(4.0, 60, TtReason.CUSTOM)).isNotNull()
        assertThat(mmol.request(10.0, 60, TtReason.CUSTOM)).isNotNull()
        assertThat(mmol.request(3.9, 60, TtReason.CUSTOM)).isNull()
        assertThat(mmol.request(10.1, 60, TtReason.CUSTOM)).isNull()

        assertThat(mgdl.request(101.0, 0, TtReason.CUSTOM)).isNull()
        assertThat(mgdl.request(101.0, mgdl.durationMax, TtReason.CUSTOM)).isNotNull()
        assertThat(mgdl.request(101.0, mgdl.durationMax + 1, TtReason.CUSTOM)).isNull()
    }

    @Test
    fun `an active target above the new maximum is not loaded or clamped into the editor`() {
        val state = state(activeTargetMgdl = 200.0)
        assertThat(state.hasActive).isTrue()
        assertThat(state.initialTarget).isEqualTo(101.0)
    }

    @Test
    fun `a confirmed request stores the reason, duration and target in mgdl`() {
        val mgdl = state(GlucoseUnit.MGDL).request(150.0, 45, TtReason.HYPO)!!.toTempTarget(timestamp = 1_000L, units = GlucoseUnit.MGDL)
        assertThat(mgdl.timestamp).isEqualTo(1_000L)
        assertThat(mgdl.duration).isEqualTo(TimeUnit.MINUTES.toMillis(45))
        assertThat(mgdl.reason).isEqualTo(TT.Reason.HYPOGLYCEMIA)
        assertThat(mgdl.lowTarget).isEqualTo(150.0)
        assertThat(mgdl.highTarget).isEqualTo(150.0)

        val mmol = state(GlucoseUnit.MMOL).request(5.5, 60, TtReason.EATING_SOON)!!.toTempTarget(timestamp = 0L, units = GlucoseUnit.MMOL)
        assertThat(mmol.lowTarget).isEqualTo(99.0)
        assertThat(mmol.highTarget).isEqualTo(99.0)
        assertThat(mmol.reason).isEqualTo(TT.Reason.EATING_SOON)
    }
}
