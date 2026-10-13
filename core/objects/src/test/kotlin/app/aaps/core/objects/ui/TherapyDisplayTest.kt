package app.aaps.core.objects.ui

import app.aaps.core.data.iob.CobInfo
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.ui.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class TherapyDisplayTest {
    private val profileFunction: ProfileFunction = mock()
    private val profile: Profile = mock()
    private val calculator: IobCobCalculator = mock()
    private val formatter: DecimalFormatter = mock()
    private val rh: ResourceHelper = mock<ResourceHelper>().also {
        whenever(it.gs(R.string.value_unavailable_short)).thenReturn("--")
        whenever(it.gs(R.string.iob_no_active_profile)).thenReturn("No active profile")
        whenever(it.gs(R.string.iob_profile_changed)).thenReturn("Profile changed; IOB unavailable")
        whenever(it.gs(R.string.format_carbs, 12)).thenReturn("12 g")
    }

    private fun load(): TherapyDisplay = TherapyDisplay.load(profileFunction, calculator, rh, formatter, "test", iobFormat = { "$it U" }) {
        BasalDisplay("0.80 U/h", "0.60 U/h")
    }

    private fun active() {
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(calculator.calculateIobFromBolus()).thenReturn(IobTotal(1).also { it.iob = 0.3 })
        whenever(calculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(1))
        whenever(calculator.getCobInfo("test")).thenReturn(CobInfo(1, 12.0, 0.0))
    }

    private fun assertUnavailable(display: TherapyDisplay) {
        assertEquals(IobDisplay("--", "--", "--", "No active profile"), display.iob)
        assertEquals(CobDisplay("--"), display.cob)
        assertEquals(BasalDisplay("--"), display.basal)
    }

    @Test fun `profile loss detected by IOB clears sibling cached COB and basal in the published state`() {
        active()
        val previous = load()
        assertEquals("0.3 U", previous.iob.total)
        assertEquals("12 g", previous.cob.value)
        assertEquals("0.80 U/h", previous.basal.rate)
        whenever(calculator.calculateIobFromBolus()).thenAnswer {
            whenever(profileFunction.getProfile()).thenReturn(null)
            IobTotal(1)
        }
        assertUnavailable(load())
    }

    @Test fun `profile replaced during the last sibling read discards the complete snapshot`() {
        active()
        val display = TherapyDisplay.load(profileFunction, calculator, rh, formatter, "test", iobFormat = { "$it U" }) {
            whenever(profileFunction.getProfile()).thenReturn(mock<Profile>())
            BasalDisplay("0.80 U/h")
        }
        assertEquals(IobDisplay("--", "--", "--", "Profile changed; IOB unavailable"), display.iob)
        assertEquals(CobDisplay("--"), display.cob)
        assertEquals(BasalDisplay("--"), display.basal)
    }

    @Test fun `already missing profile does not read cached COB or calculate insulin`() {
        assertUnavailable(load())
        verifyNoInteractions(calculator)
    }

    @Test fun `valid zero and signed IOB keep existing units rounding and activity`() {
        active()
        whenever(calculator.getCobInfo("test")).thenReturn(CobInfo(1, 0.0, 0.0))
        whenever(rh.gs(R.string.format_carbs, 0)).thenReturn("0 g")
        whenever(calculator.calculateIobFromBolus()).thenReturn(IobTotal(1))
        assertEquals(IobDisplay("0.0 U", "0.0 U", "0.0 U", activity = 0.0), load().iob)
        assertEquals("0 g", load().cob.value)
        whenever(calculator.calculateIobFromBolus()).thenReturn(IobTotal(1).also { it.iob = 1.2364; it.activity = 0.0123 })
        whenever(calculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(1).also { it.basaliob = -0.4564; it.activity = -0.0045 })
        val iob = load().iob
        assertEquals("1.236 U", iob.bolus)
        assertEquals("-0.456 U", iob.basal)
        assertEquals(0.78, iob.total.removeSuffix(" U").toDouble(), 0.000001)
        assertEquals(0.0078, requireNotNull(iob.activity), 0.000001)
    }
}
