package app.aaps.pump.ypsopump

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.db.PersistenceLayer.TempIdBinding
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.ypsopump.ble.YpsoWriteFailure
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptFileStore
import app.aaps.pump.ypsopump.bolus.YpsoBolusBaseline
import app.aaps.pump.ypsopump.bolus.YpsoBolusBlock
import app.aaps.pump.ypsopump.bolus.YpsoBolusDelivery
import app.aaps.pump.ypsopump.bolus.YpsoBolusDelivery.Result
import app.aaps.pump.ypsopump.bolus.YpsoBolusLink
import app.aaps.pump.ypsopump.bolus.YpsoBolusMessage
import app.aaps.pump.ypsopump.bolus.YpsoBolusOutcome
import app.aaps.pump.ypsopump.bolus.YpsoBolusRecords
import app.aaps.pump.ypsopump.bolus.YpsoBolusShape
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import app.aaps.pump.ypsopump.bolus.YpsoValidatedBolusRequest
import app.aaps.pump.ypsopump.comm.YpsoBolusNotification
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.history.YpsoEventIdentity
import app.aaps.pump.ypsopump.history.YpsoHistoryCursor
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoHistoryIngestion
import app.aaps.pump.ypsopump.history.YpsoHistoryIngestionResult
import app.aaps.pump.ypsopump.history.YpsoHistorySnapshot
import app.aaps.pump.ypsopump.history.YpsoHistoryState
import app.aaps.pump.ypsopump.history.YpsoHistoryStateStore
import app.aaps.pump.ypsopump.history.YpsoPendingBolusSync
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YpsoBolusDeliveryTest {
    @TempDir lateinit var directory: File
    private var clock = 1_000_000L

    /** One bolus block as CHAR_BOLUS_STATUS reports it. */
    private data class Block(val status: Int = BolusCommand.STATUS_IDLE, val sequence: Long = 0, val delivered: Int = 0, val total: Int = 0)

    /**
     * A pump following the measured bolus semantics: a dose takes a new block sequence shared with
     * history, status clears the block once the pump announces its end on CONTROL_NOTIFY.
     */
    private inner class Pump : YpsoBolusLink {
        var fast = Block()
        var slow = Block()
        var slowMinutes = 0
        var nextSequence = 48_001L
        var counter = 10L
        val writes = mutableListOf<String>()
        val historyRecoveries = mutableListOf<String>()

        /** What the next start reports; delivery begins unless it is [YpsoWriteOutcome.NotSent]. */
        var startOutcome: (String, Long) -> YpsoWriteOutcome = { id, counter -> YpsoWriteOutcome.Verified(id, counter, "ack") }
        var cancelOutcome: (String, Long) -> YpsoWriteOutcome = { id, counter -> YpsoWriteOutcome.Verified(id, counter, "ack") }
        var startCallbackLost = false
        /** Status reads never show the programmed dose: it ends within a second, as short SMBs do. */
        var endsBeforeStatus = false
        /** Delivered per status read while an immediate dose runs; it ends when all is delivered. */
        var stepPerRead = 50
        /** The pump never reports the end of a dose or a cancellation. */
        var silent = false
        var cancelStops = true
        var cancelKeepsStatus = true
        var onRead: () -> Unit = {}

        private val owner = object : YpsoBolusLink.Owner {
            override val connectionKey = CONNECTION
        }

        override val isConnected = true
        override fun therapyStatus(stopWhen: () -> Boolean) = YpsoBolusLink.Readiness.READY
        override fun yieldHistory() = true
        override fun stoppedOrEmpty() = false
        override fun reconcileHistoryLater(reason: String) { historyRecoveries += reason }
        override fun reconnect(timeoutMs: Long) = true
        override fun epoch() = YpsoBolusLink.Epoch(CONNECTION, "generation", KEY_ID, REBOOT)

        override fun status(owner: YpsoBolusLink.Owner?): BolusCommand {
            onRead()
            if (fast.status == BolusCommand.STATUS_DELIVERING && !silent) {
                fast = fast.copy(delivered = minOf(fast.total, fast.delivered + stepPerRead))
                if (fast.delivered == fast.total) {
                    val shown = fast
                    finishFast()
                    return status(shown, slow)
                }
            }
            return status(fast, slow)
        }

        private fun status(fast: Block, slow: Block): BolusCommand {
            val payload = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
                .put(fast.status.toByte()).putInt(fast.sequence.toInt()).putInt(fast.delivered).putInt(fast.total)
                .put(slow.status.toByte()).putInt(slow.sequence.toInt()).putInt(slow.delivered).putInt(slow.total)
                .putInt(0).putInt(0).putInt(0).putInt(if (slow.total > 0) slowMinutes else 0)
                .array()
            return BolusCommand(0.0).apply { decode(payload) }
        }

        /** Clears the fast block and announces its end, as the pump does within a second of stopping. */
        fun finishFast() {
            val sequence = fast.sequence
            fast = Block()
            notify(YpsoBolusNotification(YpsoBolusNotification.STATUS_COMPLETED, sequence, YpsoBolusNotification.STATUS_IDLE, 0))
        }

        private fun notify(notification: YpsoBolusNotification) = delivery.onNotification(notification, CONNECTION, System.nanoTime())

        override fun start(
            writeId: String,
            request: YpsoValidatedBolusRequest,
            expectedConnection: String,
            beforeDispatch: (Long) -> Unit,
        ): Pair<YpsoWriteOutcome, YpsoBolusLink.Owner?> {
            val outcome = startOutcome(writeId, ++counter)
            if (outcome is YpsoWriteOutcome.NotSent) return outcome to null
            beforeDispatch(counter)
            clock += 500
            writes += "start ${request.centiUnits}"
            val sequence = nextSequence++
            if (request.shape == YpsoBolusShape.IMMEDIATE) {
                fast = Block(BolusCommand.STATUS_DELIVERING, sequence, 0, request.centiUnits)
                if (endsBeforeStatus) {
                    notify(YpsoBolusNotification(YpsoBolusNotification.STATUS_DELIVERING, sequence, YpsoBolusNotification.STATUS_IDLE, 0))
                    finishFast()
                }
            } else {
                slow = Block(BolusCommand.STATUS_MIXED_DELIVERING, sequence, 0, request.centiUnits)
                slowMinutes = request.durationMinutes
            }
            if (startCallbackLost) error("bolus write callback did not arrive within 45 seconds")
            return outcome to owner
        }

        override fun cancel(
            writeId: String,
            block: YpsoBolusBlock,
            expectedConnection: String,
            beforeDispatch: (Long) -> Unit,
        ): Pair<YpsoWriteOutcome, YpsoBolusLink.Owner?> {
            beforeDispatch(++counter)
            writes += "cancel $block"
            clock += 500
            if (cancelStops) when (block) {
                YpsoBolusBlock.FAST -> {
                    fast = fast.copy(status = BolusCommand.STATUS_IDLE)
                    if (!silent) finishFast()
                }
                YpsoBolusBlock.SLOW -> {
                    val sequence = slow.sequence
                    slow = if (cancelKeepsStatus) slow.copy(status = BolusCommand.STATUS_IDLE) else Block()
                    if (!silent) notify(YpsoBolusNotification(YpsoBolusNotification.STATUS_IDLE, 0, YpsoBolusNotification.STATUS_COMPLETED, sequence))
                }
            }
            return cancelOutcome(writeId, counter) to owner
        }

        override fun verifyAccepted(owner: YpsoBolusLink.Owner, writeId: String, evidenceHash: String, detail: String) = true
        override fun recordUnresolved(owner: YpsoBolusLink.Owner, writeId: String, evidenceHash: String, detail: String) = true
    }

    /** Bolus records by temporary and pump ID, mirroring the PumpSync and database transactions. */
    private class Records : YpsoBolusRecords {
        val boluses = mutableListOf<BS>()
        val extendeds = mutableMapOf<Long, EB>()
        var extendedRunning = false
        /** False when the database leaves a provisional record and its imported row apart. */
        var merges = true

        override fun addProvisional(timestamp: Long, units: Double, temporaryId: Long, type: BS.Type, pumpSerial: String): Boolean {
            if (boluses.any { it.ids.temporaryId == temporaryId }) return false
            boluses += BS(
                timestamp = timestamp, amount = units, type = type, id = boluses.size + 1L,
                ids = IDs(temporaryId = temporaryId, pumpType = PumpType.YPSOPUMP, pumpSerial = pumpSerial),
            )
            return true
        }

        override fun syncWithTemporaryId(bolus: BS, type: BS.Type) {
            if (!merges) return
            val provisional = boluses.singleOrNull { it.ids.temporaryId == bolus.ids.temporaryId }
            boluses.removeAll { it.ids.pumpId == bolus.ids.pumpId && it !== provisional }
            if (provisional == null) boluses += bolus.copy(type = type)
            else boluses[boluses.indexOf(provisional)] = bolus.copy(type = type, id = provisional.id)
        }

        override fun bindNotified(bolus: BS, type: BS.Type): TempIdBinding {
            val provisional = boluses.singleOrNull { it.ids.temporaryId == bolus.ids.temporaryId }
            val imported = boluses.singleOrNull { it.ids.pumpId == bolus.ids.pumpId && it.ids.temporaryId == null }
            if (provisional != null && imported != null && (!provisional.isValid || !imported.isValid)) return TempIdBinding.REFUSED
            if (provisional == null && imported?.isValid == false) return TempIdBinding.REFUSED
            return when {
                provisional != null -> {
                    boluses.remove(imported)
                    boluses[boluses.indexOf(provisional)] = provisional.copy(
                        amount = bolus.amount, timestamp = bolus.timestamp, ids = provisional.ids.copy(pumpId = bolus.ids.pumpId),
                    )
                    TempIdBinding.BOUND
                }
                imported != null -> TempIdBinding.PUMP_RECORD_ONLY
                else -> TempIdBinding.NO_RECORD
            }
        }

        override fun bolus(pumpId: Long, pumpSerial: String) = boluses.singleOrNull { it.ids.pumpId == pumpId && it.ids.pumpSerial == pumpSerial }
        override fun bolusesFrom(from: Long, pumpSerial: String) = boluses.filter { it.timestamp >= from && it.ids.pumpSerial == pumpSerial }
        override fun extended(pumpId: Long, pumpSerial: String) = extendeds[pumpId]

        override fun syncExtended(start: Long, units: Double, duration: Long, pumpId: Long, pumpSerial: String): Boolean {
            extendeds[pumpId] = extendedRecord(start, units, duration, pumpId, pumpSerial)
            return true
        }

        override fun correctExtended(start: Long, units: Double, duration: Long, emulatingTempBasal: Boolean, pumpId: Long, pumpSerial: String) {
            extendeds[pumpId] = extendedRecord(start, units, duration, pumpId, pumpSerial)
        }

        override fun extendedRunning() = extendedRunning

        /** What PumpSync does with an imported history row: one record per pump ID, updated in place. */
        fun replay(timestamp: Long, units: Double, type: BS.Type, pumpId: Long) {
            val existing = boluses.indexOfFirst { it.ids.pumpId == pumpId }
            if (existing >= 0) boluses[existing] = boluses[existing].copy(timestamp = timestamp, amount = units, type = type)
            else boluses += BS(
                timestamp = timestamp, amount = units, type = type, id = boluses.size + 1L,
                ids = IDs(pumpId = pumpId, pumpType = PumpType.YPSOPUMP, pumpSerial = SERIAL),
            )
        }

        private fun extendedRecord(start: Long, units: Double, duration: Long, pumpId: Long, pumpSerial: String) = EB(
            timestamp = start, amount = units, duration = duration,
            ids = IDs(pumpId = pumpId, pumpType = PumpType.YPSOPUMP, pumpSerial = pumpSerial),
        )
    }

    private val pump = Pump()
    private val records = Records()
    private val warnings = mutableListOf<Boolean>()
    private val journalFile get() = File(directory, "ypsopump-bolus-attempt.json")
    private val anchor = YpsoHistoryEntry(800_000_000L, 4, 0, 0, 0, 48_000L, 0)

    /** Durable state of the history ingestion; its cursor is the newest row ingestion has taken. */
    private val historyState = object : YpsoHistoryStateStore {
        var value = YpsoHistoryState(cursorAt(anchor))
        override fun load() = value
        override fun commit(value: YpsoHistoryState) { this.value = value }
    }
    private val cursor get() = historyState.value.cursor

    /** Pump IDs of history rows PumpSync was asked to save, in order. */
    private val replays = mutableListOf<Long>()
    private val pumpSync: PumpSync = mock<PumpSync>().also { sync ->
        whenever(sync.replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any())).thenAnswer {
            val pumpId = it.getArgument<Long>(3)
            replays += pumpId
            records.replay(it.getArgument(0), it.getArgument(1), it.getArgument<BS.Type?>(2) ?: BS.Type.NORMAL, pumpId)
            PumpSync.BolusSyncResult.INSERTED
        }
    }

    /** History ingestion wired to this module as the plugin wires it. */
    private val ingestion by lazy {
        YpsoHistoryIngestion(
            historyState, pumpSync,
            hasRecordedBolus = { serial, pumpId -> delivery.ownsHistoryBolus(serial, pumpId) },
            resolveProvisional = { serial, pumpId, timestamp, amount, type -> delivery.bindProvisional(serial, pumpId, timestamp, amount, type) },
        )
    }

    private val delivery: YpsoBolusDelivery by lazy {
        YpsoBolusDelivery(
            pump, records, journalFile, { SERIAL },
            historyCursor = { cursor },
            historyReadiness = { _, _ -> null },
            showWarning = { warnings += it },
            aapsLogger = AAPSLoggerTest(),
            now = { clock },
            elapsed = { clock },
            sleep = { clock += it },
        )
    }

    private val attempts get() = YpsoBolusAttemptFileStore(journalFile).loadAll()
    private val progress = mutableListOf<Double>()

    private fun deliver(units: Double = 1.0, type: BS.Type = BS.Type.NORMAL): Result {
        delivery.beginRequest()
        return delivery.deliver(YpsoBolusDelivery.Order(units, 0.0, type), { 10.0 }) { progress += it }
    }

    // ---- immediate delivery ----

    @Test
    fun `a bolus is recorded at dispatch and completes when the pump announces its end`() {
        val result = deliver()

        assertEquals(Result.Delivered(1.0, proven = true), result)
        assertEquals(listOf("start 100"), pump.writes)
        assertEquals(listOf(0.5, 1.0, 1.0), progress)
        val record = records.boluses.single()
        assertEquals(1.0, record.amount)
        assertEquals(attempts.single().dispatchedAt, record.timestamp)
        assertEquals(48_001L, attempts.single().pumpFastSequence)
        assertTrue(attempts.single().blockTerminalAt != null)
        assertEquals(listOf("bolus delivery"), pump.historyRecoveries)
        assertFalse(delivery.isBusy)
    }

    @Test
    fun `an end the status never showed in full is reported as recorded, not as delivered`() {
        // The pump also ends a dose stopped on the pump itself with the same announcement.
        pump.stepPerRead = 30
        pump.onRead = { if (pump.fast.delivered == 30) pump.finishFast() }

        val result = deliver()

        assertEquals(Result.Delivered(1.0, proven = false), result)
        assertEquals(1.0, records.boluses.single().amount)
    }

    @Test
    fun `a bolus whose end is never reported stays recorded in full and raises the warning`() {
        pump.silent = true

        val result = deliver()

        assertEquals(Result.Uncertain(YpsoBolusMessage.NOT_CONFIRMED_FINISHED), result)
        assertEquals(1.0, records.boluses.single().amount)
        assertEquals(YpsoBolusOutcome.UNRESOLVED, attempts.single().outcome)
        assertEquals(true, warnings.last())
        assertEquals(1, pump.writes.size)
    }

    @Test
    fun `invalid requests and unready pumps never reach the pump or the records`() {
        assertEquals(
            Result.Failed(YpsoBolusMessage.CARBS_NOT_STORED, invalidRequest = true),
            delivery.deliver(YpsoBolusDelivery.Order(1.0, 10.0, BS.Type.NORMAL), { 10.0 }) {},
        )
        assertTrue(delivery.deliver(YpsoBolusDelivery.Order(1.05, 0.0, BS.Type.NORMAL), { 10.0 }) {} is Result.Invalid)
        assertTrue(delivery.deliver(YpsoBolusDelivery.Order(2.0, 0.0, BS.Type.NORMAL), { 1.0 }) {} is Result.Invalid)
        pump.silent = true
        pump.fast = Block(BolusCommand.STATUS_DELIVERING, 47_999L, 10, 50)
        assertEquals(Result.Failed(YpsoBolusMessage.PUMP_ALREADY_BOLUSING), deliver())

        assertTrue(pump.writes.isEmpty())
        assertTrue(records.boluses.isEmpty())
    }

    @Test
    fun `a write the pump never received is reported not sent and leaves no record`() {
        pump.startOutcome = { id, counter -> YpsoWriteOutcome.NotSent(id, counter, failure("link lost")) }

        assertEquals(Result.Failed(YpsoBolusMessage.PUMP_DID_NOT_RESPOND), deliver())
        assertTrue(records.boluses.isEmpty())
        assertEquals(YpsoBolusOutcome.NOT_SENT, attempts.single().outcome)
    }

    // ---- stop ----

    @Test
    fun `a stop pressed before dispatch sends nothing`() {
        delivery.beginRequest()
        delivery.stop {}

        val result = delivery.deliver(YpsoBolusDelivery.Order(1.0, 0.0, BS.Type.NORMAL), { 10.0 }) {}

        assertEquals(Result.Failed(YpsoBolusMessage.BOLUS_CANCELLED_BEFORE_START), result)
        assertTrue(pump.writes.isEmpty())
        assertTrue(records.boluses.isEmpty())
    }

    @Test
    fun `a delivery starting while stop hands the link over from history sends nothing`() {
        delivery.beginRequest()
        var duringHandover: Result? = null

        delivery.stop { duringHandover = delivery.deliver(YpsoBolusDelivery.Order(1.0, 0.0, BS.Type.NORMAL), { 10.0 }) {} }

        assertEquals(Result.Failed(YpsoBolusMessage.BOLUS_CANCELLED_BEFORE_START), duringHandover)
        assertTrue(pump.writes.isEmpty())
    }

    @Test
    fun `the bolus maximum is read only once the dose is about to be validated`() {
        // A carb entry is rejected before the maximum is needed at all.
        val carbs = delivery.deliver(YpsoBolusDelivery.Order(1.0, 10.0, BS.Type.NORMAL), { error("read too early") }) {}
        assertEquals(Result.Failed(YpsoBolusMessage.CARBS_NOT_STORED, invalidRequest = true), carbs)
        // A failing constraint read ends the lifecycle like any other error instead of escaping it.
        val failing = delivery.deliver(YpsoBolusDelivery.Order(0.4, 0.0, BS.Type.NORMAL), { error("constraints unavailable") }) {}
        assertEquals(Result.Error("constraints unavailable"), failing)
        assertTrue(pump.writes.isEmpty())
        assertFalse(delivery.isBusy)
    }

    @Test
    fun `stop during delivery cancels the proven block once and leaves the amount to history`() {
        pump.stepPerRead = 20
        var reads = 0
        pump.onRead = { if (++reads == 3) delivery.stop {} }

        val result = deliver()

        assertEquals(Result.Uncertain(YpsoBolusMessage.STOPPED_AMOUNT_UNKNOWN), result)
        assertEquals(listOf("start 100", "cancel FAST"), pump.writes)
        // The requested amount stays counted until history proves what was delivered.
        assertEquals(1.0, records.boluses.single().amount)
        val attempt = attempts.single()
        assertEquals(YpsoBolusOutcome.CANCEL_PENDING, attempt.outcome)
        assertTrue(attempt.blockTerminalAt != null)
    }

    // ---- uncertain ----

    @Test
    fun `a dose no status proved is recorded in full and never retried`() {
        pump.startOutcome = { id, counter -> YpsoWriteOutcome.PossiblyApplied(id, counter, failure("no ACK")) }
        pump.silent = true
        pump.onRead = { pump.fast = Block() }

        val result = deliver()

        assertEquals(Result.Uncertain(YpsoBolusMessage.MAY_HAVE_BEEN_GIVEN), result)
        assertEquals(listOf("start 100"), pump.writes)
        assertEquals(1.0, records.boluses.single().amount)
        assertEquals(YpsoBolusOutcome.UNRESOLVED, attempts.single().outcome)
        delivery.refreshWarning()
        assertEquals(true, warnings.last())
    }

    @Test
    fun `a lost write callback after dispatch records the dose in full`() {
        pump.startCallbackLost = true

        val result = deliver()

        assertEquals(Result.Uncertain(YpsoBolusMessage.PUMP_DID_NOT_RESPOND), result)
        assertEquals(1.0, records.boluses.single().amount)
        assertEquals(YpsoBolusOutcome.POSSIBLY_APPLIED, attempts.single().outcome)
    }

    // ---- awaiting history and history binding ----

    @Test
    fun `a dose that ended before status showed it waits quietly for its notified history row`() {
        pump.endsBeforeStatus = true

        val result = deliver(0.4, BS.Type.SMB)

        assertEquals(Result.AwaitingHistory, result)
        val attempt = attempts.single()
        assertEquals(YpsoBolusOutcome.AWAITING_HISTORY, attempt.outcome)
        assertEquals(48_001L, attempt.notifiedAccountingPumpId)
        assertNull(attempt.pumpFastSequence)
        assertEquals(0.4, records.boluses.single().amount)
        delivery.refreshWarning()
        assertEquals(false, warnings.last())
        assertTrue(delivery.ownsHistoryBolus(SERIAL, 48_001L))
    }

    @Test
    fun `history binds a notified dose to exactly its row and confirms it`() {
        pump.endsBeforeStatus = true
        deliver(0.4, BS.Type.SMB)

        delivery.bindProvisional(SERIAL, 48_002L, clock, 0.4, BS.Type.NORMAL)
        assertNull(records.boluses.single().ids.pumpId)

        delivery.bindProvisional(SERIAL, 48_001L, clock, 0.3, BS.Type.NORMAL)

        val record = records.boluses.single()
        assertEquals(48_001L, record.ids.pumpId)
        assertEquals(0.3, record.amount)
        assertEquals(BS.Type.SMB, record.type)
        val attempt = attempts.single()
        assertEquals(YpsoBolusOutcome.CANCELLED_PARTIAL, attempt.outcome)
        assertEquals(30, attempt.confirmedCentiUnits)
    }

    @Test
    fun `a notified dose is never merged onto a larger row`() {
        pump.endsBeforeStatus = true
        deliver(0.4)

        delivery.bindProvisional(SERIAL, 48_001L, clock, 0.5, BS.Type.NORMAL)

        assertNull(records.boluses.single().ids.pumpId)
        assertEquals(YpsoBolusOutcome.UNRESOLVED, attempts.single().outcome)
        assertEquals(true, warnings.last())
    }

    @Test
    fun `history import keeps a notified dose the user removed removed`() {
        pump.endsBeforeStatus = true
        deliver(0.2, BS.Type.SMB)
        records.boluses[0] = records.boluses[0].copy(isValid = false)

        historyState.value = historyState.value.copy(
            pendingBolus = YpsoPendingBolusSync(SERIAL, 48_001L, clock, 20, 1L, BS.Type.SMB),
        )
        assertTrue(ingestion.retryPending(SERIAL))

        val record = records.boluses.single()
        assertEquals(48_001L, record.ids.pumpId)
        assertFalse(record.isValid)
        assertEquals(YpsoBolusOutcome.COMPLETED, attempts.single().outcome)
    }

    @Test
    fun `a notified dose is confirmed only once a valid record carries its row`() {
        pump.endsBeforeStatus = true
        deliver(0.4)
        // Process death after dispatch: the provisional record was never written.
        records.boluses.clear()

        delivery.bindProvisional(SERIAL, 48_001L, clock, 0.4, BS.Type.NORMAL)
        assertEquals(YpsoBolusOutcome.AWAITING_HISTORY, attempts.single().outcome)

        records.boluses += BS(timestamp = clock, amount = 0.4, type = BS.Type.NORMAL,
            ids = IDs(pumpId = 48_001L, pumpType = PumpType.YPSOPUMP, pumpSerial = SERIAL))
        delivery.bindProvisional(SERIAL, 48_001L, clock, 0.4, BS.Type.NORMAL)
        assertEquals(YpsoBolusOutcome.COMPLETED, attempts.single().outcome)
    }

    @Test
    fun `a notified dose an older journal entry still holds is bound after a later dose became current`() {
        seed(notifiedAttempt("request", 48_316L))
        seed(notifiedAttempt("later", 48_400L).copy(outcome = YpsoBolusOutcome.COMPLETED, historyDeadline = null))
        records.addProvisional(1_200L, 0.4, temporaryId("request"), BS.Type.SMB, SERIAL)

        delivery.bindProvisional(SERIAL, 48_316L, 1_500L, 0.4, BS.Type.NORMAL)

        assertEquals(48_316L, records.boluses.single().ids.pumpId)
        assertEquals(YpsoBolusOutcome.COMPLETED, attempts.single { it.requestId == "request" }.outcome)
    }

    @Test
    fun `a row two doses name belongs to neither, at import and at repair`() {
        seed(notifiedAttempt("a", 48_316L))
        seed(notifiedAttempt("b", 48_316L))
        records.boluses += BS(timestamp = 1_500L, amount = 0.4, type = BS.Type.NORMAL,
            ids = IDs(pumpId = 48_316L, pumpType = PumpType.YPSOPUMP, pumpSerial = SERIAL))

        delivery.bindProvisional(SERIAL, 48_316L, 1_500L, 0.4, BS.Type.NORMAL)
        delivery.reconcileHistory(SERIAL, "generation", REBOOT, YpsoHistorySnapshot(1, 1, 21, 21, anchor, anchor, listOf(anchor), true, pumpClockOffsetMs = 0L))

        assertTrue(attempts.all { it.outcome == YpsoBolusOutcome.AWAITING_HISTORY })
        assertNull(records.boluses.single().ids.temporaryId)
    }

    @Test
    fun `history ownership counts removed records and journalled doses of this pump only`() {
        records.boluses += BS(timestamp = 1_000L, amount = 0.5, type = BS.Type.NORMAL, isValid = false,
            ids = IDs(pumpId = 101L, pumpType = PumpType.YPSOPUMP, pumpSerial = SERIAL))
        seed(notifiedAttempt("proven", 48_134L).copy(
            outcome = YpsoBolusOutcome.UNRESOLVED, historyDeadline = null, pumpFastSequence = 48_134L,
        ))

        assertTrue(delivery.ownsHistoryBolus(SERIAL, 101L))
        assertTrue(delivery.ownsHistoryBolus(SERIAL, 48_134L))
        assertFalse(delivery.ownsHistoryBolus(SERIAL, 48_135L))
        assertFalse(delivery.ownsHistoryBolus("20000002", 48_134L))
    }

    @Test
    fun `terminal history confirms a status-proven dose with the delivered amount`() {
        pump.stepPerRead = 20
        var reads = 0
        pump.onRead = { if (++reads == 3) delivery.stop {} }
        deliver(1.0, BS.Type.SMB)
        val dose = YpsoHistoryEntry(800_000_100L, 2, 40, 0, 0, 48_001L, 0)

        val pass = delivery.reconcileHistory(
            SERIAL, "generation", REBOOT, YpsoHistorySnapshot(2, 2, 21, 21, dose, dose, listOf(dose, anchor.copy(index = 1)), true, pumpClockOffsetMs = 0L),
        )

        val attempt = attempts.single()
        assertEquals(YpsoBolusOutcome.CANCELLED_PARTIAL, attempt.outcome)
        assertEquals(40, attempt.confirmedCentiUnits)
        assertEquals(48_001L, attempt.pumpHistoryId)
        assertEquals(BS.Type.SMB, (pass as YpsoBolusDelivery.HistoryPass.Proceed).treatment(48_001L))
        assertEquals(BS.Type.NORMAL, pass.treatment(48_002L))
    }

    @Test
    fun `history confirms a dose a restart left unresolved after a later dose became current`() {
        assertTrue(deliver(0.5, BS.Type.SMB) is Result.Delivered)
        // The app restarts before history confirmed the dose, past its observation window.
        clock += 120_000L
        delivery.expireStale()
        delivery.refreshWarning()
        assertEquals(YpsoBolusOutcome.UNRESOLVED, attempts.single().outcome)
        assertEquals(true, warnings.last())
        assertTrue(deliver(0.5, BS.Type.SMB) is Result.Delivered)
        val rows = listOf(
            YpsoHistoryEntry(800_000_400L, 2, 50, 0, 0, 48_002L, 0),
            YpsoHistoryEntry(800_000_100L, 2, 50, 0, 0, 48_001L, 0),
        )

        historyPass(rows)

        val first = attempts.first()
        assertEquals(YpsoBolusOutcome.COMPLETED, first.outcome)
        assertEquals(50, first.confirmedCentiUnits)
        assertEquals(48_001L, first.pumpHistoryId)
        assertEquals(false, warnings.last())
        assertEquals(listOf(48_001L, 48_002L), records.boluses.map { it.ids.pumpId })
        // Once settled it leaves the journal like any confirmed dose.
        historyPass(rows)
        assertEquals(48_002L, attempts.single().pumpHistoryId)
    }

    @Test
    fun `a superseded dose left unresolved after its row was imported is confirmed by repair`() {
        seed(confirmedAttempt("stuck", 48_317L).copy(
            outcome = YpsoBolusOutcome.UNRESOLVED, pumpHistoryId = null, confirmedCentiUnits = null, deliveryTimestamp = null,
        ))
        imported("stuck", 48_317L)
        seed(confirmedAttempt("current", 48_401L, counter = 9))
        imported("current", 48_401L)
        historyState.value = YpsoHistoryState(cursorAt(YpsoHistoryEntry(800_000_100L, 2, 40, 0, 0, 48_401L, 0)))
        delivery.refreshWarning()
        assertEquals(true, warnings.last())

        reconcile(emptyList())

        // Confirmed, so the warning clears and the settled dose leaves the journal.
        assertEquals(false, warnings.last())
        assertEquals(listOf("current"), attempts.map { it.requestId })
    }

    @Test
    fun `provisioning reads its counter floor from the journal the module owns`() {
        assertNull(delivery.recoveryEvidence())
        deliver()

        val evidence = checkNotNull(delivery.recoveryEvidence())
        assertEquals(pump.counter, evidence.attempt.dispatchCounter)
        assertEquals(KEY_ID, evidence.attempt.sessionKeyId)
        assertTrue(evidence.bytes.contentEquals(journalFile.readBytes()))
    }

    // ---- extended ----

    @Test
    fun `an extended bolus is recorded under its proven slow block`() {
        val result = delivery.startExtended(1.5, 30, 10.0)

        assertEquals(Result.ExtendedStarted(1.5, 30), result)
        val record = records.extendeds.getValue(48_001L)
        assertEquals(1.5, record.amount)
        assertEquals(30 * 60_000L, record.duration)
        assertEquals(attempts.single().dispatchedAt, record.timestamp)
        assertEquals(listOf("extended bolus delivery"), pump.historyRecoveries)
    }

    @Test
    fun `a cancellation status proved closes the record at the delivered amount`() {
        delivery.startExtended(1.5, 30, 10.0)
        clock += 10 * 60_000L
        pump.slow = pump.slow.copy(delivered = 50)

        val result = delivery.cancelExtended()

        assertEquals(Result.Cancelled(0.5), result)
        val record = records.extendeds.getValue(48_001L)
        assertEquals(0.5, record.amount)
        assertTrue(record.duration < 30 * 60_000L)
        assertEquals(YpsoBolusOutcome.CANCELLED_PARTIAL, attempts.single().outcome)
    }

    @Test
    fun `a pump announced stop closes the record at the announced instant`() {
        delivery.startExtended(1.5, 30, 10.0)
        val start = checkNotNull(attempts.single().dispatchedAt)
        clock = start + 3 * 60_000L
        pump.cancelOutcome = { id, counter -> YpsoWriteOutcome.AcceptedUnverified(id, counter) }
        pump.cancelKeepsStatus = false

        val result = delivery.cancelExtended()

        // The pump stopped 3 min 0.5 s into the 30 min schedule: 150 × 180.5 / 1800 = 15.04 centi-units.
        val record = records.extendeds.getValue(48_001L)
        assertEquals(start + 180_500L, attempts.single().blockTerminalAt)
        assertEquals(180_500L, record.duration)
        assertEquals(0.15, record.amount)
        assertEquals(Result.Cancelled(0.15), result)
    }

    @Test
    fun `delivery the pump reported is never reduced to the elapsed schedule`() {
        delivery.startExtended(1.5, 30, 10.0)
        val start = checkNotNull(attempts.single().dispatchedAt)
        clock = start + 3 * 60_000L
        // Status at the cancel shows 1.0 U delivered, more than three minutes of schedule.
        pump.slow = pump.slow.copy(delivered = 100)
        pump.cancelOutcome = { id, counter -> YpsoWriteOutcome.AcceptedUnverified(id, counter) }
        pump.cancelKeepsStatus = false

        val result = delivery.cancelExtended()

        assertEquals(Result.Cancelled(1.0), result)
        assertEquals(1.0, records.extendeds.getValue(48_001L).amount)
    }

    @Test
    fun `a dispatched but unproven cancellation never shortens the record`() {
        delivery.startExtended(1.5, 30, 10.0)
        pump.cancelOutcome = { id, counter -> YpsoWriteOutcome.PossiblyApplied(id, counter, failure("no ACK")) }
        pump.cancelStops = false

        val result = delivery.cancelExtended()

        assertEquals(Result.Uncertain(YpsoBolusMessage.EXTENDED_STOP_UNCONFIRMED, cancel = true), result)
        assertEquals(listOf("start 150", "cancel SLOW"), pump.writes)
        val record = records.extendeds.getValue(48_001L)
        assertEquals(1.5, record.amount)
        assertEquals(30 * 60_000L, record.duration)
        assertEquals(true, warnings.last())
    }

    @Test
    fun `cancelling an extended bolus AAPS did not start is refused`() {
        assertEquals(Result.NothingToCancel, delivery.cancelExtended())
        records.extendedRunning = true
        assertEquals(Result.Failed(YpsoBolusMessage.EXTENDED_NOT_STARTED_BY_AAPS), delivery.cancelExtended())
        assertTrue(pump.writes.isEmpty())
    }

    // ---- journal size ----

    @Test
    fun `the journal stays bounded over many settled doses and records each row once`() {
        val rows = mutableListOf<YpsoHistoryEntry>()
        repeat(DOSES) { i ->
            assertTrue(deliver(0.5, BS.Type.SMB) is Result.Delivered)
            rows.add(0, YpsoHistoryEntry(800_000_100L + i * 300L, 2, 50, 0, 0, 48_001L + i, 0))
            historyPass(rows)
        }
        // Later scans of the same history, once the last row is behind the cursor too.
        repeat(2) { historyPass(rows) }

        val current = attempts.single()
        assertEquals(48_000L + DOSES, current.pumpHistoryId)
        assertEquals(pump.counter, delivery.recoveryEvidence()?.attempt?.dispatchCounter)
        // Every row was saved once, onto its dose's provisional record, and stays an SMB.
        assertEquals((48_001L..48_000L + DOSES).toList(), replays)
        assertEquals((48_001L..48_000L + DOSES).toList(), records.boluses.map { it.ids.pumpId })
        assertTrue(records.boluses.all { it.amount == 0.5 && it.type == BS.Type.SMB && it.ids.temporaryId != null })
        assertTrue(delivery.ownsHistoryBolus(SERIAL, 48_001L))
    }

    @Test
    fun `doses whose accounting is not settled stay journalled until it is`() {
        seed(notifiedAttempt("awaiting", 48_316L))
        seed(notifiedAttempt("unresolved", 48_317L).copy(outcome = YpsoBolusOutcome.UNRESOLVED, historyDeadline = null, pumpFastSequence = 48_317L))
        // Confirmed, but no record carries its row.
        seed(confirmedAttempt("never-imported", 48_318L))
        // Confirmed and imported, but its provisional record is still a second record of the dose.
        seed(confirmedAttempt("unmerged", 48_319L))
        records.addProvisional(1_200L, 0.4, temporaryId("unmerged"), BS.Type.SMB, SERIAL)
        records.replay(1_500L, 0.4, BS.Type.SMB, 48_319L)
        seed(confirmedAttempt("settled", 48_320L))
        imported("settled", 48_320L)
        // The operator removed the imported row while it was still apart from its provisional record.
        seed(confirmedAttempt("removed-import", 48_321L))
        records.addProvisional(1_200L, 0.4, temporaryId("removed-import"), BS.Type.SMB, SERIAL)
        records.replay(1_500L, 0.4, BS.Type.SMB, 48_321L)
        records.boluses[records.boluses.lastIndex] = records.boluses.last().copy(isValid = false)
        // No provisional record was ever found: absence is no proof one was not written.
        seed(confirmedAttempt("no-provisional", 48_322L))
        records.replay(1_500L, 0.4, BS.Type.SMB, 48_322L)
        // Settled, but of another pump whose history ingestion has not moved past it.
        seed(confirmedAttempt("other-pump", 48_323L).copy(pumpSerial = "20000002"))
        records.boluses += BS(
            timestamp = 1_500L, amount = 0.4, type = BS.Type.SMB, id = records.boluses.size + 1L,
            ids = IDs(temporaryId = temporaryId("other-pump"), pumpId = 48_323L, pumpType = PumpType.YPSOPUMP, pumpSerial = "20000002"),
        )
        // Its row is the cursor itself, which a scan can still offer again.
        seed(confirmedAttempt("at-cursor", 48_400L))
        imported("at-cursor", 48_400L)
        seed(confirmedAttempt("current", 48_401L, counter = 9))
        imported("current", 48_401L)
        historyState.value = YpsoHistoryState(cursorAt(YpsoHistoryEntry(800_000_100L, 2, 40, 0, 0, 48_400L, 0)))
        records.merges = false
        val kept = listOf("awaiting", "unresolved", "never-imported", "removed-import", "no-provisional", "other-pump", "at-cursor", "current")

        reconcile(emptyList())

        assertEquals(listOf("awaiting", "unresolved", "never-imported", "unmerged") + kept.drop(3), attempts.map { it.requestId })

        records.merges = true
        reconcile(emptyList())

        // Once repair merges them, a dose is settled; for a proven identity the merge keeps the operator's removal.
        assertEquals(kept - "removed-import", attempts.map { it.requestId })
        assertFalse(records.boluses.single { it.ids.pumpId == 48_321L }.isValid)
        assertEquals(temporaryId("unmerged"), records.boluses.single { it.ids.pumpId == 48_319L }.ids.temporaryId)
        assertEquals(1, records.boluses.count { it.ids.temporaryId == temporaryId("unmerged") })
    }

    /** One history pass as the plugin runs it: the module reconciles the scan, then ingestion takes its new rows. */
    private fun historyPass(rowsNewestFirst: List<YpsoHistoryEntry>) {
        val (snapshot, pass) = reconcile(rowsNewestFirst)
        val result = ingestion.ingest(SERIAL, REBOOT.toLong(), snapshot) { pass.treatment(it.identity.aapsPumpId) }
        assertTrue(result is YpsoHistoryIngestionResult.Applied, result.toString())
    }

    private fun reconcile(rowsNewestFirst: List<YpsoHistoryEntry>): Pair<YpsoHistorySnapshot, YpsoBolusDelivery.HistoryPass.Proceed> {
        val scan = (rowsNewestFirst + anchor).mapIndexed { index, row -> row.copy(index = index) }
        val snapshot = YpsoHistorySnapshot(scan.size, scan.size, 21, 21, scan.first(), scan.first(), scan, true, pumpClockOffsetMs = 0L)
        return snapshot to delivery.reconcileHistory(SERIAL, "generation", REBOOT, snapshot) as YpsoBolusDelivery.HistoryPass.Proceed
    }

    private fun cursorAt(row: YpsoHistoryEntry) = YpsoHistoryCursor(YpsoEventIdentity(SERIAL, 0, row.sequence), row.fingerprint(), REBOOT.toLong())

    /** The single record of a journalled dose whose provisional record took over its imported row. */
    private fun imported(requestId: String, pumpId: Long) {
        records.boluses += BS(
            timestamp = 1_500L, amount = 0.4, type = BS.Type.SMB, id = records.boluses.size + 1L,
            ids = IDs(temporaryId = temporaryId(requestId), pumpId = pumpId, pumpType = PumpType.YPSOPUMP, pumpSerial = SERIAL),
        )
    }

    private fun confirmedAttempt(requestId: String, sequence: Long, counter: Long = 1) = notifiedAttempt(requestId, sequence).copy(
        outcome = YpsoBolusOutcome.COMPLETED, historyDeadline = null, dispatchCounter = counter, notifiedDispatchCounter = counter,
        pumpFastSequence = sequence, pumpHistoryId = sequence, confirmedCentiUnits = 40, deliveryTimestamp = 1_500L,
    )

    private fun seed(attempt: YpsoBolusAttempt) = YpsoBolusAttemptFileStore(journalFile).commit(attempt)

    /** The provisional record identity the module derives from a journalled attempt. */
    private fun temporaryId(requestId: String) = requestId.hashCode().toLong() and 0xffffffffL or ((1_100L / 1000L) shl 32)

    private fun notifiedAttempt(requestId: String, sequence: Long) = YpsoBolusAttempt(
        requestId = requestId,
        pumpSerial = SERIAL,
        sessionGeneration = "generation",
        treatment = YpsoBolusTreatment.SMB,
        requestedCentiUnits = 40,
        payloadHash = "ab".repeat(32),
        baseline = YpsoBolusBaseline(48_000, 20, 48_000, 1, 2, REBOOT, 1_000),
        createdAt = 1_100,
        outcome = YpsoBolusOutcome.AWAITING_HISTORY,
        dispatchCounter = 1,
        dispatchedAt = 1_200,
        notifiedFastSequence = sequence,
        notifiedDispatchCounter = 1,
        notifiedTerminalAt = 2_000,
        historyDeadline = clock + 60_000,
    )

    private fun failure(detail: String) =
        YpsoWriteFailure(YpsoWriteFailure.Layer.DISPATCH, YpsoWritePolicy.BOLUS_START_STOP_UUID, null, detail = detail)

    private companion object {
        const val SERIAL = "10000001"
        const val CONNECTION = "connection"
        const val REBOOT = 21
        const val DOSES = 40
        val KEY_ID = "cd".repeat(32)
    }
}
