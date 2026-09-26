package app.aaps.plugins.main.general.overview

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TT
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TargetDisplayTest : TestBaseWithProfile() {

    private val start = 1_000_000L
    private fun temporary(low: Double = 140.0, high: Double = 160.0) = TT(
        timestamp = start, reason = TT.Reason.ACTIVITY,
        lowTarget = low, highTarget = high, duration = 30 * 60_000L
    )
    private fun display(tt: TT? = temporary(), now: Long = start) = TargetDisplay.at(now, tt, 90.0, 110.0)

    @Test fun `active target replaces profile for range and current glucose comparison`() {
        val target = display()
        assertThat(target.range(GlucoseUnit.MGDL, profileUtil)).isEqualTo("140 - 160 mg/dL")
        assertThat(target.stateLine(120.0, GlucoseUnit.MGDL, profileUtil)).isEqualTo("20 below target")
        assertThat(target.stateLine(150.0, GlucoseUnit.MGDL, profileUtil)).isEqualTo("In target range")
        assertThat(target.stateLine(180.0, GlucoseUnit.MGDL, profileUtil)).isEqualTo("20 above target")
        assertThat(target.stateLine(140.0, GlucoseUnit.MGDL, profileUtil)).isEqualTo("In target range")
        assertThat(target.stateLine(160.0, GlucoseUnit.MGDL, profileUtil)).isEqualTo("In target range")
    }

    @Test fun `mmol conversion applies to range and comparison once`() {
        val target = display(temporary(108.0, 144.0))
        assertThat(target.range(GlucoseUnit.MMOL, profileUtil)).isEqualTo("6.0 - 8.0 mmol/L")
        assertThat(target.stateLine(90.0, GlucoseUnit.MMOL, profileUtil)).isEqualTo("1.0 below target")
        assertThat(target.stateLine(162.0, GlucoseUnit.MMOL, profileUtil)).isEqualTo("1.0 above target")
    }

    @Test fun `single targets do not repeat the value`() {
        assertThat(display(temporary(108.0, 108.0)).range(GlucoseUnit.MMOL, profileUtil)).isEqualTo("6.0 mmol/L")
        assertThat(display(temporary(108.0, 108.0)).range(GlucoseUnit.MGDL, profileUtil)).isEqualTo("108 mg/dL")
    }

    @Test fun `start change cancel and expiry select the effective target`() {
        val tt = temporary()
        assertThat(display(tt, start - 1).temporaryTarget).isNull()
        assertThat(display(tt, start).low).isEqualTo(140.0)
        assertThat(display(temporary(120.0, 120.0)).low).isEqualTo(120.0)
        assertThat(display(tt, tt.end - 1).temporaryTarget).isEqualTo(tt)
        listOf(display(null), display(tt, tt.end), display(tt, tt.end + 1), display(tt.copy(isValid = false))).forEach {
            assertThat(it.temporaryTarget).isNull()
            assertThat(it.low).isEqualTo(90.0)
            assertThat(it.high).isEqualTo(110.0)
        }
    }

    @Test fun `active status remains available without profile or glucose`() {
        val target = TargetDisplay.at(start, temporary(), null, null)
        assertThat(target.range(GlucoseUnit.MGDL, profileUtil)).isEqualTo("140 - 160 mg/dL")
        assertThat(target.stateLine(null, GlucoseUnit.MGDL, profileUtil)).isEmpty()
        val absent = TargetDisplay.at(start, null, null, null)
        assertThat(absent.range(GlucoseUnit.MGDL, profileUtil)).isEmpty()
        assertThat(absent.stateLine(100.0, GlucoseUnit.MGDL, profileUtil)).isEmpty()
    }
}
