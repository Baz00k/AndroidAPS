package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptJournal
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptStore
import app.aaps.pump.ypsopump.bolus.YpsoBolusBaseline
import app.aaps.pump.ypsopump.bolus.YpsoBolusBlock
import app.aaps.pump.ypsopump.bolus.YpsoBolusOutcome
import app.aaps.pump.ypsopump.bolus.YpsoBolusShape
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoBolusAttemptJournalTest {
    private class MemoryStore : YpsoBolusAttemptStore {
        var value: YpsoBolusAttempt? = null
        var failNextCommit = false
        override fun load() = value
        override fun commit(attempt: YpsoBolusAttempt) {
            if (failNextCommit) { failNextCommit = false; error("injected commit failure") }
            value = attempt
        }
    }

    @Test
    fun `dispatch boundary is durable and restart never turns it into a retryable request`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt())
        journal.beforeDispatch("request-1", counter = 4810, now = 2_000)

        val afterRestart = YpsoBolusAttemptJournal(store)
        assertTrue(requireNotNull(store.value).inhibitsAutomatedDelivery)
        assertThrows(IllegalArgumentException::class.java) { afterRestart.prepare(attempt(requestId = "request-2")) }
        assertThrows(IllegalArgumentException::class.java) { afterRestart.beforeDispatch("request-1", 4811, 3_000) }
    }

    @Test
    fun `persist failure before dispatch leaves the prior certain-not-sent state`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt())
        store.failNextCommit = true

        assertThrows(IllegalStateException::class.java) { journal.beforeDispatch("request-1", 4810, 2_000) }
        assertEquals(YpsoBolusOutcome.NOT_SENT, store.value?.outcome)
        assertEquals(null, store.value?.dispatchCounter)
    }

    @Test
    fun `ACK never creates delivered insulin`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt())
        journal.beforeDispatch("request-1", 4810, 2_000)
        val acked = journal.transportAccepted("request-1")

        assertEquals(YpsoBolusOutcome.ACCEPTED_UNVERIFIED, acked.outcome)
        assertEquals(null, acked.confirmedCentiUnits)
        assertEquals(null, acked.deliveryTimestamp)
        assertTrue(acked.inhibitsAutomatedDelivery)
    }

    @Test
    fun `delivery binds a changed status sequence and exact programmed amount`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt())
        journal.beforeDispatch("request-1", 4810, 2_000)

        assertThrows(IllegalArgumentException::class.java) { journal.observeFastDelivering("request-1", 44, 100) }
        assertThrows(IllegalArgumentException::class.java) { journal.observeFastDelivering("request-1", 45, 90) }
        val delivering = journal.observeFastDelivering("request-1", 45, 100)
        assertEquals(YpsoBolusOutcome.DELIVERING, delivering.outcome)
        assertEquals(45, delivering.pumpFastSequence)
    }

    @Test
    fun `extended delivery binds only the slow block identity and its programmed total`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt(shape = YpsoBolusShape.EXTENDED))
        journal.beforeDispatch("request-1", 4810, 2_000)

        assertThrows(IllegalArgumentException::class.java) { journal.observeFastDelivering("request-1", 45, 100) }
        assertThrows(IllegalArgumentException::class.java) { journal.observeSlowDelivering("request-1", 9, 100) }
        assertThrows(IllegalArgumentException::class.java) { journal.observeSlowDelivering("request-1", 46, 90) }
        val delivering = journal.observeSlowDelivering("request-1", 46, 100)
        assertEquals(YpsoBolusOutcome.DELIVERING, delivering.outcome)
        assertEquals(46, delivering.pumpSlowSequence)
        assertEquals(null, delivering.pumpFastSequence)

        val cancelled = delivering
            .let { journal.requestCancel("request-1", "cancel-1", 4811, YpsoBolusBlock.SLOW) }
            .let { journal.confirmTerminal("request-1", 17, 2_500, YpsoBolusBlock.SLOW, 46, 101, cancelled = true) }
        assertEquals(YpsoBolusOutcome.CANCELLED_PARTIAL, cancelled.outcome)
        assertEquals(0.17, cancelled.confirmedUnits)
    }

    @Test
    fun `combination delivery binds the slow block against the whole programmed total`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt(shape = YpsoBolusShape.COMBINED, requestedCentiUnits = 100, immediateCentiUnits = 40))
        journal.beforeDispatch("request-1", 4810, 2_000)

        val delivering = journal.observeSlowDelivering("request-1", 47, 100)
        assertEquals(YpsoBolusOutcome.DELIVERING, delivering.outcome)
        assertEquals(47, delivering.pumpSlowSequence)
        assertEquals(YpsoBolusBlock.SLOW, delivering.provenCancelBlock)

        val completed = journal.confirmTerminal("request-1", 100, 2_600, YpsoBolusBlock.SLOW, 47, 101, cancelled = false)
        assertEquals(YpsoBolusOutcome.COMPLETED, completed.outcome)
        assertEquals(1.0, completed.confirmedUnits)
    }

    @Test
    fun `pump confirmed partial and completed amounts remain distinct terminal outcomes`() {
        val partialStore = MemoryStore()
        val partial = YpsoBolusAttemptJournal(partialStore)
        partial.prepare(attempt())
        partial.beforeDispatch("request-1", 4810, 2_000)
        partial.observeFastDelivering("request-1", 45, 100)
        partial.requestCancel("request-1", "cancel-1", 4811, YpsoBolusBlock.FAST)
        partial.unresolved("request-1", "awaiting terminal history")
        val stopped = partial.confirmTerminal("request-1", 37, 2_500, YpsoBolusBlock.FAST, 45, 101, cancelled = true)
        assertEquals(YpsoBolusOutcome.CANCELLED_PARTIAL, stopped.outcome)
        assertEquals(0.37, stopped.confirmedUnits)
        assertFalse(stopped.inhibitsAutomatedDelivery)
        assertEquals(null, stopped.detail)

        val completeStore = MemoryStore()
        val complete = YpsoBolusAttemptJournal(completeStore)
        complete.prepare(attempt())
        complete.beforeDispatch("request-1", 4810, 2_000)
        complete.observeFastDelivering("request-1", 45, 100)
        val done = complete.confirmTerminal("request-1", 100, 2_600, YpsoBolusBlock.FAST, 45, 101, cancelled = false)
        assertEquals(YpsoBolusOutcome.COMPLETED, done.outcome)
        assertEquals(1.0, done.confirmedUnits)
    }

    @Test
    fun `stale completion and second cancellation cannot attach to the attempt`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt())
        journal.beforeDispatch("request-1", 4810, 2_000)
        journal.observeFastDelivering("request-1", 45, 100)
        journal.requestCancel("request-1", "cancel-1", 4811, YpsoBolusBlock.FAST)

        assertThrows(IllegalArgumentException::class.java) {
            journal.requestCancel("request-1", "cancel-2", 4812, YpsoBolusBlock.FAST)
        }
        assertThrows(IllegalArgumentException::class.java) {
            journal.confirmTerminal("request-1", 100, 2_500, YpsoBolusBlock.FAST, 44, 101, cancelled = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            journal.confirmTerminal("request-1", 100, 2_500, YpsoBolusBlock.FAST, 45, 100, cancelled = false)
        }
    }

    @Test
    fun `terminal history cannot establish command identity after the fact`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt())
        journal.beforeDispatch("request-1", 4810, 2_000)

        assertThrows(IllegalArgumentException::class.java) {
            journal.confirmTerminal("request-1", 100, 2_500, YpsoBolusBlock.FAST, 45, 101, cancelled = false)
        }
        assertEquals(YpsoBolusOutcome.POSSIBLY_APPLIED, store.value?.outcome)
    }

    @Test
    fun `stable unchanged status and history can close an unknown dispatch as no effect`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt())
        journal.beforeDispatch("request-1", 4810, 2_000)
        journal.unresolved("request-1", "initial reconciliation had no compatible terminal row")

        val rejected = journal.reconcileNoEffect(
            "request-1",
            "status remained idle at baseline sequence and stable history contained no new rows",
        )

        assertEquals(YpsoBolusOutcome.PROVEN_REJECTED, rejected.outcome)
        assertEquals(4810, rejected.dispatchCounter)
        assertFalse(rejected.inhibitsAutomatedDelivery)
        assertEquals(null, rejected.confirmedCentiUnits)
    }

    @Test
    fun `no-effect reconciliation cannot erase a proven delivery identity`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt())
        journal.beforeDispatch("request-1", 4810, 2_000)
        journal.observeFastDelivering("request-1", 45, 100)

        assertThrows(IllegalArgumentException::class.java) {
            journal.reconcileNoEffect("request-1", "contradictory negative observation")
        }
        assertEquals(YpsoBolusOutcome.DELIVERING, store.value?.outcome)
    }

    @Test
    fun `proven not sent cancellation restores delivery uncertainty without claiming stopped`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt())
        journal.beforeDispatch("request-1", 4810, 2_000)
        journal.transportAccepted("request-1")
        journal.observeFastDelivering("request-1", 45, 100)
        journal.requestCancel("request-1", "cancel-1", 4811, YpsoBolusBlock.FAST)

        val restored = journal.cancelNotSent("request-1", "cancel dispatch refused")

        assertEquals(YpsoBolusOutcome.DELIVERING, restored.outcome)
        assertEquals(null, restored.cancelRequestId)
        assertEquals(null, restored.cancelCounter)
        assertEquals(null, restored.cancelBlock)
        assertTrue(restored.inhibitsAutomatedDelivery)
    }

    @Test
    fun `proven not sent extended cancellation restores delivering not accepted-unverified`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt(shape = YpsoBolusShape.EXTENDED))
        journal.beforeDispatch("request-1", 4810, 2_000)
        journal.transportAccepted("request-1")
        journal.observeSlowDelivering("request-1", 46, 100)
        journal.requestCancel("request-1", "cancel-1", 4811, YpsoBolusBlock.SLOW)

        val restored = journal.cancelNotSent("request-1", "cancel dispatch refused")

        assertEquals(YpsoBolusOutcome.DELIVERING, restored.outcome)
        assertEquals(null, restored.cancelBlock)
        assertEquals(null, restored.cancelObservedCentiUnits)
    }

    @Test
    fun `cancellation requires a persisted proven block sequence identity`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt())
        journal.beforeDispatch("request-1", 4810, 2_000)
        journal.transportAccepted("request-1")

        assertThrows(IllegalArgumentException::class.java) {
            journal.requestCancel("request-1", "cancel-1", 4811, YpsoBolusBlock.FAST)
        }
        assertEquals(YpsoBolusOutcome.ACCEPTED_UNVERIFIED, store.value?.outcome)
        assertEquals(null, store.value?.cancelRequestId)
    }

    @Test
    fun `cancel delivery evidence is retained monotonically and capped by the request`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt(shape = YpsoBolusShape.EXTENDED))
        journal.beforeDispatch("request-1", 4810, 2_000)
        journal.observeSlowDelivering("request-1", 46, 100)
        journal.requestCancel("request-1", "cancel-1", 4811, YpsoBolusBlock.SLOW)

        journal.observeCancelDelivery("request-1", 17)
        val observed = journal.observeCancelDelivery("request-1", 12)
        assertEquals(17, observed.cancelObservedCentiUnits)
        assertThrows(IllegalArgumentException::class.java) { journal.observeCancelDelivery("request-1", 101) }
    }

    @Test
    fun `fast sequence permits only unsigned forward movement including wrap`() {
        val store = MemoryStore()
        val journal = YpsoBolusAttemptJournal(store)
        journal.prepare(attempt().copy(baseline = attempt().baseline.copy(fastSequence = 0xffff_ffffL)))
        journal.beforeDispatch("request-1", 4810, 2_000)
        assertEquals(YpsoBolusOutcome.DELIVERING, journal.observeFastDelivering("request-1", 0, 100).outcome)

        val backwardsStore = MemoryStore()
        val backwards = YpsoBolusAttemptJournal(backwardsStore)
        backwards.prepare(attempt())
        backwards.beforeDispatch("request-1", 4810, 2_000)
        assertThrows(IllegalArgumentException::class.java) { backwards.observeFastDelivering("request-1", 43, 100) }
    }

    private fun attempt(
        requestId: String = "request-1",
        shape: YpsoBolusShape = YpsoBolusShape.IMMEDIATE,
        requestedCentiUnits: Int = 100,
        immediateCentiUnits: Int = 0,
    ) = YpsoBolusAttempt(
        requestId = requestId,
        pumpSerial = "10000001",
        sessionGeneration = "generation-1",
        treatment = YpsoBolusTreatment.NORMAL,
        requestedCentiUnits = requestedCentiUnits,
        payloadHash = "ab".repeat(32),
        baseline = YpsoBolusBaseline(
            fastSequence = 44, slowSequence = 45, historyPumpId = 100,
            historyFingerprintHigh = 10, historyFingerprintLow = 20,
            pumpReboot = 21, observedAt = 1_000,
        ),
        createdAt = 1_500,
        shape = shape,
        durationMinutes = if (shape == YpsoBolusShape.IMMEDIATE) 0 else 15,
        immediateCentiUnits = immediateCentiUnits,
    )
}
