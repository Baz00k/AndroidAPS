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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
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
        assertNull(display.iob.activity)
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

    @Test fun `profile removed while COB is read discards the complete display snapshot`() {
        active()
        whenever(calculator.getCobInfo("test")).thenAnswer {
            whenever(profileFunction.getProfile()).thenReturn(null)
            CobInfo(1, 12.0, 0.0)
        }
        assertUnavailable(load())
    }

    @Test fun `profile removed while basal is read discards the complete display snapshot`() {
        active()
        val display = TherapyDisplay.load(profileFunction, calculator, rh, formatter, "test", iobFormat = { "$it U" }) {
            whenever(profileFunction.getProfile()).thenReturn(null)
            BasalDisplay("0.80 U/h")
        }
        assertUnavailable(display)
    }

    @Test fun `already missing profile does not read cached COB or calculate insulin`() {
        assertUnavailable(load())
        verify(calculator, never()).getCobInfo("test")
        verify(calculator, never()).calculateIobFromBolus()
    }
}
