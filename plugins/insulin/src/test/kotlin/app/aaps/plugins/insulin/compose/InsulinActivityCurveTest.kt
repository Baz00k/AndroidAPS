package app.aaps.plugins.insulin.compose

import app.aaps.core.data.iob.Iob
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.plugins.insulin.InsulinLyumjevPlugin
import app.aaps.plugins.insulin.InsulinOrefRapidActingPlugin
import app.aaps.plugins.insulin.InsulinOrefUltraRapidActingPlugin
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class InsulinActivityCurveTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var config: Config
    @Mock lateinit var hardLimits: HardLimits
    @Mock lateinit var uiInteraction: UiInteraction

    @Test
    fun `activity is percent of the dose per hour rather than normalized to the curve maximum`() {
        val insulin = mock<Insulin>()
        whenever(insulin.iobCalcForTreatment(any(), any(), any())).thenReturn(Iob(activityContrib = 0.002))

        val curve = requireNotNull(buildInsulinActivityCurve(insulin, 5.0, 55))

        // 0.002 U/min for a 1 U dose is 0.2%/min, or 12%/hour, not 100% of the peak.
        assertThat(curve.peak.percentPerHour).isWithin(1e-9).of(12.0)
        assertThat(curve.axisMaxPercentPerHour).isEqualTo(20.0)
        verify(insulin).iobCalcForTreatment(
            argThat { amount == 1.0 && timestamp == 0L }, eq(55 * 60_000L), eq(5.0)
        )
    }

    @Test
    fun `preset previews retain the total dose and mark the true maximum at the configured time`() {
        val insulins = listOf(
            InsulinOrefRapidActingPlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction),
            InsulinOrefUltraRapidActingPlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction),
            InsulinLyumjevPlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
        )
        for (insulin in insulins) {
            for (dia in listOf(5.0, 8.0)) {
                val curve = requireNotNull(buildInsulinActivityCurve(insulin, dia, insulin.peak))
                // Activity integrated over elapsed hours accounts for the entire insulin dose.
                val totalPercent = curve.points.zipWithNext().sumOf { (a, b) ->
                    (a.percentPerHour + b.percentPerHour) / 2.0 * (b.minutes - a.minutes) / 60.0
                }
                assertThat(totalPercent).isWithin(0.2).of(100.0)
                assertThat(curve.points.maxBy { it.percentPerHour }.minutes).isEqualTo(insulin.peak.toDouble())
                assertThat(curve.axisMaxPercentPerHour).isAtLeast(curve.peak.percentPerHour)
                assertThat(curve.points.first().percentPerHour).isEqualTo(0.0)
                assertThat(curve.points.last().percentPerHour).isEqualTo(0.0)
            }
        }
    }

    @Test
    fun `off-grid peak is sampled at its exact time instead of placed halfway across the plot`() {
        val insulin = InsulinOrefUltraRapidActingPlugin(rh, profileFunction, rxBus, aapsLogger, config, hardLimits, uiInteraction)
        val curve = requireNotNull(buildInsulinActivityCurve(insulin, 5.0, insulin.peak))

        assertThat(curve.peak.minutes).isEqualTo(55.0)
        assertThat(curve.peak.minutes / curve.durationMinutes).isWithin(1e-9).of(11.0 / 60.0)
        assertThat(curve.points).contains(curve.peak)
    }

    @Test
    fun `invalid durations and oref peaks are unavailable without sampling or silently clamping`() {
        val insulin = mock<Insulin>()
        val invalid = listOf(
            0.0 to 55, -5.0 to 55, Double.NaN to 55, Double.POSITIVE_INFINITY to 55,
            Double.MAX_VALUE to 55, 0.4 to 5, 5.0 to 0, 5.0 to -1, 5.0 to 150, 5.0 to 151
        )
        invalid.forEach { (dia, peak) ->
            assertThat(buildInsulinActivityCurve(insulin, dia, peak)).isNull()
        }
        verifyNoInteractions(insulin)
    }

    @Test
    fun `nonfinite negative or empty activity is unavailable instead of drawing a misleading scale`() {
        val insulin = mock<Insulin>()
        for (activity in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.001, 0.0)) {
            whenever(insulin.iobCalcForTreatment(any(), any(), any())).thenReturn(Iob(activityContrib = activity))
            assertThat(buildInsulinActivityCurve(insulin, 5.0, 55)).isNull()
        }
    }
}
