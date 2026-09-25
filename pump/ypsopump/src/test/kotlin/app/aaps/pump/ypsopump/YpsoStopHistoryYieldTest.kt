package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptJournal
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptStore
import app.aaps.pump.ypsopump.bolus.YpsoBolusBaseline
import app.aaps.pump.ypsopump.bolus.YpsoBolusBlock
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import app.aaps.pump.ypsopump.comm.YpsoBolusNotification
import app.aaps.pump.ypsopump.bolus.YpsoImmediateBolusController
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class YpsoStopHistoryYieldTest {
    @Test
    fun `a terminal announcement that precedes the identity proof still completes the bolus`() {
        val store = MemoryAttemptStore()
        val journal = YpsoBolusAttemptJournal(store)
        val controller = YpsoImmediateBolusController(mock(), journal, { "serial" }, { null })
        journal.prepare(immediateAttempt())
        journal.beforeDispatch("request-1", 4810, 2_000)
        assertTrue(controller.beginDelivery())

        // Observed on target: a short bolus finished and announced its terminal transition before
        // pollIdentity had journalled the fast sequence, so the announcement had nowhere to attach.
        controller.observeBolusNotification(terminalFast(45), observedAt = 2_400)
        assertNull(store.value?.blockTerminalAt)

        journal.observeFastDelivering("request-1", 45, 100)
        controller.applyPendingTerminal("request-1", YpsoBolusBlock.FAST, 45L)

        assertEquals(2_400L, store.value?.blockTerminalAt)
    }

    @Test
    fun `a terminal announcement for another sequence is never applied`() {
        val store = MemoryAttemptStore()
        val journal = YpsoBolusAttemptJournal(store)
        val controller = YpsoImmediateBolusController(mock(), journal, { "serial" }, { null })
        journal.prepare(immediateAttempt())
        journal.beforeDispatch("request-1", 4810, 2_000)
        assertTrue(controller.beginDelivery())

        controller.observeBolusNotification(terminalFast(45), observedAt = 2_400)
        journal.observeFastDelivering("request-1", 46, 100)
        controller.applyPendingTerminal("request-1", YpsoBolusBlock.FAST, 46L)

        assertNull(store.value?.blockTerminalAt)
    }

    private class MemoryAttemptStore : YpsoBolusAttemptStore {
        var value: YpsoBolusAttempt? = null
        override fun load(): YpsoBolusAttempt? = value
        override fun commit(attempt: YpsoBolusAttempt) { value = attempt }
    }

    private fun terminalFast(sequence: Long) = YpsoBolusNotification(
        YpsoBolusNotification.STATUS_COMPLETED, sequence, YpsoBolusNotification.STATUS_IDLE, 0,
    )

    private fun immediateAttempt() = YpsoBolusAttempt(
        requestId = "request-1",
        pumpSerial = "serial",
        sessionGeneration = "generation",
        treatment = YpsoBolusTreatment.NORMAL,
        requestedCentiUnits = 100,
        payloadHash = "a".repeat(64),
        baseline = YpsoBolusBaseline(44, 44, 100, 1, 2, 21, 1_000),
        createdAt = 1_000,
    )

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
