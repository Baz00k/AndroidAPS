package app.aaps.core.objects.ui

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.Locale

class IobDisplayTest {
    private val calculator: IobCobCalculator = mock()
    private val profileFunction: ProfileFunction = mock()
    private val profile: Profile = mock()
    private val rh: ResourceHelper = mock<ResourceHelper>().also {
        whenever(it.gs(R.string.value_unavailable_short)).thenReturn("--")
        whenever(it.gs(R.string.iob_no_active_profile)).thenReturn("No active profile")
        whenever(it.gs(R.string.iob_profile_changed)).thenReturn("Profile changed; IOB unavailable")
        whenever(it.gs(eq(R.string.format_insulin_units), any<Double>())).thenAnswer { call ->
            String.format(Locale.US, "%.2f U", call.getArgument<Double>(1))
        }
    }

    @Test fun `no active profile makes total and both parts unavailable without reading empty calculator totals`() {
        val display = IobDisplay.load(profileFunction, calculator, rh)
        assertEquals(IobDisplay("--", "--", "--", "No active profile"), display)
        assertFalse(display.isAvailable)
        assertNull(display.activity)
        verifyNoInteractions(calculator)
    }

    @Test fun `compact status format uses the guarded sample and activity without adding units to unavailable values`() {
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(calculator.calculateIobFromBolus()).thenReturn(IobTotal(1).also { it.iob = 0.3; it.activity = 0.0123 })
        whenever(calculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(1).also { it.basaliob = -0.1; it.activity = -0.0045 })
        val display = IobDisplay.load(profileFunction, calculator, rh) { String.format(Locale.US, "%.2f", it) }
        assertEquals(IobDisplay("0.20", "0.30", "-0.10", activity = 0.0123 - 0.0045), display)
        assertEquals(0.0078, requireNotNull(display.activity), 0.000001)
    }

    @Test fun `active profile keeps rounding and negative basal contribution`() {
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(calculator.calculateIobFromBolus()).thenReturn(IobTotal(1).also { it.iob = 1.236 })
        whenever(calculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(1).also { it.basaliob = -0.456 })
        assertEquals(IobDisplay("0.78 U", "1.24 U", "-0.46 U", activity = 0.0), IobDisplay.load(profileFunction, calculator, rh))
    }

    @Test fun `real zero with a profile is available and does not survive profile removal`() {
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(calculator.calculateIobFromBolus()).thenReturn(IobTotal(1))
        whenever(calculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(1))
        assertEquals(IobDisplay("0.00 U", "0.00 U", "0.00 U", activity = 0.0), IobDisplay.load(profileFunction, calculator, rh))
        whenever(profileFunction.getProfile()).thenReturn(null)
        assertEquals(IobDisplay("--", "--", "--", "No active profile"), IobDisplay.load(profileFunction, calculator, rh))
    }

    @Test fun `activating a profile replaces unavailable values with calculated IOB`() {
        assertEquals("--", IobDisplay.load(profileFunction, calculator, rh).total)
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(calculator.calculateIobFromBolus()).thenReturn(IobTotal(1).also { it.iob = 0.3 })
        whenever(calculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(1))
        assertEquals(IobDisplay("0.30 U", "0.30 U", "0.00 U", activity = 0.0), IobDisplay.load(profileFunction, calculator, rh))
    }

    @Test fun `profile removed during bolus calculation cannot publish the calculator's empty zero`() {
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(calculator.calculateIobFromBolus()).thenAnswer {
            whenever(profileFunction.getProfile()).thenReturn(null)
            IobTotal(1)
        }
        whenever(calculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(1))
        assertEquals(IobDisplay("--", "--", "--", "No active profile"), IobDisplay.load(profileFunction, calculator, rh))
    }

    @Test fun `profile removed during basal calculation discards both parts`() {
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(calculator.calculateIobFromBolus()).thenReturn(IobTotal(1).also { it.iob = 0.3 })
        whenever(calculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenAnswer {
            whenever(profileFunction.getProfile()).thenReturn(null)
            IobTotal(1)
        }
        assertEquals(IobDisplay("--", "--", "--", "No active profile"), IobDisplay.load(profileFunction, calculator, rh))
    }

    @Test fun `a different profile during calculation cannot publish mixed totals`() {
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(calculator.calculateIobFromBolus()).thenAnswer {
            whenever(profileFunction.getProfile()).thenReturn(mock<Profile>())
            IobTotal(1).also { it.iob = 0.3 }
        }
        whenever(calculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(1))
        assertEquals(IobDisplay("--", "--", "--", "Profile changed; IOB unavailable"), IobDisplay.load(profileFunction, calculator, rh))
    }
}
