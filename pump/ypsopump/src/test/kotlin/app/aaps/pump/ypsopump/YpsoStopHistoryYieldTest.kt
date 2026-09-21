package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptJournal
import app.aaps.pump.ypsopump.bolus.YpsoImmediateBolusController
import app.aaps.pump.ypsopump.ble.YpsoBleManager
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

    @Test
    fun `history yield waits for a selector safe boundary instead of hard cancelling ownership`() {
        val attempt = YpsoBleManager.HistoryReadAttempt()
        var hardCancelled = false
        attempt.onCancel = { hardCancelled = true }

        assertTrue(attempt.requestYield())
        assertTrue(attempt.isActive)
        assertTrue(attempt.shouldYield)
        assertFalse(hardCancelled)
    }
}
