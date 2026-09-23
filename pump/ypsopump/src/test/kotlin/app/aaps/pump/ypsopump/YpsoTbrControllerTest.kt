package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.tbr.YpsoTbrAttempt
import app.aaps.pump.ypsopump.tbr.YpsoTbrAttemptStore
import app.aaps.pump.ypsopump.tbr.YpsoTbrCommandEvidence
import app.aaps.pump.ypsopump.tbr.YpsoTbrController
import app.aaps.pump.ypsopump.tbr.YpsoTbrController.Reason
import app.aaps.pump.ypsopump.tbr.YpsoTbrController.Result
import app.aaps.pump.ypsopump.tbr.YpsoTbrJournal
import app.aaps.pump.ypsopump.tbr.YpsoTbrHeadRow
import app.aaps.pump.ypsopump.tbr.YpsoTbrLink
import app.aaps.pump.ypsopump.tbr.YpsoTbrObservation
import app.aaps.pump.ypsopump.tbr.YpsoTbrRecords
import app.aaps.pump.ypsopump.tbr.YpsoTbrRejectReason
import app.aaps.pump.ypsopump.tbr.YpsoTbrRequest
import app.aaps.pump.ypsopump.tbr.YpsoTbrWriteResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoTbrControllerTest {
    private var clock = 1_000_000L

    /** A pump that follows the measured START_STOP_TBR semantics. */
    private inner class Pump(var running: Boolean = true, var percent: Int = 100, var remaining: Int = 0) : YpsoTbrLink {
        val commands = mutableListOf<Pair<Int, Int>>()
        var readable = true
        var dropAck = false
        var statusAfterCommand = true
        /** Answers a start with a proven counter rejection this many times, like transport recovery does. */
        var counterRetries = 0
        var sendStart = true
        var onCommand: (Int) -> Unit = {}

        override fun status(): YpsoTbrObservation? = if (readable) observe() else null

        /** The pump appends a running TBR row whenever a start takes effect. */
        var nextRowId = 48_300L
        var head: YpsoTbrHeadRow? = null
        var headReadable = true
        override fun headRow(): YpsoTbrHeadRow? = head.takeIf { headReadable }

        private fun observe() = YpsoTbrObservation(running, percent, remaining, clock)

        override fun command(
            percent: Int,
            durationMinutes: Int,
            effective: (YpsoTbrObservation) -> Boolean,
            beforeDispatch: (Long) -> Unit,
        ): YpsoTbrCommandEvidence {
            if (durationMinutes > 0 && !sendStart) return YpsoTbrCommandEvidence(YpsoTbrWriteResult.NotSent("link"), null, null, null)
            val dispatchedAt = clock
            repeat(if (durationMinutes > 0) counterRetries + 1 else 1) { beforeDispatch(clock); clock += 100 }
            commands += percent to durationMinutes
            onCommand(durationMinutes)
            clock += 400
            val result = when {
                durationMinutes == 0 -> { this.percent = 100; remaining = 0; YpsoTbrWriteResult.Acknowledged }
                !running -> YpsoTbrWriteResult.Rejected(YpsoTbrRejectReason.PUMP_STOPPED)
                this.percent != 100 -> YpsoTbrWriteResult.Rejected(YpsoTbrRejectReason.TBR_ALREADY_ACTIVE)
                else -> {
                    this.percent = percent; remaining = durationMinutes
                    head = YpsoTbrHeadRow(nextRowId++, 9, percent, durationMinutes)
                    YpsoTbrWriteResult.Acknowledged
                }
            }
            val reported = if (dropAck) YpsoTbrWriteResult.Uncertain("lost ACK") else result
            val ackAt = if (reported == YpsoTbrWriteResult.Acknowledged) clock else null
            clock += 500
            return YpsoTbrCommandEvidence(reported, ackAt, dispatchedAt, if (statusAfterCommand) observe() else null)
        }
    }

    /** Records keyed by temporary ID, mirroring the idempotent PumpSync temp-ID transactions. */
    private class Records : YpsoTbrRecords {
        data class Record(val start: Long, val percent: Int, var durationMs: Long)
        val byTempId = linkedMapOf<Long, Record>()
        var saves = true
        var shortens = true
        val reconciled = mutableListOf<YpsoTbrObservation>()
        override fun saveStart(attempt: YpsoTbrAttempt, timestamp: Long): YpsoTbrRecords.Saved {
            if (!saves) return YpsoTbrRecords.Saved.NOT_SAVED
            byTempId.getOrPut(attempt.temporaryId) { Record(timestamp, attempt.percent, attempt.durationMinutes * 60_000L) }
            return YpsoTbrRecords.Saved.SAVED
        }
        override fun shortenStart(attempt: YpsoTbrAttempt, end: Long): Boolean {
            if (!shortens) return false
            val record = byTempId[attempt.temporaryId] ?: return false
            record.durationMs = minOf(record.durationMs, end - record.start)
            return true
        }
        override fun reconcileWith(observation: YpsoTbrObservation) { reconciled += observation }
        fun runningAt(at: Long) = byTempId.values.lastOrNull { it.start <= at && it.start + it.durationMs > at }
    }

    private class MemoryStore : YpsoTbrAttemptStore {
        var attempts = emptyList<YpsoTbrAttempt>()
        override fun loadAll() = attempts
        override fun commitAll(attempts: List<YpsoTbrAttempt>) { this.attempts = attempts }
    }

    private val store = MemoryStore()
    private val journal = YpsoTbrJournal(store)
    private val records = Records()

    private fun controller(pump: YpsoTbrLink, baseline: Long? = 7L) =
        YpsoTbrController(pump, journal, records, { "10054912" }, { baseline }, { clock })

    private val starts get() = store.attempts.filter { it.kind == YpsoTbrAttempt.Kind.START }
    private val stops get() = store.attempts.filter { it.kind == YpsoTbrAttempt.Kind.STOP }

    @Test
    fun `start without a running TBR sends one command and records the acknowledged start`() {
        val pump = Pump()
        val result = controller(pump).start(YpsoTbrRequest(150, 30), "NORMAL")

        assertEquals(Result.Started(YpsoTbrRequest(150, 30)), result)
        assertEquals(listOf(150 to 30), pump.commands)
        assertEquals(1_000_500L, records.runningAt(clock)!!.start)
        assertTrue(starts.single().accounted)
        assertEquals(7L, starts.single().baselinePumpId)
    }

    @Test
    fun `replacement stops first, journals the stop and records the real gap`() {
        val pump = Pump()
        val controller = controller(pump)
        controller.start(YpsoTbrRequest(80, 30), "NORMAL")

        val result = controller.start(YpsoTbrRequest(120, 15), "NORMAL")

        assertTrue(result is Result.Started)
        assertEquals(listOf(80 to 30, 100 to 0, 120 to 15), pump.commands)
        val (old, new) = records.byTempId.values.toList()
        assertEquals(stops.single().effectiveAt, old.start + old.durationMs)
        assertTrue(new.start > old.start + old.durationMs)
        assertEquals(YpsoTbrAttempt.State.EFFECTIVE, stops.single().state)
    }

    @Test
    fun `a failed start after a confirmed stop never reports success, so no paired SMB follows`() {
        val pump = Pump()
        val controller = controller(pump)
        controller.start(YpsoTbrRequest(80, 30), "NORMAL")
        pump.sendStart = false

        val result = controller.start(YpsoTbrRequest(0, 30), "NORMAL")

        assertEquals(Result.Failed(Reason.COMMAND_NOT_SENT, pumpChanged = true), result)
        assertNull(records.runningAt(clock))
    }

    @Test
    fun `a stopped pump fails before any command`() {
        val pump = Pump(running = false)
        val result = controller(pump).start(YpsoTbrRequest(150, 30), "NORMAL")

        assertEquals(Result.Failed(Reason.PUMP_STOPPED, false), result)
        assertTrue(pump.commands.isEmpty())
        assertTrue(store.attempts.isEmpty())
    }

    @Test
    fun `a start rejected after a confirmed stop leaves AAPS showing no TBR`() {
        val pump = Pump()
        val controller = controller(pump)
        controller.start(YpsoTbrRequest(80, 30), "NORMAL")
        // The pump is stopped by hand between the confirmed stop and the new start (code 135).
        pump.onCommand = { minutes -> if (minutes == 0) pump.onCommand = { pump.running = false } }

        val result = controller.start(YpsoTbrRequest(120, 15), "NORMAL")

        assertEquals(Result.Failed(Reason.START_REJECTED, pumpChanged = true), result)
        assertNull(records.runningAt(clock))
        assertEquals(YpsoTbrAttempt.State.NO_EFFECT, starts.last().state)
    }

    @Test
    fun `lost ACK with matching status records the start from its dispatch time`() {
        val pump = Pump().apply { dropAck = true }
        val result = controller(pump).start(YpsoTbrRequest(150, 30), "NORMAL")

        assertTrue(result is Result.Started)
        assertEquals(1_000_000L, records.runningAt(clock)!!.start)
    }

    @Test
    fun `proven counter rejection retries dispatch once without breaking the journal`() {
        val pump = Pump().apply { counterRetries = 1 }
        val result = controller(pump).start(YpsoTbrRequest(150, 30), "NORMAL")

        assertTrue(result is Result.Started)
        assertEquals(1_000_000L, starts.single().dispatchedAt)
    }

    @Test
    fun `unreadable post-start status fails, records nothing and stays open for later status`() {
        val pump = Pump().apply { statusAfterCommand = false }
        val controller = controller(pump)
        val result = controller.start(YpsoTbrRequest(150, 30), "NORMAL")

        assertEquals(Result.Failed(Reason.START_NOT_CONFIRMED, pumpChanged = true), result)
        assertTrue(records.byTempId.isEmpty())
        assertEquals(YpsoTbrAttempt.State.DISPATCHED, starts.single().state)
        assertTrue(controller.hasUnresolved())
    }

    @Test
    fun `a later status resolves a dispatched start without resending it`() {
        val pump = Pump().apply { statusAfterCommand = false }
        val controller = controller(pump)
        controller.start(YpsoTbrRequest(150, 30), "NORMAL")
        clock += 3 * 60_000L
        pump.remaining = 27

        controller.onStatus(pump.status()!!)

        assertEquals(YpsoTbrAttempt.State.EFFECTIVE, starts.single().state)
        assertEquals(1_000_000L, records.runningAt(clock)!!.start)
        assertEquals(1, pump.commands.size)
        assertFalse(controller.hasUnresolved())
    }

    @Test
    fun `a later idle status proves the dispatched start is not running`() {
        val pump = Pump().apply { statusAfterCommand = false }
        val controller = controller(pump)
        controller.start(YpsoTbrRequest(150, 30), "NORMAL")
        pump.percent = 100
        pump.remaining = 0
        clock += 1_000

        controller.onStatus(pump.status()!!)

        assertEquals(YpsoTbrAttempt.State.NO_EFFECT, starts.single().state)
        assertTrue(records.byTempId.isEmpty())
    }

    @Test
    fun `an unsaved start is retried by later status until its record exists`() {
        val pump = Pump()
        records.saves = false
        val controller = controller(pump)

        assertEquals(Result.Failed(Reason.NOT_SAVED, pumpChanged = true), controller.start(YpsoTbrRequest(150, 30), "NORMAL"))
        assertTrue(controller.hasUnresolved())

        records.saves = true
        clock += 1_000
        controller.onStatus(pump.status()!!)

        assertEquals(1_000_500L, records.runningAt(clock)!!.start)
        assertTrue(starts.single().accounted)
        assertFalse(controller.hasUnresolved())
    }

    @Test
    fun `cancel with an idle pump sends nothing and never cuts records locally`() {
        val pump = Pump()
        val controller = controller(pump)
        records.byTempId[1L] = Records.Record(0, 80, 60 * 60_000L)

        val result = controller.cancel()

        assertEquals(Result.Stopped(enacted = false), result)
        assertTrue(pump.commands.isEmpty())
        assertEquals(60 * 60_000L, records.byTempId[1L]!!.durationMs)
    }

    @Test
    fun `cancel in Stop mode leaves the pump suspend record to history`() {
        val pump = Pump(running = false)
        val result = controller(pump).cancel()

        assertEquals(Result.Stopped(enacted = false), result)
        assertTrue(pump.commands.isEmpty())
    }

    @Test
    fun `cancel stops a TBR set on the pump without touching any AAPS record`() {
        val pump = Pump(percent = 110, remaining = 40)
        records.byTempId[1L] = Records.Record(0, 110, 60 * 60_000L)

        val result = controller(pump).cancel()

        assertEquals(Result.Stopped(enacted = true), result)
        assertEquals(listOf(100 to 0), pump.commands)
        assertEquals(60 * 60_000L, records.byTempId[1L]!!.durationMs)
    }

    @Test
    fun `an unconfirmed stop is journalled, keeps the AAPS record and reports failure`() {
        val pump = Pump()
        val controller = controller(pump)
        controller.start(YpsoTbrRequest(110, 60), "NORMAL")
        pump.statusAfterCommand = false

        val result = controller.cancel()

        assertEquals(Result.Failed(Reason.STOP_NOT_CONFIRMED, pumpChanged = true), result)
        assertEquals(60 * 60_000L, records.byTempId.values.single().durationMs)
        assertEquals(YpsoTbrAttempt.State.DISPATCHED, stops.single().state)
        assertTrue(controller.hasUnresolved())
    }

    @Test
    fun `status never resolves a stop because history carries its end time`() {
        val pump = Pump()
        val controller = controller(pump)
        controller.start(YpsoTbrRequest(110, 60), "NORMAL")
        pump.statusAfterCommand = false
        controller.cancel()
        clock += 1_000

        controller.onStatus(pump.status()!!)

        assertEquals(YpsoTbrAttempt.State.NO_EFFECT, stops.single().state)
        assertEquals(60 * 60_000L, records.byTempId.values.single().durationMs)
        assertFalse(controller.hasUnresolved())
    }

    @Test
    fun `a stop whose record cut failed is replayed by later status`() {
        val pump = Pump()
        val controller = controller(pump)
        controller.start(YpsoTbrRequest(0, 60), "NORMAL")
        records.shortens = false

        assertEquals(Result.Stopped(enacted = true), controller.cancel())
        assertTrue(controller.hasUnresolved())
        assertEquals(60 * 60_000L, records.byTempId.values.single().durationMs)

        records.shortens = true
        clock += 1_000
        controller.onStatus(pump.status()!!)

        val record = records.byTempId.values.single()
        assertEquals(stops.single().effectiveAt, record.start + record.durationMs)
        assertFalse(controller.hasUnresolved())
    }

    @Test
    fun `every status is also reconciled against active records`() {
        val pump = Pump()
        val controller = controller(pump)
        controller.onStatus(pump.status()!!)

        assertEquals(1, records.reconciled.size)
    }

    @Test
    fun `a proven start is identified by its own history row on the command link`() {
        val pump = Pump()
        controller(pump).start(YpsoTbrRequest(150, 30), "NORMAL")

        assertEquals(48_300L, starts.single().rowPumpId)
    }

    @Test
    fun `a newest row that is not this start is never taken as its identity`() {
        val pump = Pump()
        pump.onCommand = { pump.onCommand = {}; Unit }
        val controller = controller(pump)
        pump.headReadable = false
        controller.start(YpsoTbrRequest(150, 30), "NORMAL")
        pump.head = YpsoTbrHeadRow(48_400L, 2, 150, 0)
        pump.headReadable = true
        clock += 60_000

        controller.onStatus(pump.status()!!.copy(remainingMinutes = 29))

        assertNull(starts.single().rowPumpId)
    }

    @Test
    fun `status identifies a start later while the pump still runs it`() {
        val pump = Pump().apply { headReadable = false }
        val controller = controller(pump)
        controller.start(YpsoTbrRequest(150, 30), "NORMAL")
        pump.headReadable = true
        clock += 60_000

        controller.onStatus(pump.status()!!.copy(remainingMinutes = 29))

        assertEquals(48_300L, starts.single().rowPumpId)
    }

    @Test
    fun `routine status never resolves the command currently in flight`() {
        val pump = Pump()
        val controller = controller(pump)
        var observed: Result? = null
        pump.onCommand = { controller.onStatus(YpsoTbrObservation(true, 100, 0, clock - 10_000)) }

        observed = controller.start(YpsoTbrRequest(150, 30), "NORMAL")

        assertTrue(observed is Result.Started)
        assertEquals(YpsoTbrAttempt.State.EFFECTIVE, starts.single().state)
    }

    @Test
    fun `an unreadable journal fails closed without touching the pump`() {
        val pump = Pump()
        val broken = object : YpsoTbrAttemptStore {
            override fun loadAll(): List<YpsoTbrAttempt> = error("unsupported TBR journal version")
            override fun commitAll(attempts: List<YpsoTbrAttempt>) = error("unsupported TBR journal version")
        }
        val controller = YpsoTbrController(pump, YpsoTbrJournal(broken), records, { "10054912" }, { 7L }, { clock })

        assertEquals(Result.Failed(Reason.INTERNAL_ERROR, true), controller.start(YpsoTbrRequest(150, 30), "NORMAL"))
        assertEquals(Result.Failed(Reason.INTERNAL_ERROR, true), controller.cancel())
        assertTrue(pump.commands.isEmpty())
        assertTrue(runCatching { controller.hasUnresolved() }.isFailure)
    }

    @Test
    fun `start is refused until history has a cursor to bind the pump row`() {
        val pump = Pump()
        val result = controller(pump, baseline = null).start(YpsoTbrRequest(150, 30), "NORMAL")

        assertEquals(Result.Failed(Reason.HISTORY_NOT_READY, false), result)
        assertTrue(pump.commands.isEmpty())
    }

    @Test
    fun `request validation matches the measured pump limits`() {
        YpsoTbrRequest(0, 15)
        YpsoTbrRequest(500, 1440)
        YpsoTbrRequest(1, 16)
        for ((percent, minutes) in listOf(501 to 15, 50 to 14, 50 to 1441, 100 to 30, -1 to 15)) {
            assertTrue(runCatching { YpsoTbrRequest(percent, minutes) }.isFailure, "$percent/$minutes")
        }
    }

    @Test
    fun `absolute conversion uses the scheduled rate and caps at the pump maximum`() {
        assertEquals(150, YpsoTbrRequest.percentFor(0.75, 0.5))
        assertEquals(0, YpsoTbrRequest.percentFor(0.0, 0.0))
        assertEquals(500, YpsoTbrRequest.percentFor(10.0, 0.5))
        assertEquals(33, YpsoTbrRequest.percentFor(0.1, 0.3))
        assertTrue(runCatching { YpsoTbrRequest.percentFor(0.2, 0.0) }.isFailure)
        assertTrue(runCatching { YpsoTbrRequest.percentFor(Double.NaN, 0.5) }.isFailure)
    }
}
