package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptJournal
import app.aaps.pump.ypsopump.bolus.YpsoImmediateBolusController
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class YpsoStopHistoryYieldTest {
    @Test
    fun `Stop interrupts the in flight history read but permits terminal confirmation reads`() {
        val controller = YpsoImmediateBolusController(mock(), YpsoBolusAttemptJournal(mock()), { "serial" }, { null })
        assertTrue(controller.beginDelivery())
        controller.requestStop()
        val historyMustYield = controller::consumeHistoryYield
        assertTrue(historyMustYield())
        // Stop retries must retain cancellation intent without starving terminal history forever.
        controller.requestStop()
        assertTrue(controller.cancellationRequested)
        assertFalse(historyMustYield())
    }
}
