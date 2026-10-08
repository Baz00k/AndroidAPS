package app.aaps.pump.ypsopump

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.ypsopump.history.YpsoEventIdentity
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoHistoryEvent
import app.aaps.pump.ypsopump.history.YpsoHistoryIngestion
import app.aaps.pump.ypsopump.history.YpsoHistoryIngestionResult
import app.aaps.pump.ypsopump.history.YpsoHistorySnapshot
import app.aaps.pump.ypsopump.history.YpsoHistoryState
import app.aaps.pump.ypsopump.history.YpsoHistoryStateStore
import app.aaps.pump.ypsopump.tbr.YpsoTbrAttempt
import app.aaps.pump.ypsopump.tbr.YpsoTbrAttemptStore
import app.aaps.pump.ypsopump.tbr.YpsoTbrCommandEvidence
import app.aaps.pump.ypsopump.tbr.YpsoTbrController
import app.aaps.pump.ypsopump.tbr.YpsoTbrHistoryAccounting
import app.aaps.pump.ypsopump.tbr.YpsoTbrJournal
import app.aaps.pump.ypsopump.tbr.YpsoTbrLink
import app.aaps.pump.ypsopump.tbr.YpsoTbrObservation
import app.aaps.pump.ypsopump.tbr.YpsoTbrRecordLookup
import app.aaps.pump.ypsopump.tbr.YpsoTbrRecords
import app.aaps.pump.ypsopump.tbr.YpsoTbrRequest
import app.aaps.pump.ypsopump.tbr.YpsoTbrWriteResult
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class YpsoTbrHistoryAccountingTest {
    private val zone = ZoneId.of("UTC")
    private val serial = "10054912"
    private val sync: PumpSync = mock()
    private val store = object : YpsoTbrAttemptStore {
        var attempts = emptyList<YpsoTbrAttempt>()
        override fun loadAll() = attempts
        override fun commitAll(attempts: List<YpsoTbrAttempt>) { this.attempts = attempts }
    }
    private val journal = YpsoTbrJournal(store)

    /** A fake database keyed by pump ID, with temp-ID binding, mirroring the PumpSync transactions. */
    private val saved = mutableMapOf<Long, Pair<Long, Long>>()
    private val provisional = mutableMapOf<Long, Pair<Long, Long>>()
    private val invalid = mutableSetOf<Long>()
    private var registeredAt = 0L
    private val statusSuspends = mutableListOf<YpsoTbrRecordLookup.StatusSuspend>()
    private val accounting = YpsoTbrHistoryAccounting(sync, journal, object : YpsoTbrRecordLookup {
        override fun byPumpId(pumpId: Long, pumpSerial: String, start: Long) =
            saved[pumpId]?.let { YpsoTbrRecordLookup.Record(it.first, it.second, pumpId !in invalid) }
        override fun suspendActiveAt(pumpSerial: String, at: Long) = suspendOpen
        override fun anyStartedBetween(pumpSerial: String, from: Long, to: Long, exceptPumpId: Long) =
            saved.any { (id, record) -> id != exceptPumpId && record.first > from && record.first < to }
        override fun latestSuspendBefore(pumpSerial: String, at: Long) = saved.entries
            .filter { it.key in suspends && it.value.first <= at }.maxByOrNull { it.value.first }
            ?.let { YpsoTbrRecordLookup.Suspend(it.key, null, it.value.first, it.value.second, it.key !in invalid) }
        override fun statusSuspendsFrom(pumpSerial: String, from: Long) = statusSuspends.filter { it.start >= from }
        override fun rowSuspendsAt(pumpSerial: String, at: Long) = saved.entries
            .filter { it.key in suspends && it.value.first == at }
            .map { YpsoTbrRecordLookup.Suspend(it.key, null, it.value.first, it.value.second, it.key !in invalid) }
    }, registeredAt = { registeredAt })
    private var suspendOpen = false
    private val suspends = mutableSetOf<Long>()

    init {
        whenever(sync.syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())).thenAnswer {
            val pumpId = it.getArgument<Long>(5)
            if (pumpId in invalid) return@thenAnswer false
            saved[pumpId] = it.getArgument<Long>(0) to it.getArgument(2)
            if (it.getArgument<PumpSync.TemporaryBasalType?>(4) == PumpSync.TemporaryBasalType.PUMP_SUSPEND) suspends += pumpId
            true
        }
        whenever(sync.invalidateTemporaryBasalWithPumpId(any(), any(), any())).thenAnswer {
            invalid += it.getArgument<Long>(0)
            true
        }
        whenever(sync.syncTemporaryBasalWithTempId(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())).thenAnswer {
            val tempId = it.getArgument<Long>(4)
            if (provisional.remove(tempId) == null) return@thenAnswer false
            saved[it.getArgument(6)] = it.getArgument<Long>(0) to it.getArgument(2)
            true
        }
    }

    private val pumpStartSeconds = 843_494_510L
    private val pumpStart = LocalDateTime.of(2000, 1, 1, 0, 0).plus(pumpStartSeconds, ChronoUnit.SECONDS)
        .atZone(zone).toInstant().toEpochMilli()

    private fun event(sequence: Long, type: Int, v1: Int, v2: Int, seconds: Long = pumpStartSeconds) = YpsoHistoryEvent(
        YpsoEventIdentity(serial, 0, sequence),
        YpsoHistoryEntry(seconds, type, v1, v2, 0, sequence, 0),
    )

    private fun startedAttempt(id: String = "a", percent: Int = 120, minutes: Int = 15, baseline: Long = 48_222L, at: Long = pumpStart + 2_000L, tempId: Long = 99L, row: Long? = null) =
        YpsoTbrAttempt(
            id = id, kind = YpsoTbrAttempt.Kind.START, pumpSerial = serial, percent = percent, durationMinutes = minutes,
            type = "NORMAL", temporaryId = tempId, baselinePumpId = baseline, createdAt = at - 1_000L,
        ).also {
            journal.prepare(it)
            journal.dispatched(id, at - 500L)
            journal.effective(id, at, at)
            journal.accounted(id)
            row?.let { journal.identified(id, it) }
            provisional[tempId] = at to minutes * 60_000L
        }

    @Test
    fun `manual running TBR imports at pump time as a percent record`() {
        assertNull(accounting.apply(event(48_224, 9, 110, 15), serial, zone))

        assertEquals(pumpStart to 15 * 60_000L, saved[48_224L])
    }

    @Test
    fun `manual TBR cancelled on the pump shortens to its elapsed minutes`() {
        accounting.apply(event(48_221, 9, 80, 15), serial, zone)
        accounting.apply(event(48_221, 10, 80, 8), serial, zone)

        assertEquals(pumpStart to 8 * 60_000L, saved[48_221L])
    }

    @Test
    fun `AAPS started TBR binds its row to the provisional record at the AAPS start time`() {
        startedAttempt()

        assertNull(accounting.apply(event(48_224, 9, 120, 15), serial, zone))

        assertEquals(pumpStart + 2_000L to 15 * 60_000L, saved[48_224L])
        verify(sync, never()).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())
        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `bound TBR cancelled on the pump shortens without moving its start`() {
        startedAttempt()
        accounting.apply(event(48_224, 9, 120, 15), serial, zone)

        accounting.apply(event(48_224, 10, 120, 6), serial, zone)

        assertEquals(pumpStart + 2_000L to 6 * 60_000L, saved[48_224L])
    }

    @Test
    fun `a TBR AAPS already stopped is not extended by its terminal row`() {
        startedAttempt()
        val stop = YpsoTbrAttempt(
            id = "stop", kind = YpsoTbrAttempt.Kind.STOP, pumpSerial = serial, percent = 100, durationMinutes = 0,
            type = "", temporaryId = 5L, baselinePumpId = null, createdAt = pumpStart,
        )
        journal.prepare(stop)
        journal.dispatched("stop", pumpStart + 91_000L)
        journal.stopEffective("stop", pumpStart + 2_000L + 90_000L)

        accounting.apply(event(48_224, 10, 120, 2), serial, zone)

        assertEquals(pumpStart + 2_000L to 90_000L, saved[48_224L])
    }

    @Test
    fun `without a usable clock an ended row that could be an AAPS start waits for a reading`() {
        startedAttempt(at = pumpStart + 3 * 60_000L)

        assertNotNull(accounting.apply(event(48_224, 10, 120, 3), serial, zone, waitForClock = true))
        assertNull(saved[48_224L])
    }

    @Test
    fun `when no clock reading comes history stays blocked without suppressing the row`() {
        startedAttempt(at = pumpStart + 3 * 60_000L)

        assertNotNull(accounting.apply(event(48_224, 9, 120, 15), serial, zone))

        assertNull(saved[48_224L])
        assertNull(store.attempts.single().unmatchedRowPumpId)
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `an unmatched start no longer holds later rows with its percent`() {
        startedAttempt(at = pumpStart + 3 * 60_000L)
        journal.unmatched("a", 48_224L)

        assertNull(accounting.apply(event(48_230, 9, 120, 15, seconds = pumpStartSeconds + 3_600), serial, zone, waitForClock = true))
        assertEquals(pumpStart + 3_600_000L to 15 * 60_000L, saved[48_230L])
    }

    @Test
    fun `a replayed pump row keeps the start first written despite a slightly different offset`() {
        assertNull(accounting.apply(event(48_224, 9, 110, 15), serial, zone, pumpClockOffsetMs = 1_000L))
        assertNull(accounting.apply(event(48_224, 10, 110, 4), serial, zone, pumpClockOffsetMs = 2_000L))

        assertEquals(pumpStart - 1_000L to 4 * 60_000L, saved[48_224L])
    }

    @Test
    fun `clock changes cannot promote missing command coverage to a proven identity`() {
        startedAttempt(percent = 80)
        val replay = SyntheticHistoryReplay()
        val anchor = event(48_222, 14, 10, 0).entry
        assertTrue(replay.scan(listOf(anchor), 14_000L) is YpsoHistoryIngestionResult.Applied)
        val running = event(48_224, 9, 80, 15).entry
        assertTrue(replay.scan(listOf(running, anchor), 14_000L) is YpsoHistoryIngestionResult.Blocked)
        assertTrue(replay.blockedAfterRestart())
        val terminal = event(48_224, 10, 80, 6).entry
        assertTrue(replay.scan(listOf(terminal, anchor), 13_000L) is YpsoHistoryIngestionResult.Blocked)

        verify(sync, never()).syncTemporaryBasalWithTempId(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())
        assertNull(journal.find("a")!!.pumpId)
    }

    @Test
    fun `ended pump basal never takes the identity of a subsequent AAPS start`() {
        val replay = SyntheticHistoryReplay()
        val anchor = event(48_222, 14, 10, 0).entry
        assertTrue(replay.scan(listOf(anchor), 0L) is YpsoHistoryIngestionResult.Applied)
        val pump = ScriptedTbrPump(pumpStart + 10_000L)
        val controller = controller(pump)
        assertTrue(controller.start(YpsoTbrRequest(80, 30), "NORMAL") is YpsoTbrController.Result.Started)
        val attempt = journal.all().single { it.kind == YpsoTbrAttempt.Kind.START }

        // Independent row 48224 started at T and ended before the fresh idle status at T+10s.
        // AAPS starts row 48226 at T+10s; accurate zero clock offset and both history rows are
        // supplied. Timing follows documented semantics, but menu latency is not hardware-tested.
        val independent = event(48_224, 10, 80, 0).entry
        val own = event(48_226, 9, 80, 30, seconds = pumpStartSeconds + 10).entry
        val result = replay.scan(listOf(own, independent, anchor), 0L)

        // Assert the call itself: the pump-ID-keyed fake cannot conceal a wrong merge here.
        verify(sync, never()).syncTemporaryBasalWithTempId(
            any(), any(), any(), any(), org.mockito.kotlin.eq(attempt.temporaryId), anyOrNull(), org.mockito.kotlin.eq(48_224L), any(), any()
        )
        assertTrue(result is YpsoHistoryIngestionResult.Blocked)
        assertNull(journal.find(attempt.id)!!.pumpId)
        assertEquals(48_222L, replay.state.cursor!!.identity.sequence)
        assertTrue(replay.blockedAfterRestart())
        // A changed clock measurement or disappearance of the competing row is not proof.
        assertTrue(replay.scan(listOf(own, anchor), 0L) is YpsoHistoryIngestionResult.Blocked)
        assertTrue(replay.blockedAfterRestart())
        // Established row identity (not a time-based guess) allows replay and clears the gate.
        journal.identified(attempt.id, 48_226L)
        assertTrue(replay.scan(listOf(own, independent, anchor), 0L) is YpsoHistoryIngestionResult.Applied)
        assertEquals(48_226L, journal.find(attempt.id)!!.pumpId)
        assertTrue(!replay.blockedAfterRestart())
    }

    @Test
    fun `terminal row fitting two unbound attempts blocks without choosing the earlier identity`() {
        val replay = SyntheticHistoryReplay()
        val anchor = event(48_222, 14, 10, 0).entry
        assertTrue(replay.scan(listOf(anchor), 0L) is YpsoHistoryIngestionResult.Applied)
        val pump = ScriptedTbrPump(pumpStart + 2_000L)
        val controller = controller(pump)
        assertTrue(controller.start(YpsoTbrRequest(80, 30), "NORMAL") is YpsoTbrController.Result.Started)
        // A normal AAPS replacement sends a confirmed stop before the second start. The same
        // old history baseline is used because neither start has been scanned yet.
        pump.clock = pumpStart + 10_000L
        assertTrue(controller.start(YpsoTbrRequest(80, 30), "NORMAL") is YpsoTbrController.Result.Started)
        val attempts = journal.all().filter { it.kind == YpsoTbrAttempt.Kind.START }
        assertNotNull(attempts.first().stoppedAt)

        // Complete causal history: the earlier row ended, the newer one runs. Both compatible
        // time windows overlap. Check the conservative ambiguity policy, not hardware occurrence.
        val first = event(48_224, 10, 80, 0, seconds = pumpStartSeconds + 2).entry
        val second = event(48_226, 9, 80, 30, seconds = pumpStartSeconds + 10).entry
        val result = replay.scan(listOf(second, first, anchor), 0L)

        verify(sync, never()).syncTemporaryBasalWithTempId(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())
        assertTrue(result is YpsoHistoryIngestionResult.Blocked)
        assertTrue(journal.all().filter { it.kind == YpsoTbrAttempt.Kind.START }.all { it.pumpId == null })
        assertEquals(48_222L, replay.state.cursor!!.identity.sequence)
    }

    private class ScriptedTbrPump(var clock: Long) : YpsoTbrLink {
        private var percent = 100
        private var remaining = 0
        override fun status() = YpsoTbrObservation(true, percent, remaining, clock)
        override fun command(percent: Int, durationMinutes: Int, effective: (YpsoTbrObservation) -> Boolean, beforeDispatch: (Long) -> Unit): YpsoTbrCommandEvidence {
            val dispatched = clock
            beforeDispatch(dispatched)
            this.percent = percent
            remaining = durationMinutes
            clock += 500L
            val acknowledged = clock
            clock += 500L
            return YpsoTbrCommandEvidence(YpsoTbrWriteResult.Acknowledged, acknowledged, dispatched, status())
        }
    }

    private fun controller(pump: ScriptedTbrPump) = YpsoTbrController(
        pump, journal, object : YpsoTbrRecords {
            override fun saveStart(attempt: YpsoTbrAttempt, timestamp: Long): YpsoTbrRecords.Saved {
                provisional[attempt.temporaryId] = timestamp to attempt.durationMinutes * 60_000L
                return YpsoTbrRecords.Saved.SAVED
            }
            override fun shortenStart(attempt: YpsoTbrAttempt, end: Long): Boolean {
                val record = provisional[attempt.temporaryId] ?: return false
                provisional[attempt.temporaryId] = record.first to end - record.first
                return true
            }
            override fun reconcileWith(observation: YpsoTbrObservation) = Unit
        }, { serial }, { 48_222L }, { pump.clock }
    )

    private inner class SyntheticHistoryReplay {
        var state = YpsoHistoryState()
        private val historyStore = object : YpsoHistoryStateStore {
            override fun load() = state
            override fun commit(value: YpsoHistoryState) { state = value }
        }
        private val ingestion = YpsoHistoryIngestion(historyStore, sync, accounting)

        fun blockedAfterRestart() = YpsoHistoryIngestion(historyStore, sync, accounting).basalAccountingBlocked()

        fun scan(rows: List<YpsoHistoryEntry>, offset: Long?): YpsoHistoryIngestionResult {
            val indexed = rows.mapIndexed { index, row -> row.copy(index = index) }
            val snapshot = YpsoHistorySnapshot(indexed.size, indexed.size, 21, 21, indexed.first(), indexed.first(), indexed, true, pumpClockOffsetMs = offset)
            return ingestion.ingest(serial, zone, 21, snapshot)
        }
    }

    @Test
    fun `clockless and missing history cannot enable dosing for an outstanding start`() {
        for (missing in listOf(false, true)) {
            store.attempts = emptyList()
            startedAttempt(at = pumpStart + 3 * 60_000L)
            val replay = SyntheticHistoryReplay()
            val anchor = event(48_222, 14, 10, 0).entry
            assertTrue(replay.scan(listOf(anchor), 0L) is YpsoHistoryIngestionResult.Applied)
            val row = event(48_224, 9, 120, 15).entry
            val rows = if (missing) listOf(event(48_224, 14, 10, 0).entry, anchor) else listOf(row, anchor)
            assertTrue(replay.scan(rows, if (missing) 0L else null) is YpsoHistoryIngestionResult.Blocked)
            assertTrue(replay.blockedAfterRestart())
            assertNull(journal.find("a")!!.pumpId)
            assertNull(journal.find("a")!!.unmatchedRowPumpId)
        }
        verify(sync, never()).syncTemporaryBasalWithTempId(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())
    }

    @Test
    fun `a row whose pump start does not match the AAPS start is imported on its own`() {
        startedAttempt(at = pumpStart + 3 * 60_000L)

        accounting.apply(event(48_224, 10, 120, 3), serial, zone, pumpClockOffsetMs = 0L)

        assertEquals(pumpStart to 3 * 60_000L, saved[48_224L])
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `a row older than the attempt baseline is never bound to it`() {
        startedAttempt(baseline = 48_230L)

        accounting.apply(event(48_224, 9, 120, 15), serial, zone)

        assertEquals(pumpStart to 15 * 60_000L, saved[48_224L])
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `two same-percent attempts bind to their own rows by start window`() {
        startedAttempt("a", percent = 0, minutes = 30, tempId = 1L)
        startedAttempt("b", percent = 0, minutes = 30, at = pumpStart + 4 * 60_000L, tempId = 2L)

        accounting.apply(event(48_224, 10, 0, 3), serial, zone)
        accounting.apply(event(48_226, 9, 0, 30, seconds = pumpStartSeconds + 240), serial, zone)

        assertEquals(48_224L, journal.find("a")!!.pumpId)
        assertEquals(48_226L, journal.find("b")!!.pumpId)
    }

    @Test
    fun `a row that fits two AAPS starts is refused without selecting the earlier one`() {
        startedAttempt("a", percent = 0, minutes = 30, tempId = 1L)
        startedAttempt("b", percent = 0, minutes = 30, at = pumpStart + 30_000L, tempId = 2L)

        assertNotNull(accounting.apply(event(48_224, 10, 0, 0), serial, zone))

        assertNull(journal.find("a")!!.pumpId)
        assertNull(journal.find("b")!!.pumpId)
        verify(sync, never()).syncTemporaryBasalWithTempId(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())
    }

    @Test
    fun `a journalled row identity from an earlier build still binds`() {
        startedAttempt(percent = 0, minutes = 120, at = pumpStart + 10 * 60_000L, row = 48_224L)

        assertNull(accounting.apply(event(48_224, 9, 0, 120), serial, zone))
        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `with a measured pump clock a far-off row time still binds its AAPS start`() {
        // The pump clock runs ten minutes behind the phone.
        val offset = -10 * 60_000L
        startedAttempt(percent = 0, minutes = 120)

        assertNull(accounting.apply(event(48_224, 9, 0, 120, seconds = pumpStartSeconds - 600), serial, zone, pumpClockOffsetMs = offset))

        assertEquals(pumpStart + 2_000L to 120 * 60_000L, saved[48_224L])
        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `with a measured pump clock a TBR set again on the pump is never taken for the AAPS start`() {
        val offset = -10 * 60_000L
        startedAttempt(percent = 0, minutes = 30)
        // Cancelled on the pump after 30 s and set again two minutes later, identical.
        assertNull(accounting.apply(event(48_224, 10, 0, 0, seconds = pumpStartSeconds - 600), serial, zone, pumpClockOffsetMs = offset))
        assertNull(accounting.apply(event(48_226, 9, 0, 30, seconds = pumpStartSeconds - 600 + 150), serial, zone, pumpClockOffsetMs = offset))

        assertEquals(48_224L, store.attempts.single().pumpId)
        assertEquals(pumpStart + 150_000L to 30 * 60_000L, saved[48_226L])
    }

    @Test
    fun `without a measured pump clock a running row that could be an AAPS start waits`() {
        startedAttempt(percent = 0, minutes = 120, at = pumpStart + 10 * 60_000L)

        assertNotNull(accounting.apply(event(48_224, 9, 0, 120), serial, zone, waitForClock = true))
        assertNull(saved[48_224L])
    }

    @Test
    fun `a TBR that ended before the pump was registered in AAPS is passed over`() {
        registeredAt = pumpStart + 31 * 60_000L

        assertNull(accounting.apply(event(48_221, 10, 300, 30), serial, zone))
        assertEquals(emptyMap<Long, Pair<Long, Long>>(), saved)
    }

    @Test
    fun `a TBR running at registration counts from registration until it ends`() {
        registeredAt = pumpStart + 10 * 60_000L

        assertNull(accounting.apply(event(48_221, 9, 300, 30), serial, zone))
        assertEquals(registeredAt to 20 * 60_000L, saved[48_221L])

        assertNull(accounting.apply(event(48_221, 10, 300, 25), serial, zone))
        assertEquals(registeredAt to 15 * 60_000L, saved[48_221L])
    }

    @Test
    fun `a TBR recorded from its running row and cancelled before registration is ended there`() {
        registeredAt = pumpStart + 10 * 60_000L
        accounting.apply(event(48_221, 9, 300, 30), serial, zone)

        assertNull(accounting.apply(event(48_221, 10, 300, 5), serial, zone))

        assertEquals(registeredAt to 1L, saved[48_221L])
    }

    @Test
    fun `a pump stop running at registration counts from registration until its resume`() {
        registeredAt = pumpStart + 10 * 60_000L

        assertNull(accounting.apply(event(48_222, 14, 3, 0), serial, zone))
        assertEquals(registeredAt to 24 * 60 * 60_000L - 10 * 60_000L, saved[48_222L])
        assertNull(accounting.apply(event(48_223, 14, 10, 0, seconds = pumpStartSeconds + 1_800), serial, zone))

        assertEquals(registeredAt to 20 * 60_000L, saved[48_222L])
    }

    @Test
    fun `a pump stop and resume both before registration leave nothing and never block`() {
        registeredAt = pumpStart + 60 * 60_000L

        repeat(2) {
            assertNull(accounting.apply(event(48_222, 14, 3, 0), serial, zone))
            assertNull(accounting.apply(event(48_223, 14, 10, 0, seconds = pumpStartSeconds + 600), serial, zone))
        }

        assertTrue(saved.keys.all { it in invalid })
    }

    @Test
    fun `replaying an earlier stop and resume never removes a later stop still running at registration`() {
        registeredAt = pumpStart + 60 * 60_000L
        val stopA = event(48_222, 14, 3, 0)
        val resumeA = event(48_223, 14, 10, 0, seconds = pumpStartSeconds + 600)
        val stopB = event(48_224, 14, 3, 0, seconds = pumpStartSeconds + 1_200)

        repeat(2) { listOf(stopA, resumeA, stopB).forEach { assertNull(accounting.apply(it, serial, zone)) } }

        assertTrue(48_222L in invalid)
        assertTrue(48_224L !in invalid)
        assertEquals(registeredAt, saved[48_224L]?.first)
    }

    @Test
    fun `a stop running at registration still lapses a day after the pump stopped`() {
        registeredAt = pumpStart + 23 * 60 * 60_000L

        assertNull(accounting.apply(event(48_222, 14, 3, 0), serial, zone))

        assertEquals(registeredAt to 60 * 60_000L, saved[48_222L])
    }

    @Test
    fun `a pump stop a day before registration has lapsed and is passed over`() {
        registeredAt = pumpStart + 24 * 60 * 60_000L

        assertNull(accounting.apply(event(48_222, 14, 3, 0), serial, zone))

        assertEquals(emptyMap<Long, Pair<Long, Long>>(), saved)
    }

    @Test
    fun `an AAPS start whose row reads just before registration still binds`() {
        registeredAt = pumpStart + 1_000L
        startedAttempt()

        assertNull(accounting.apply(event(48_224, 10, 120, 3), serial, zone))
        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `a second stop is recorded even when an earlier status stop ended just before it`() {
        statusSuspends += YpsoTbrRecordLookup.StatusSuspend(77L, pumpStart - 40_000L, 20_000L)

        assertNull(accounting.apply(event(48_224, 14, 3, 0), serial, zone))

        assertEquals(pumpStart to 24 * 60 * 60_000L, saved[48_224L])
    }

    @Test
    fun `a stop row status already recorded covers only the time before status saw it`() {
        statusSuspends += YpsoTbrRecordLookup.StatusSuspend(77L, pumpStart + 3 * 60_000L, 24 * 60 * 60_000L)

        assertNull(accounting.apply(event(48_224, 14, 3, 0), serial, zone))

        assertEquals(pumpStart to 3 * 60_000L, saved[48_224L])
    }

    @Test
    fun `a pump row whose AAPS start is not yet resolved by status waits instead of importing`() {
        YpsoTbrAttempt(
            id = "p", kind = YpsoTbrAttempt.Kind.START, pumpSerial = serial, percent = 0, durationMinutes = 30,
            type = "NORMAL", temporaryId = 7L, baselinePumpId = 48_222L, createdAt = pumpStart,
        ).also { journal.prepare(it); journal.dispatched("p", pumpStart) }

        assertNotNull(accounting.apply(event(48_224, 9, 0, 30), serial, zone))
        assertNull(saved[48_224L])
    }

    @Test
    fun `a manual row overlapping an AAPS start it is not is imported on its own`() {
        startedAttempt(percent = 0, minutes = 30, at = pumpStart + 10 * 60_000L, row = 48_226L)

        assertNull(accounting.apply(event(48_224, 10, 0, 10), serial, zone))
        assertEquals(pumpStart to 10 * 60_000L, saved[48_224L])
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `a manual row with a measured clock is imported beside an AAPS start it does not fit`() {
        startedAttempt(percent = 0, minutes = 30, at = pumpStart + 10 * 60_000L)

        assertNull(accounting.apply(event(48_224, 10, 0, 10), serial, zone, pumpClockOffsetMs = 0L))
        assertEquals(pumpStart to 10 * 60_000L, saved[48_224L])
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `resume never extends an earlier stop that other records followed`() {
        accounting.apply(event(48_215, 14, 3, 0), serial, zone)
        saved[48_215L] = pumpStart to 600_000L
        accounting.apply(event(48_216, 9, 110, 15, seconds = pumpStartSeconds + 1_200), serial, zone)

        accounting.apply(event(48_219, 14, 10, 0, seconds = pumpStartSeconds + 18_000), serial, zone)

        assertEquals(pumpStart to 600_000L, saved[48_215L])
    }

    @Test
    fun `a record the user removed is treated as applied`() {
        accounting.apply(event(48_224, 9, 110, 15), serial, zone)
        invalid += 48_224L

        assertNull(accounting.apply(event(48_224, 10, 110, 4), serial, zone))
        assertEquals(pumpStart to 15 * 60_000L, saved[48_224L])
    }

    @Test
    fun `resume restores the exact stop window even after a status cut it short`() {
        accounting.apply(event(48_215, 14, 3, 0), serial, zone)
        saved[48_215L] = pumpStart to 1L

        assertNull(accounting.apply(event(48_219, 14, 10, 0, seconds = pumpStartSeconds + 600), serial, zone))

        assertEquals(pumpStart to 600_000L, saved[48_215L])
    }

    @Test
    fun `replaying stop and resume rows is idempotent after resume ended the stop`() {
        accounting.apply(event(48_215, 14, 3, 0), serial, zone)
        accounting.apply(event(48_219, 14, 10, 0, seconds = pumpStartSeconds + 60), serial, zone)
        assertEquals(pumpStart to 60_000L, saved[48_215L])

        assertNull(accounting.apply(event(48_215, 14, 3, 0), serial, zone))
        assertNull(accounting.apply(event(48_219, 14, 10, 0, seconds = pumpStartSeconds + 60), serial, zone))
        assertEquals(pumpStart to 60_000L, saved[48_215L])
    }

    @Test
    fun `a resume that did not end the recorded stop blocks the cursor`() {
        suspendOpen = true

        assertNotNull(accounting.apply(event(48_219, 14, 10, 0), serial, zone))
    }

    @Test
    fun `a record PumpSync did not save blocks the cursor`() {
        whenever(sync.syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())).thenReturn(false)

        assertNotNull(accounting.apply(event(48_224, 9, 110, 15), serial, zone))
    }

    @Test
    fun `pump stop and resume rows bound a zero basal window at pump time`() {
        assertNull(accounting.apply(event(48_215, 14, 3, 0), serial, zone))
        assertEquals(pumpStart to 24 * 60 * 60_000L, saved[48_215L])

        assertNull(accounting.apply(event(48_219, 14, 10, 0, seconds = pumpStartSeconds + 1_200), serial, zone))
        assertEquals(pumpStart to 1_200_000L, saved[48_215L])
    }

    @Test
    fun `re-applying the same rows writes nothing more`() {
        accounting.apply(event(48_224, 9, 110, 15), serial, zone)
        accounting.apply(event(48_224, 9, 110, 15), serial, zone)

        verify(sync).syncTemporaryBasalWithPumpId(pumpStart, 110.0, 15 * 60_000L, false, PumpSync.TemporaryBasalType.NORMAL, 48_224L, PumpType.YPSOPUMP, serial)
    }
}
