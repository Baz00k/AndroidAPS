package app.aaps.pump.ypsopump

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.ypsopump.history.YpsoEventIdentity
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoHistoryEvent
import app.aaps.pump.ypsopump.tbr.YpsoTbrAttempt
import app.aaps.pump.ypsopump.tbr.YpsoTbrAttemptStore
import app.aaps.pump.ypsopump.tbr.YpsoTbrHistoryAccounting
import app.aaps.pump.ypsopump.tbr.YpsoTbrJournal
import app.aaps.pump.ypsopump.tbr.YpsoTbrRecordLookup
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
    private val serial = "10123456"
    private val sync: PumpSync = mock()
    private val store = object : YpsoTbrAttemptStore {
        var attempts = emptyList<YpsoTbrAttempt>()
        override fun loadAll() = attempts
        override fun commitAll(attempts: List<YpsoTbrAttempt>) { this.attempts = attempts }
    }
    private val journal = YpsoTbrJournal(store)

    /** A fake database keyed by pump ID, with temp-ID binding, mirroring the PumpSync transactions. */
    private val saved = mutableMapOf<Long, Pair<Long, Long>>()
    /** AAPS starts' records not yet bound to a row, by temporary ID; [boundTemporaryIds] once bound, by pump ID. */
    private val provisional = mutableMapOf<Long, Provisional>()
    private val boundTemporaryIds = mutableMapOf<Long, Long>()
    private val invalid = mutableSetOf<Long>()
    private var registeredAt = 0L
    private val statusSuspends = mutableListOf<YpsoTbrRecordLookup.StatusSuspend>()
    private val accounting = YpsoTbrHistoryAccounting(sync, journal, object : YpsoTbrRecordLookup {
        override fun byPumpId(pumpId: Long, pumpSerial: String, start: Long) =
            saved[pumpId]?.let { YpsoTbrRecordLookup.Record(it.first, it.second, pumpId !in invalid, boundTemporaryIds[pumpId]) }
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
        override fun unboundStarts(pumpSerial: String, from: Long, to: Long) = provisional
            .filter { it.value.start in from..to }
            .map { YpsoTbrRecordLookup.UnboundStart(it.key, it.value.start, it.value.duration, it.value.percent.toDouble()) }
    }, registeredAt = { registeredAt })
    private var suspendOpen = false
    private val suspends = mutableSetOf<Long>()

    init {
        whenever(sync.syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())).thenAnswer {
            val pumpId = it.getArgument<Long>(5)
            if (pumpId in invalid) return@thenAnswer false
            val start = it.getArgument<Long>(0)
            // As the transaction does: a new record ends the one running when it starts, if that began earlier.
            if (pumpId !in saved) {
                saved.entries.firstOrNull { (_, r) -> r.first < start && r.first + r.second > start }?.let { e -> e.setValue(e.value.first to start - e.value.first) }
                    ?: provisional.entries.firstOrNull { (_, r) -> r.start < start && r.start + r.duration > start }
                        ?.let { e -> e.setValue(e.value.copy(duration = start - e.value.start)) }
            }
            saved[pumpId] = start to it.getArgument(2)
            if (it.getArgument<PumpSync.TemporaryBasalType?>(4) == PumpSync.TemporaryBasalType.PUMP_SUSPEND) suspends += pumpId
            true
        }
        whenever(sync.invalidateTemporaryBasalWithPumpId(any(), any(), any())).thenAnswer {
            invalid += it.getArgument<Long>(0)
            true
        }
        whenever(sync.syncTemporaryBasalWithTempId(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())).thenAnswer {
            val tempId = it.getArgument<Long>(4)
            val pumpId = it.getArgument<Long>(6)
            val unbound = provisional.remove(tempId)
            if (unbound == null && boundTemporaryIds[pumpId] != tempId) return@thenAnswer false
            if (unbound?.valid == false) invalid += pumpId
            saved[pumpId] = it.getArgument<Long>(0) to it.getArgument(2)
            boundTemporaryIds[pumpId] = tempId
            true
        }
    }

    private data class Provisional(val start: Long, val duration: Long, val percent: Int, val valid: Boolean = true)

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
            provisional[tempId] = Provisional(at, minutes * 60_000L, percent)
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
    fun `when no clock reading comes the AAPS record stands in for the row and history moves on`() {
        startedAttempt(at = pumpStart + 3 * 60_000L)

        assertNull(accounting.apply(event(48_224, 9, 120, 15), serial, zone))
        assertNull(accounting.apply(event(48_224, 10, 120, 3), serial, zone, pumpClockOffsetMs = 0L))

        assertNull(saved[48_224L])
        assertEquals(48_224L, store.attempts.single().unmatchedRowPumpId)
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `an unmatched start no longer holds later rows with its percent`() {
        startedAttempt(at = pumpStart + 3 * 60_000L)
        accounting.apply(event(48_224, 9, 120, 15), serial, zone)

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
    fun `a row that fits two AAPS starts binds the earlier one and the next row the later`() {
        startedAttempt("a", percent = 0, minutes = 30, tempId = 1L)
        startedAttempt("b", percent = 0, minutes = 30, at = pumpStart + 30_000L, tempId = 2L)

        assertNull(accounting.apply(event(48_224, 10, 0, 0), serial, zone))
        assertNull(accounting.apply(event(48_226, 9, 0, 30, seconds = pumpStartSeconds + 30), serial, zone))

        assertEquals(48_224L, journal.find("a")!!.pumpId)
        assertEquals(48_226L, journal.find("b")!!.pumpId)
    }

    /** Starts made while history is stalled, each awaiting its row, an hour apart from [pumpStart] on. */
    private fun stalledStarts(count: Int) = repeat(count) {
        startedAttempt("stalled$it", percent = 150, minutes = 30, at = pumpStart + (it + 1) * 3_600_000L, tempId = 1_000L + it)
    }

    @Test
    fun `a long history stall keeps the journal bounded and never evicts a start whose outcome is open`() {
        val pending = YpsoTbrAttempt(
            id = "pending", kind = YpsoTbrAttempt.Kind.START, pumpSerial = serial, percent = 0, durationMinutes = 30,
            type = "NORMAL", temporaryId = 7L, baselinePumpId = 48_222L, createdAt = pumpStart,
        )
        journal.prepare(pending)
        journal.dispatched("pending", pumpStart + 500L)
        startedAttempt("unmatched", percent = 0, minutes = 30, at = pumpStart + 60 * 60_000L, tempId = 8L)
        journal.unmatched("unmatched", 48_200L)

        stalledStarts(100)

        assertEquals(64, store.attempts.size)
        assertNotNull(journal.find("pending"))
        assertNotNull(journal.find("unmatched"))
        assertNull(journal.find("stalled0"))
        assertNotNull(journal.find("stalled99"))
    }

    @Test
    fun `a start the journal evicted during a history stall binds its row by its record`() {
        startedAttempt("first", percent = 0, minutes = 30, tempId = 1L)
        stalledStarts(64)
        assertNull(journal.find("first"))

        assertNull(accounting.apply(event(48_224, 10, 0, 4), serial, zone, pumpClockOffsetMs = 0L))

        assertEquals(pumpStart + 2_000L to 4 * 60_000L, saved[48_224L])
        assertTrue(provisional.keys.none { it == 1L })
        verify(sync, never()).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())
    }

    @Test
    fun `a start sent at the end of its send window with its ACK lost still binds its row with a measured clock`() {
        // Recorded at dispatch; the first frame left 29 s later and the write took the transport's full 8 s.
        provisional[1L] = Provisional(pumpStart - 37_000L, 30 * 60_000L, 0)

        assertNull(accounting.apply(event(48_224, 10, 0, 4), serial, zone, pumpClockOffsetMs = 0L))

        assertEquals(pumpStart - 37_000L to 4 * 60_000L, saved[48_224L])
    }

    @Test
    fun `an evicted start's row never lengthens the end AAPS recorded`() {
        // AAPS stopped it 3.5 minutes in; the pump counts the minute it began a little earlier.
        provisional[1L] = Provisional(pumpStart + 2_000L, 210_000L, 0)

        assertNull(accounting.apply(event(48_224, 10, 0, 4), serial, zone, pumpClockOffsetMs = 0L))

        assertEquals(pumpStart + 2_000L to 210_000L, saved[48_224L])
    }

    @Test
    fun `a row bound to its start's record before the journal recorded it binds that start when applied again`() {
        startedAttempt()
        // The app stopped after PumpSync bound the record and before the journal was written.
        provisional.remove(99L)
        saved[48_224L] = pumpStart + 2_000L to 15 * 60_000L
        boundTemporaryIds[48_224L] = 99L

        assertNull(accounting.apply(event(48_224, 9, 120, 15), serial, zone))

        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `without a measured clock an evicted start's row binds within the drift profile reads allow`() {
        provisional[1L] = Provisional(pumpStart + 100_000L, 30 * 60_000L, 0)

        assertNull(accounting.apply(event(48_224, 10, 0, 4), serial, zone))

        assertEquals(pumpStart + 100_000L to 4 * 60_000L, saved[48_224L])
    }

    @Test
    fun `a running row is not taken for an evicted start that asked for a longer TBR`() {
        provisional[1L] = Provisional(pumpStart + 2_000L, 60 * 60_000L, 0)

        assertNull(accounting.apply(event(48_224, 9, 0, 30), serial, zone, pumpClockOffsetMs = 0L))

        assertEquals(pumpStart to 30 * 60_000L, saved[48_224L])
        assertEquals(setOf(1L), provisional.keys)
    }

    @Test
    fun `an evicted start's row waits while a start with its percent has no proven outcome`() {
        provisional[1L] = Provisional(pumpStart + 2_000L, 30 * 60_000L, 0)
        val pending = YpsoTbrAttempt(
            id = "pending", kind = YpsoTbrAttempt.Kind.START, pumpSerial = serial, percent = 0, durationMinutes = 30,
            type = "NORMAL", temporaryId = 7L, baselinePumpId = 48_222L, createdAt = pumpStart + 600_000L,
        )
        journal.prepare(pending)
        journal.dispatched("pending", pumpStart + 600_500L)

        assertNotNull(accounting.apply(event(48_224, 10, 0, 4), serial, zone, pumpClockOffsetMs = 0L))

        assertTrue(saved.isEmpty())
    }

    @Test
    fun `an evicted start whose record the user removed stays removed when its row binds`() {
        provisional[1L] = Provisional(pumpStart + 2_000L, 30 * 60_000L, 0, valid = false)

        assertNull(accounting.apply(event(48_224, 10, 0, 4), serial, zone, pumpClockOffsetMs = 0L))

        assertEquals(setOf(48_224L), invalid)
        verify(sync, never()).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())
    }

    @Test
    fun `an evicted start's record is not taken for a TBR of its percent set on the pump outside its window`() {
        startedAttempt("first", percent = 0, minutes = 30, tempId = 1L)
        stalledStarts(64)
        // Set on the pump three minutes earlier and cancelled after two.
        assertNull(accounting.apply(event(48_223, 10, 0, 2, seconds = pumpStartSeconds - 180), serial, zone, pumpClockOffsetMs = 0L))
        assertNull(accounting.apply(event(48_224, 10, 0, 4), serial, zone, pumpClockOffsetMs = 0L))

        assertEquals(pumpStart - 180_000L to 2 * 60_000L, saved[48_223L])
        assertEquals(pumpStart + 2_000L to 4 * 60_000L, saved[48_224L])
        assertEquals(mapOf(48_224L to 1L), boundTemporaryIds)
    }

    /** Every recorded TBR as a time span; these tests use one percent, so any overlap counts it twice. */
    private fun overlaps(): Boolean {
        val spans = (saved.values + provisional.values.map { it.start to it.duration }).sortedBy { it.first }
        return spans.zipWithNext().any { (a, b) -> a.first + a.second > b.first }
    }

    @Test
    fun `a TBR that ended before registration never takes the record of a start after it`() {
        // Set on the pump for its first minute, then the pump was registered again two minutes in.
        registeredAt = pumpStart + 120_000L
        val manual = event(48_223, 10, 0, 1)
        // The AAPS start right after registration, evicted: only its record is left.
        provisional[1L] = Provisional(pumpStart + 127_000L, 30 * 60_000L, 0)
        val own = event(48_224, 10, 0, 4, seconds = pumpStartSeconds + 125)
        val read = listOf(manual to null, own to null)

        assertNull(accounting.apply(manual, serial, zone, scanRows = read))
        assertNull(accounting.apply(own, serial, zone, scanRows = read))

        assertNull(saved[48_223L])
        assertEquals(pumpStart + 127_000L to 4 * 60_000L, saved[48_224L])
        assertEquals(mapOf(48_224L to 1L), boundTemporaryIds)
        assertTrue(!overlaps())
    }

    @Test
    fun `two rows of a read that could both be an evicted start's own block instead of counting it twice`() {
        // Set on the pump for a minute, then the AAPS start two minutes later; without a clock both fit.
        provisional[1L] = Provisional(pumpStart + 127_000L, 30 * 60_000L, 0)
        val manual = event(48_223, 10, 0, 1)
        val own = event(48_224, 10, 0, 4, seconds = pumpStartSeconds + 125)
        val read = listOf(manual to null, own to null)

        assertNotNull(accounting.apply(manual, serial, zone, scanRows = read))
        assertNotNull(accounting.apply(own, serial, zone, scanRows = read))

        assertTrue(saved.isEmpty())
        assertEquals(setOf(1L), provisional.keys)
    }

    @Test
    fun `with a measured clock the same rows resolve, each to its own record`() {
        provisional[1L] = Provisional(pumpStart + 127_000L, 30 * 60_000L, 0)
        val manual = event(48_223, 10, 0, 1)
        val own = event(48_224, 10, 0, 4, seconds = pumpStartSeconds + 125)
        val read = listOf(manual to 0L, own to 0L)

        assertNull(accounting.apply(manual, serial, zone, pumpClockOffsetMs = 0L, scanRows = read))
        assertNull(accounting.apply(own, serial, zone, pumpClockOffsetMs = 0L, scanRows = read))

        assertEquals(pumpStart to 60_000L, saved[48_223L])
        assertEquals(pumpStart + 127_000L to 4 * 60_000L, saved[48_224L])
        assertTrue(!overlaps())
    }

    @Test
    fun `a clockless read binds an evicted start and a journalled start when only one assignment fits`() {
        // The evicted start's record, and the next start, still journalled, two minutes later.
        provisional[1L] = Provisional(pumpStart + 2_000L, 30 * 60_000L, 0)
        startedAttempt("next", percent = 0, minutes = 30, at = pumpStart + 122_000L, tempId = 2L)
        val first = event(48_224, 10, 0, 2)
        val second = event(48_226, 9, 0, 30, seconds = pumpStartSeconds + 120)
        val read = listOf(first to null, second to null)

        // The second row fits both, the first only the record: the first is the record's.
        assertNull(accounting.apply(first, serial, zone, scanRows = read))
        assertNull(accounting.apply(second, serial, zone, scanRows = read))

        assertEquals(pumpStart + 2_000L to 2 * 60_000L, saved[48_224L])
        assertEquals(48_226L, journal.find("next")!!.pumpId)
        assertTrue(!overlaps())
    }

    @Test
    fun `two rows that each fit two evicted starts' records block, though their order would suggest a pairing`() {
        provisional[1L] = Provisional(pumpStart + 2_000L, 30 * 60_000L, 0)
        provisional[2L] = Provisional(pumpStart + 20_000L, 30 * 60_000L, 0)
        val first = event(48_224, 10, 0, 0)
        val second = event(48_226, 10, 0, 4, seconds = pumpStartSeconds + 18)
        val read = listOf(first to 0L, second to 0L)

        assertNotNull(accounting.apply(first, serial, zone, pumpClockOffsetMs = 0L, scanRows = read))

        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a start whose final fragment reached the pump late, with its ACK lost, binds its row after eviction`() {
        // Recorded at dispatch; the pump started it a minute later, beyond the window of a measured clock.
        provisional[1L] = Provisional(pumpStart - 60_000L, 30 * 60_000L, 0)
        val row = event(48_224, 10, 0, 4)

        assertNull(accounting.apply(row, serial, zone, pumpClockOffsetMs = 0L, scanRows = listOf(row to 0L)))

        assertEquals(pumpStart - 60_000L to 4 * 60_000L, saved[48_224L])
        assertEquals(mapOf(48_224L to 1L), boundTemporaryIds)
        assertTrue(!overlaps())
    }

    @Test
    fun `a row bound to a start that took effect late is not imported again over the end AAPS recorded`() {
        // AAPS stopped it 3.5 minutes after it was recorded; the pump counts four minutes.
        provisional[1L] = Provisional(pumpStart - 60_000L, 210_000L, 0)
        val row = event(48_224, 10, 0, 4)

        assertNull(accounting.apply(row, serial, zone, pumpClockOffsetMs = 0L, scanRows = listOf(row to 0L)))

        assertEquals(210_000L, saved[48_224L]!!.second)
        assertEquals(mapOf(48_224L to 1L), boundTemporaryIds)
        verify(sync, never()).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())
    }

    @Test
    fun `two rows that could each be a start that took effect late block`() {
        provisional[1L] = Provisional(pumpStart - 60_000L, 30 * 60_000L, 0)
        val first = event(48_224, 10, 0, 4)
        val second = event(48_226, 10, 0, 5, seconds = pumpStartSeconds + 300)
        val read = listOf(first to 0L, second to 0L)

        assertNotNull(accounting.apply(first, serial, zone, pumpClockOffsetMs = 0L, scanRows = read))

        assertTrue(saved.isEmpty())
        assertEquals(setOf(1L), provisional.keys)
    }

    @Test
    fun `a TBR set on the pump while an evicted start ran is imported once that start's own row took its record`() {
        provisional[1L] = Provisional(pumpStart + 2_000L, 30 * 60_000L, 0)
        val own = event(48_224, 10, 0, 5)
        // Cancelled on the pump after five minutes and set again by hand at the same percent.
        val manual = event(48_226, 10, 0, 10, seconds = pumpStartSeconds + 360)
        val read = listOf(own to 0L, manual to 0L)

        assertNull(accounting.apply(own, serial, zone, pumpClockOffsetMs = 0L, scanRows = read))
        assertNull(accounting.apply(manual, serial, zone, pumpClockOffsetMs = 0L, scanRows = read))

        assertEquals(pumpStart + 2_000L to 5 * 60_000L, saved[48_224L])
        assertEquals(pumpStart + 360_000L to 10 * 60_000L, saved[48_226L])
        assertTrue(!overlaps())
    }

    @Test
    fun `a row that two evicted starts' records could be stays blocked`() {
        provisional[1L] = Provisional(pumpStart + 2_000L, 30 * 60_000L, 0)
        provisional[2L] = Provisional(pumpStart + 40_000L, 30 * 60_000L, 0)

        assertNotNull(accounting.apply(event(48_224, 10, 0, 4), serial, zone, pumpClockOffsetMs = 0L))

        assertTrue(saved.isEmpty())
        assertEquals(setOf(1L, 2L), provisional.keys)
    }

    @Test
    fun `a row that a journalled start and an evicted start's record could both be stays blocked`() {
        startedAttempt("journalled", percent = 0, minutes = 30, at = pumpStart + 10_000L, tempId = 2L)
        provisional[1L] = Provisional(pumpStart + 2_000L, 30 * 60_000L, 0)

        assertNotNull(accounting.apply(event(48_224, 10, 0, 4), serial, zone, pumpClockOffsetMs = 0L))

        assertTrue(saved.isEmpty())
        assertNull(journal.find("journalled")!!.pumpId)
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

    /** 02:04:44 on 2026-10-25 in Warsaw: 00:04:44Z before the fall-back, 01:04:44Z after it. */
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val repeatedHourSeconds = ChronoUnit.SECONDS.between(LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2026, 10, 25, 2, 4, 44))
    private val beforeFallBack = java.time.Instant.parse("2026-10-25T00:04:44Z").toEpochMilli()
    private val afterFallBack = java.time.Instant.parse("2026-10-25T01:04:44Z").toEpochMilli()

    @Test
    fun `a row in the repeated hour binds the AAPS start whose window holds one of its instants`() {
        for (at in listOf(beforeFallBack, afterFallBack)) {
            store.attempts = emptyList()
            saved.clear()
            startedAttempt(percent = 0, minutes = 60, at = at + 1_000L)

            assertNull(accounting.apply(event(55_473, 9, 0, 60, seconds = repeatedHourSeconds), serial, warsaw))

            assertEquals(at + 1_000L to 60 * 60_000L, saved[55_473L])
            assertEquals(55_473L, store.attempts.single().pumpId)
        }
    }

    @Test
    fun `a row in the repeated hour that no AAPS start explains stays blocked`() {
        assertNotNull(accounting.apply(event(55_473, 9, 0, 60, seconds = repeatedHourSeconds), serial, warsaw))
        assertNotNull(accounting.apply(event(55_474, 14, 3, 0, seconds = repeatedHourSeconds), serial, warsaw))

        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a row in the repeated hour that two AAPS starts could explain stays blocked`() {
        startedAttempt(id = "first", percent = 0, minutes = 60, at = beforeFallBack + 1_000L, tempId = 1L)
        startedAttempt(id = "second", percent = 0, minutes = 60, at = afterFallBack + 1_000L, tempId = 2L)

        assertNotNull(accounting.apply(event(55_473, 9, 0, 60, seconds = repeatedHourSeconds), serial, warsaw))

        assertTrue(saved.isEmpty())
        assertTrue(store.attempts.all { it.pumpId == null })
    }

    @Test
    fun `a row in the repeated hour is not bound when another row of the read could be the same start`() {
        // Only the start after the fall-back is still journalled. The row before it, from a start the
        // journal evicted or a TBR set on the pump, carries the same wall time and percent.
        startedAttempt(percent = 200, minutes = 30, at = afterFallBack + 1_000L)
        val first = event(55_473, 10, 200, 4, seconds = repeatedHourSeconds)
        val second = event(55_480, 10, 200, 15, seconds = repeatedHourSeconds + 2)

        val read = listOf(first to null, second to null)
        for (row in listOf(first, second)) {
            assertNotNull(accounting.apply(row, serial, warsaw, scanRows = read))
        }

        assertTrue(saved.isEmpty())
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `rows the start's history baseline or measured clock excludes are no rival`() {
        // A TBR set on the pump a minute before the first start, already behind its history baseline and
        // read without a usable clock, so only the baseline tells it apart.
        val manual = event(55_470, 10, 200, 1, seconds = repeatedHourSeconds - 60)
        startedAttempt(id = "first", percent = 200, minutes = 30, at = beforeFallBack + 1_000L, baseline = 55_470L, tempId = 1L)
        // A second start 61 minutes later, apart from the first by more than the measured skew.
        startedAttempt(id = "second", percent = 200, minutes = 30, at = afterFallBack + 61_000L, baseline = 55_470L, tempId = 2L)
        val first = event(55_473, 9, 200, 30, seconds = repeatedHourSeconds)
        val second = event(55_480, 9, 200, 30, seconds = repeatedHourSeconds + 60)
        val read = listOf(manual to null, first to 0L, second to 0L)

        assertNull(accounting.apply(first, serial, warsaw, pumpClockOffsetMs = 0L, scanRows = read))
        assertNull(accounting.apply(second, serial, warsaw, pumpClockOffsetMs = 0L, scanRows = read))

        assertEquals(mapOf("first" to 55_473L, "second" to 55_480L), store.attempts.associate { it.id to it.pumpId })
    }

    @Test
    fun `a row in the repeated hour binds the record of a start the journal evicted, also when read again`() {
        // Only the record is left of the start before the fall-back; nothing is journalled.
        provisional[1L] = Provisional(beforeFallBack + 1_000L, 60 * 60_000L, 0)
        val row = event(55_473, 10, 0, 4, seconds = repeatedHourSeconds)
        val read = listOf(row to 0L)

        assertNull(accounting.apply(row, serial, warsaw, pumpClockOffsetMs = 0L, scanRows = read))
        // A later row blocked the read, so this one is applied again.
        assertNull(accounting.apply(row, serial, warsaw, pumpClockOffsetMs = 0L, scanRows = read))

        assertEquals(beforeFallBack + 1_000L to 4 * 60_000L, saved[55_473L])
        assertEquals(mapOf(55_473L to 1L), boundTemporaryIds)
    }

    @Test
    fun `a row in the repeated hour is not bound to an evicted start's record another row of the read could be`() {
        provisional[1L] = Provisional(afterFallBack + 1_000L, 30 * 60_000L, 200)
        val first = event(55_473, 10, 200, 4, seconds = repeatedHourSeconds)
        val second = event(55_480, 10, 200, 15, seconds = repeatedHourSeconds + 2)
        val read = listOf(first to null, second to null)

        for (row in listOf(first, second)) assertNotNull(accounting.apply(row, serial, warsaw, scanRows = read))

        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a row in the repeated hour a record already carries is no rival`() {
        // The first row was bound to its start's record earlier in this read.
        saved[55_473L] = afterFallBack + 1_000L to 30 * 60_000L
        boundTemporaryIds[55_473L] = 1L
        provisional[2L] = Provisional(afterFallBack + 31_000L, 30 * 60_000L, 200)
        val first = event(55_473, 10, 200, 0, seconds = repeatedHourSeconds)
        val second = event(55_480, 10, 200, 15, seconds = repeatedHourSeconds + 30)
        val read = listOf(first to 0L, second to 0L)

        assertNull(accounting.apply(second, serial, warsaw, pumpClockOffsetMs = 0L, scanRows = read))

        assertEquals(afterFallBack + 31_000L to 15 * 60_000L, saved[55_480L])
    }

    @Test
    fun `a row in the repeated hour waits while a start with its percent has no proven outcome`() {
        startedAttempt(percent = 0, minutes = 60, at = beforeFallBack + 1_000L)
        val pending = YpsoTbrAttempt(
            id = "pending", kind = YpsoTbrAttempt.Kind.START, pumpSerial = serial, percent = 0, durationMinutes = 60,
            type = "NORMAL", temporaryId = 7L, baselinePumpId = 48_222L, createdAt = afterFallBack,
        )
        journal.prepare(pending)
        journal.dispatched("pending", afterFallBack + 500L)

        assertNotNull(accounting.apply(event(55_473, 9, 0, 60, seconds = repeatedHourSeconds), serial, warsaw))

        assertTrue(saved.isEmpty())
    }

    @Test
    fun `re-applying the same rows writes nothing more`() {
        accounting.apply(event(48_224, 9, 110, 15), serial, zone)
        accounting.apply(event(48_224, 9, 110, 15), serial, zone)

        verify(sync).syncTemporaryBasalWithPumpId(pumpStart, 110.0, 15 * 60_000L, false, PumpSync.TemporaryBasalType.NORMAL, 48_224L, PumpType.YPSOPUMP, serial)
    }
}
