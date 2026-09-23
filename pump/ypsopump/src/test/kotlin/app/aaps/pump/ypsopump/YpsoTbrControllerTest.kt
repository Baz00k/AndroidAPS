package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.tbr.YpsoTbrAttempt
import app.aaps.pump.ypsopump.tbr.YpsoTbrAttemptStore
import app.aaps.pump.ypsopump.tbr.YpsoTbrCommandEvidence
import app.aaps.pump.ypsopump.tbr.YpsoTbrController
import app.aaps.pump.ypsopump.tbr.YpsoTbrJournal
import app.aaps.pump.ypsopump.tbr.YpsoTbrLink
import app.aaps.pump.ypsopump.tbr.YpsoTbrObservation
import app.aaps.pump.ypsopump.tbr.YpsoTbrRecords
import app.aaps.pump.ypsopump.tbr.YpsoTbrRejectReason
import app.aaps.pump.ypsopump.tbr.YpsoTbrRequest
import app.aaps.pump.ypsopump.tbr.YpsoTbrWriteResult
import org.junit.jupiter.api.Assertions.assertEquals
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

        override fun status(): YpsoTbrObservation? = if (readable) observe() else null

        private fun observe() = YpsoTbrObservation(running, percent, remaining, clock)

        override fun command(
            percent: Int,
            durationMinutes: Int,
            effective: (YpsoTbrObservation) -> Boolean,
            beforeDispatch: (Long) -> Unit,
        ): YpsoTbrCommandEvidence {
            val dispatchedAt = clock
            beforeDispatch(dispatchedAt)
            commands += percent to durationMinutes
            clock += 500
            val result = when {
                durationMinutes == 0 -> { this.percent = 100; remaining = 0; YpsoTbrWriteResult.Acknowledged }
                !running -> YpsoTbrWriteResult.Rejected(YpsoTbrRejectReason.PUMP_STOPPED)
                this.percent != 100 -> YpsoTbrWriteResult.Rejected(YpsoTbrRejectReason.TBR_ALREADY_ACTIVE)
                else -> { this.percent = percent; remaining = durationMinutes; YpsoTbrWriteResult.Acknowledged }
            }
            val reported = if (dropAck) YpsoTbrWriteResult.Uncertain("lost ACK") else result
            val ackAt = if (reported == YpsoTbrWriteResult.Acknowledged) clock else null
            clock += 500
            return YpsoTbrCommandEvidence(reported, ackAt, dispatchedAt, if (statusAfterCommand) observe() else null)
        }
    }

    private class Records : YpsoTbrRecords {
        data class Record(val start: Long, val percent: Int, val durationMs: Long, var end: Long? = null)
        val records = mutableListOf<Record>()
        var saves = true
        override fun started(attempt: YpsoTbrAttempt, timestamp: Long): Boolean {
            if (!saves) return false
            records += Record(timestamp, attempt.percent, attempt.durationMinutes * 60_000L)
            return true
        }
        override fun stopped(timestamp: Long): Boolean {
            records.lastOrNull { it.end == null && it.start + it.durationMs > timestamp }?.end = timestamp
            return true
        }
        val running get() = records.lastOrNull { it.end == null }
    }

    private class MemoryStore : YpsoTbrAttemptStore {
        var attempts = emptyList<YpsoTbrAttempt>()
        override fun loadAll() = attempts
        override fun commitAll(attempts: List<YpsoTbrAttempt>) { this.attempts = attempts }
    }

    private val store = MemoryStore()
    private val journal = YpsoTbrJournal(store)
    private val records = Records()

    private fun controller(pump: Pump, baseline: Long? = 7L) =
        YpsoTbrController(pump, journal, records, { "10054912" }, { baseline }, { clock })

    @Test
    fun `start without a running TBR sends one command and records the acknowledged start`() {
        val pump = Pump()
        val result = controller(pump).start(YpsoTbrRequest(150, 30), "NORMAL")

        assertEquals(YpsoTbrController.Result.Started(YpsoTbrRequest(150, 30)), result)
        assertEquals(listOf(150 to 30), pump.commands)
        assertEquals(1_000_500L, records.running!!.start)
        assertEquals(YpsoTbrAttempt.State.STARTED, store.attempts.single().state)
        assertEquals(7L, store.attempts.single().baselinePumpId)
    }

    @Test
    fun `replacement stops the running TBR first and records the real gap`() {
        val pump = Pump(percent = 80, remaining = 20)
        records.records += Records.Record(0, 80, 30 * 60_000L)

        val result = controller(pump).start(YpsoTbrRequest(120, 15), "NORMAL")

        assertTrue(result is YpsoTbrController.Result.Started)
        assertEquals(listOf(100 to 0, 120 to 15), pump.commands)
        val (old, new) = records.records
        assertEquals(1_000_500L, old.end)
        assertEquals(1_001_500L, new.start)
    }

    @Test
    fun `a stopped pump rejects before any command`() {
        val pump = Pump(running = false)
        val result = controller(pump).start(YpsoTbrRequest(150, 30), "NORMAL")

        assertEquals(YpsoTbrController.Result.NotChanged(YpsoTbrController.Reason.PUMP_STOPPED), result)
        assertTrue(pump.commands.isEmpty())
        assertTrue(store.attempts.isEmpty())
    }

    @Test
    fun `failed start after a confirmed stop leaves AAPS showing no TBR`() {
        val pump = object : YpsoTbrLink {
            val inner = Pump(percent = 80, remaining = 20)
            override fun status() = inner.status()
            override fun command(percent: Int, durationMinutes: Int, effective: (YpsoTbrObservation) -> Boolean, beforeDispatch: (Long) -> Unit): YpsoTbrCommandEvidence {
                val evidence = inner.command(percent, durationMinutes, effective, beforeDispatch)
                if (durationMinutes == 0) inner.running = false
                return evidence
            }
        }
        records.records += Records.Record(0, 80, 30 * 60_000L)

        val result = YpsoTbrController(pump, journal, records, { "10054912" }, { 7L }, { clock })
            .start(YpsoTbrRequest(120, 15), "NORMAL")

        assertEquals(YpsoTbrController.Result.Uncertain(YpsoTbrController.Reason.START_REJECTED, previousStopped = true), result)
        assertEquals(null, records.running)
        assertEquals(YpsoTbrAttempt.State.NOT_STARTED, store.attempts.single().state)
    }

    @Test
    fun `lost ACK with matching status records the start from its dispatch time`() {
        val pump = Pump().apply { dropAck = true }
        val result = controller(pump).start(YpsoTbrRequest(150, 30), "NORMAL")

        assertTrue(result is YpsoTbrController.Result.Started)
        assertEquals(1_000_000L, records.running!!.start)
    }

    @Test
    fun `unreadable post-start status stays uncertain and records nothing`() {
        val pump = Pump().apply { statusAfterCommand = false }
        val result = controller(pump).start(YpsoTbrRequest(150, 30), "NORMAL")

        assertEquals(YpsoTbrController.Result.Uncertain(YpsoTbrController.Reason.START_NOT_CONFIRMED, previousStopped = false), result)
        assertEquals(null, records.running)
        assertEquals(YpsoTbrAttempt.State.DISPATCHED, store.attempts.single().state)
        assertEquals(1, pump.commands.size)
    }

    @Test
    fun `a later status resolves a dispatched start without resending it`() {
        val pump = Pump().apply { statusAfterCommand = false }
        val controller = controller(pump)
        controller.start(YpsoTbrRequest(150, 30), "NORMAL")
        clock += 3 * 60_000L
        pump.remaining = 27

        controller.resolvePending(pump.status()!!)

        assertEquals(YpsoTbrAttempt.State.STARTED, store.attempts.single().state)
        assertEquals(1_000_000L, records.running!!.start)
        assertEquals(1, pump.commands.size)
    }

    @Test
    fun `a later idle status proves the dispatched start is not running`() {
        val pump = Pump().apply { statusAfterCommand = false }
        val controller = controller(pump)
        controller.start(YpsoTbrRequest(150, 30), "NORMAL")
        pump.percent = 100
        pump.remaining = 0

        controller.resolvePending(pump.status()!!)

        assertEquals(YpsoTbrAttempt.State.NOT_STARTED, store.attempts.single().state)
        assertEquals(null, records.running)
    }

    @Test
    fun `cancel without a running TBR sends nothing but clears any AAPS record`() {
        val pump = Pump()
        records.records += Records.Record(0, 80, 30 * 60_000L)

        val result = controller(pump).cancel()

        assertEquals(YpsoTbrController.Result.Stopped(enacted = false), result)
        assertTrue(pump.commands.isEmpty())
        assertEquals(null, records.running)
    }

    @Test
    fun `cancel stops any running TBR including one set on the pump`() {
        val pump = Pump(percent = 110, remaining = 40)

        val result = controller(pump).cancel()

        assertEquals(YpsoTbrController.Result.Stopped(enacted = true), result)
        assertEquals(listOf(100 to 0), pump.commands)
    }

    @Test
    fun `unconfirmed stop keeps the AAPS record and reports uncertainty`() {
        val pump = Pump(percent = 110, remaining = 40).apply { statusAfterCommand = false }
        records.records += Records.Record(0, 110, 60 * 60_000L)

        val result = controller(pump).cancel()

        assertEquals(YpsoTbrController.Result.Uncertain(YpsoTbrController.Reason.STOP_NOT_CONFIRMED, previousStopped = false), result)
        assertEquals(null, records.running!!.end)
    }

    @Test
    fun `start is refused until history has a cursor to bind the pump row`() {
        val pump = Pump()
        val result = controller(pump, baseline = null).start(YpsoTbrRequest(150, 30), "NORMAL")

        assertEquals(YpsoTbrController.Result.NotChanged(YpsoTbrController.Reason.HISTORY_NOT_READY), result)
        assertTrue(pump.commands.isEmpty())
    }

    @Test
    fun `unsaved start record is reported as uncertain`() {
        val pump = Pump()
        records.saves = false
        val result = controller(pump).start(YpsoTbrRequest(150, 30), "NORMAL")

        assertEquals(YpsoTbrController.Result.Uncertain(YpsoTbrController.Reason.NOT_SAVED, previousStopped = false), result)
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
