package app.aaps.core.objects.ui

import app.aaps.core.data.aps.BasalData
import app.aaps.core.data.iob.CobInfo
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.objects.extensions.generateCOBString
import app.aaps.core.ui.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class UnavailableTherapyDisplayTest {
    private val rh: ResourceHelper = mock<ResourceHelper>().also {
        whenever(it.gs(R.string.value_unavailable_short)).thenReturn("--")
        whenever(it.gs(R.string.cob_unavailable)).thenReturn("COB calculation unavailable")
        whenever(it.gs(R.string.pump_base_basal_rate, 0.0)).thenReturn("0.00 U/h")
        whenever(it.gs(R.string.pump_base_basal_rate, 0.8)).thenReturn("0.80 U/h")
        whenever(it.gs(R.string.pump_base_basal_rate, 1.2)).thenReturn("1.20 U/h")
        whenever(it.gs(R.string.format_carbs, 0)).thenReturn("0 g")
        whenever(it.gs(R.string.format_carbs, 12)).thenReturn("12 g")
    }
    private val formatter: DecimalFormatter = mock<DecimalFormatter>().also {
        whenever(it.to0Decimal(0.0)).thenReturn("0")
        whenever(it.to0Decimal(12.0)).thenReturn("12")
        whenever(it.to0Decimal(5.0)).thenReturn("5")
    }

    @Test fun `missing COB uses the same unitless placeholder in home and compact status`() {
        for (compact in listOf(false, true)) {
            assertEquals(CobDisplay("--", "COB calculation unavailable"), CobDisplay.from(null, rh, formatter, compact))
            assertEquals(CobDisplay("--", "COB calculation unavailable"), CobDisplay.from(CobInfo(1, null, 5.0), rh, formatter, compact))
        }
        assertEquals("--", CobInfo(1, null, 0.0).generateCOBString(formatter))
    }

    @Test fun `calculated zero and future carbs retain units and existing formatting`() {
        assertEquals(CobDisplay("0 g"), CobDisplay.from(CobInfo(1, 0.0, 0.0), rh, formatter))
        assertEquals(CobDisplay("0g"), CobDisplay.from(CobInfo(1, 0.0, 0.0), rh, formatter, true))
        assertEquals(CobDisplay("12 g(5)"), CobDisplay.from(CobInfo(1, 12.0, 5.0), rh, formatter))
        assertEquals(CobDisplay("12(5)g"), CobDisplay.from(CobInfo(1, 12.0, 5.0), rh, formatter, true))
    }

    @Test fun `missing basal is not a confirmed zero but actual zero basal is valid`() {
        assertEquals(BasalDisplay("--"), BasalDisplay.from(null, rh))
        assertEquals(BasalDisplay("0.00 U/h"), BasalDisplay.from(BasalData(), rh))
        assertEquals(BasalDisplay("1.20 U/h", "0.80 U/h"), BasalDisplay.from(BasalData().also {
            it.basal = 0.8; it.tempBasalAbsolute = 1.2; it.isTempBasalRunning = true
        }, rh))
    }
}
