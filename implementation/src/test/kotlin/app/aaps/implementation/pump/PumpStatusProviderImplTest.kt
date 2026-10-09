package app.aaps.implementation.pump

import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PumpStatusProviderImplTest {
    @Test fun `last bolus short status retains historical precision`() {
        val pump = mock<Pump>()
        val activePlugin = mock<ActivePlugin>()
        val pumpSync = mock<PumpSync>()
        val dateUtil = mock<DateUtil>()
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(pump.isInitialized()).thenReturn(true)
        whenever(pump.lastBolusAmount).thenReturn(0.025)
        whenever(pump.lastBolusTime).thenReturn(1000L)
        whenever(pump.pumpSpecificShortStatus(false)).thenReturn("")
        whenever(pumpSync.expectedPumpState()).thenReturn(PumpSync.PumpState(null, null, null, null, "test"))
        whenever(dateUtil.timeString(1000)).thenReturn("12:30")
        val rh = Mockito.mock(ResourceHelper::class.java) { invocation ->
            "Last bolus %1\$s U at %2\$s".format(*invocation.arguments.drop(1).toTypedArray())
        }
        val status = PumpStatusProviderImpl(activePlugin, pumpSync, mock(), mock(), rh, dateUtil, mock(), mock(), mock()).shortStatus(false)
        assertThat(status.replace(',', '.')).isEqualTo("Last bolus 0.025 U at 12:30")
    }
}
