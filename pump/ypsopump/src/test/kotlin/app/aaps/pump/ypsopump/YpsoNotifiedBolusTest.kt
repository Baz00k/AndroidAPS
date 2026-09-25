package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptFileStore
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptJournal
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptStore
import app.aaps.pump.ypsopump.bolus.YpsoBolusBaseline
import app.aaps.pump.ypsopump.bolus.YpsoBolusOutcome
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import app.aaps.pump.ypsopump.bolus.YpsoImmediateBolusController
import app.aaps.pump.ypsopump.comm.YpsoBolusNotification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.File

/**
 * A fast bolus can end before any status read shows its programmed amount (bench: 0.4 U finished in
 * about a second, every read then showed an idle block). The pump still names its sequence on
 * CONTROL_NOTIFY; that sequence links the provisional record to the history row, and nothing else.
 */
class YpsoNotifiedBolusTest {
    private class MemoryStore : YpsoBolusAttemptStore {
        val values = mutableListOf<YpsoBolusAttempt>()
        override fun load() = values.lastOrNull()
        override fun loadAll() = values.toList()
        override fun commit(attempt: YpsoBolusAttempt) {
            val i = values.indexOfFirst { it.requestId == attempt.requestId }
            if (i >= 0) values[i] = attempt else values += attempt
        }
    }

    private val store = MemoryStore()
    private val journal = YpsoBolusAttemptJournal(store)
    private var clock = 2_000L
    private val controller = YpsoImmediateBolusController(mock(), journal, { SERIAL }, { null }, now = { clock })

    private fun dispatched(requestId: String = "request-1", counter: Long = 4810) {
        journal.prepare(attempt(requestId))
        journal.beforeDispatch(requestId, counter, clock)
        assertTrue(controller.beginDelivery())
        controller.armDispatchConnection(CONNECTION, counter, armedAt = 0)
    }

    @Test
    fun `the first new sequence notified on the dispatch connection is kept durably with its terminal time`() {
        dispatched()

        controller.observeBolusNotification(fast(STATUS_DELIVERING, 48_316), CONNECTION, 2_400)
        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_316), CONNECTION, 3_300)

        val saved = store.load()!!
        assertEquals(48_316L, saved.notifiedFastSequence)
        assertEquals(3_300L, saved.notifiedTerminalAt)
        assertEquals(48_316L, saved.notifiedAccountingPumpId)
        // Never an identity for cancellation or command success.
        assertNull(saved.pumpFastSequence)
        assertNull(saved.provenCancelBlock)
        assertNull(saved.accountingPumpId)
    }

    @Test
    fun `a notification from another connection or before the dispatch was armed is not this command's`() {
        journal.prepare(attempt())
        journal.beforeDispatch("request-1", 4810, clock)
        assertTrue(controller.beginDelivery())
        controller.observeBolusNotification(fast(STATUS_DELIVERING, 48_316), CONNECTION, 2_100, receivedAt = 10)
        assertNull(store.load()!!.notifiedFastSequence)

        controller.armDispatchConnection(CONNECTION, 4810, armedAt = 0)
        controller.observeBolusNotification(fast(STATUS_DELIVERING, 48_317), "other", 2_200, receivedAt = 10)
        assertNull(store.load()!!.notifiedFastSequence)
    }

    @Test
    fun `a notification received before the dispatch was armed is not adopted even if processed later`() {
        journal.prepare(attempt())
        journal.beforeDispatch("request-1", 4810, clock)
        assertTrue(controller.beginDelivery())
        controller.armDispatchConnection(CONNECTION, 4810, armedAt = 1_000)

        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_316), CONNECTION, 2_400, receivedAt = 999)

        assertNull(store.load()!!.notifiedFastSequence)
    }

    @Test
    fun `a notification from a rejected earlier dispatch is not adopted by the re-dispatch`() {
        dispatched()
        journal.provenNotApplied("request-1", rejected = true, detail = "counter")
        journal.beforeDispatch("request-1", 4811, 3_000)

        // Still armed for 4810: evidence belongs to the dispatch it was armed for.
        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_316), CONNECTION, 3_300)

        assertNull(store.load()!!.notifiedFastSequence)
    }

    @Test
    fun `a sequence status saw first conflicts with a different notified one`() {
        dispatched()
        val noteStatus = YpsoImmediateBolusController::class.java.getDeclaredMethod(
            "noteStatusSequence", YpsoBolusAttempt::class.java, Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        noteStatus.invoke(controller, store.load(), 48_315L)

        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_316), CONNECTION, 3_300)

        val saved = store.load()!!
        assertNull(saved.notifiedAccountingPumpId)
        assertTrue(saved.notifiedConflict)
        assertNull(controller.awaitHistoryAfterUnprovenPoll("request-1"))
    }

    @Test
    fun `evidence validated for a dispatch the pump rejected is not stamped onto the re-dispatch`() {
        dispatched()
        journal.provenNotApplied("request-1", rejected = true, detail = "counter")
        journal.beforeDispatch("request-1", 4811, 3_000)

        // The notification passed its checks under 4810 before the re-dispatch committed.
        journal.observeNotifiedFast("request-1", 4810, 48_316, 3_300)

        assertNull(store.load()!!.notifiedFastSequence)
    }

    @Test
    fun `a terminal announcement from another connection does not complete the evidence`() {
        dispatched()
        controller.observeBolusNotification(fast(STATUS_DELIVERING, 48_316), CONNECTION, 2_400)

        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_316), "other", 3_300)
        assertNull(store.load()!!.notifiedTerminalAt)
        assertNull(controller.awaitHistoryAfterUnprovenPoll("request-1"))

        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_316), CONNECTION, 3_400)
        assertEquals(3_400L, store.load()!!.notifiedTerminalAt)
    }

    @Test
    fun `a status sequence differing from the notified one blocks any identity, status proof included`() {
        dispatched()
        controller.observeBolusNotification(fast(STATUS_DELIVERING, 48_316), CONNECTION, 2_400)
        val noteStatus = YpsoImmediateBolusController::class.java.getDeclaredMethod(
            "noteStatusSequence", YpsoBolusAttempt::class.java, Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        noteStatus.invoke(controller, store.load(), 48_318L)

        assertTrue(store.load()!!.notifiedConflict)
        val conflict = YpsoImmediateBolusController::class.java.getDeclaredField("conflictSeen").apply { isAccessible = true }
        assertTrue((conflict.get(controller) as java.util.concurrent.atomic.AtomicBoolean).get())
    }

    @Test
    fun `a conflict recorded before status identity is committed blocks that identity`() {
        dispatched()
        journal.markNotifiedConflict("request-1", 4810)

        assertThrows(IllegalArgumentException::class.java) { journal.observeFastDelivering("request-1", 48_316, 40) }
        assertNull(store.load()!!.pumpFastSequence)
    }

    @Test
    fun `status identity must agree with an already notified sequence`() {
        dispatched()
        controller.observeBolusNotification(fast(STATUS_DELIVERING, 48_316), CONNECTION, 2_400)

        assertThrows(IllegalArgumentException::class.java) { journal.observeFastDelivering("request-1", 48_318, 40) }
        assertEquals(48_316L, journal.observeFastDelivering("request-1", 48_316, 40).pumpFastSequence)
    }

    @Test
    fun `a stale callback for an earlier dispatch never touches the next dispatch's evidence`() {
        dispatched()
        journal.provenNotApplied("request-1", rejected = true, detail = "counter")
        journal.beforeDispatch("request-1", 4811, 3_000)
        controller.armDispatchConnection(CONNECTION, 4811, armedAt = 0)
        val note = YpsoImmediateBolusController::class.java.getDeclaredMethod(
            "noteNewFastSequence", YpsoBolusAttempt::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        // Validated under 4810, resumed after the re-arm.
        assertFalse(note.invoke(controller, store.load(), 4810L, 48_316L) as Boolean)
        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_317), CONNECTION, 3_300)

        val saved = store.load()!!
        assertEquals(48_317L, saved.notifiedFastSequence)
        assertFalse(saved.notifiedConflict)
    }

    @Test
    fun `a second sequence after the quiet wait began surfaces the dose at once`() {
        dispatched()
        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_316), CONNECTION, 3_300)
        controller.awaitHistoryAfterUnprovenPoll("request-1")

        controller.observeBolusNotification(fast(STATUS_DELIVERING, 48_318), CONNECTION, 3_500)

        val saved = store.load()!!
        assertEquals(YpsoBolusOutcome.UNRESOLVED, saved.outcome)
        assertTrue(saved.hasUnresolvedWarning)
        assertNull(saved.notifiedAccountingPumpId)
    }

    @Test
    fun `a sequence not newer than the baseline or the cursor is ignored`() {
        dispatched()

        controller.observeBolusNotification(fast(STATUS_COMPLETED, 44), CONNECTION, 2_400)
        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_000), CONNECTION, 2_400)

        assertNull(store.load()!!.notifiedFastSequence)
    }

    @Test
    fun `a second distinct sequence voids the notified identity for good`() {
        dispatched()
        controller.observeBolusNotification(fast(STATUS_DELIVERING, 48_316), CONNECTION, 2_400)

        controller.observeBolusNotification(fast(STATUS_DELIVERING, 48_318), CONNECTION, 2_500)
        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_316), CONNECTION, 3_300)

        val saved = store.load()!!
        assertTrue(saved.notifiedConflict)
        assertNull(saved.notifiedAccountingPumpId)
        assertNull(controller.awaitHistoryAfterUnprovenPoll("request-1"))
    }

    @Test
    fun `a re-dispatch after a pump rejection voids a sequence named under the earlier dispatch`() {
        dispatched()
        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_316), CONNECTION, 2_400)
        journal.provenNotApplied("request-1", rejected = true, detail = "counter")

        journal.beforeDispatch("request-1", 4811, 3_000)

        assertNull(store.load()!!.notifiedAccountingPumpId)
    }

    @Test
    fun `a complete uncontested notification waits quietly for history, without a warning`() {
        dispatched()
        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_316), CONNECTION, 3_300)

        val waiting = controller.awaitHistoryAfterUnprovenPoll("request-1")!!

        assertEquals(YpsoBolusOutcome.AWAITING_HISTORY, waiting.outcome)
        assertFalse(waiting.hasUnresolvedWarning)
        assertEquals(clock + YpsoImmediateBolusController.HISTORY_CONFIRMATION_WINDOW_MS, waiting.historyDeadline)
    }

    @Test
    fun `without the terminal announcement the dose stays uncertain`() {
        dispatched()
        controller.observeBolusNotification(fast(STATUS_DELIVERING, 48_316), CONNECTION, 2_400)

        assertNull(controller.awaitHistoryAfterUnprovenPoll("request-1"))
    }

    @Test
    fun `the quiet wait turns into a warning at its deadline and a later dose never renews it`() {
        dispatched()
        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_316), CONNECTION, 3_300)
        controller.awaitHistoryAfterUnprovenPoll("request-1")
        controller.finishDelivery()
        journal.prepare(attempt("request-2"))

        clock += YpsoImmediateBolusController.HISTORY_CONFIRMATION_WINDOW_MS - 1
        assertTrue(controller.expireAwaitingHistory().isEmpty())
        clock += 1
        val expired = controller.expireAwaitingHistory().single()

        assertEquals("request-1", expired.requestId)
        assertTrue(expired.hasUnresolvedWarning)
    }

    @Test
    fun `history confirms an older notified dose after a later one became current`() {
        dispatched()
        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_316), CONNECTION, 3_300)
        controller.awaitHistoryAfterUnprovenPoll("request-1")
        controller.finishDelivery()
        journal.prepare(attempt("request-2"))

        val confirmed = controller.confirmNotifiedTerminal("request-1", 40, 2_900, 48_316)

        assertEquals(YpsoBolusOutcome.COMPLETED, confirmed.outcome)
        assertEquals(48_316L, confirmed.pumpHistoryId)
        assertEquals("request-2", store.load()!!.requestId)
        // A row larger than the request is never this dose.
        assertThrows(IllegalArgumentException::class.java) { journal.confirmNotifiedTerminal("request-1", 50, 2_900, 48_316) }
    }

    @Test
    fun `a partial row confirms a cancelled dose and history contradiction raises the warning`() {
        dispatched()
        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_316), CONNECTION, 3_300)
        controller.awaitHistoryAfterUnprovenPoll("request-1")

        assertEquals(YpsoBolusOutcome.CANCELLED_PARTIAL, controller.confirmNotifiedTerminal("request-1", 20, 2_900, 48_316).outcome)
        controller.finishDelivery()

        dispatched("request-2")
        controller.observeBolusNotification(fast(STATUS_COMPLETED, 48_320), CONNECTION, 3_300)
        controller.awaitHistoryAfterUnprovenPoll("request-2")
        val rejected = controller.rejectNotified("request-2", "row larger than the request")
        assertTrue(rejected.hasUnresolvedWarning)
        assertNull(rejected.notifiedAccountingPumpId)
    }

    @Test
    fun `notified evidence survives a restart through the file store and older journals still load`(@TempDir dir: File) {
        val file = File(dir, "attempt.json")
        val fileStore = YpsoBolusAttemptFileStore(file)
        val waiting = attempt().copy(
            outcome = YpsoBolusOutcome.AWAITING_HISTORY, dispatchCounter = 4810, dispatchedAt = 2_000,
            notifiedFastSequence = 48_316, notifiedDispatchCounter = 4810, notifiedTerminalAt = 3_300,
            historyDeadline = 1_802_000,
        )
        fileStore.commit(waiting)
        fileStore.commit(attempt("request-2"))

        assertEquals(waiting, YpsoBolusAttemptFileStore(file).loadAll().first())

        val v7 = org.json.JSONObject(file.readText()).apply {
            put("version", 7)
            val attempts = getJSONArray("attempts")
            for (i in 0 until attempts.length()) attempts.getJSONObject(i).apply {
                put("version", 7)
                listOf("notifiedFastSequence", "notifiedDispatchCounter", "notifiedTerminalAt", "notifiedConflict", "historyDeadline")
                    .forEach { remove(it) }
                put("outcome", "UNRESOLVED")
            }
        }
        file.writeText(v7.toString())
        val migrated = YpsoBolusAttemptFileStore(file).loadAll().first()
        assertNull(migrated.notifiedFastSequence)
        assertFalse(migrated.notifiedConflict)
    }

    private fun attempt(requestId: String = "request-1") = YpsoBolusAttempt(
        requestId = requestId,
        pumpSerial = SERIAL,
        sessionGeneration = "generation",
        treatment = YpsoBolusTreatment.SMB,
        requestedCentiUnits = 40,
        payloadHash = "a".repeat(64),
        baseline = YpsoBolusBaseline(44, 44, 48_300, 1, 2, 21, 1_000),
        createdAt = 1_000,
    )

    private fun fast(status: Int, sequence: Long) = YpsoBolusNotification(status, sequence, YpsoBolusNotification.STATUS_IDLE, 0)

    private companion object {
        const val SERIAL = "10054912"
        const val CONNECTION = "gatt:generation"
        const val STATUS_DELIVERING = YpsoBolusNotification.STATUS_DELIVERING
        const val STATUS_COMPLETED = YpsoBolusNotification.STATUS_COMPLETED
    }
}
