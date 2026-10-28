package app.aaps.pump.ypsopump

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.ypsopump.history.YpsoEventIdentity
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoHistoryEvent
import app.aaps.pump.ypsopump.history.YpsoHistorySnapshot
import app.aaps.pump.ypsopump.history.YpsoPumpLocalTime
import app.aaps.pump.ypsopump.tbr.YpsoTbrAttempt
import app.aaps.pump.ypsopump.tbr.YpsoTbrAttemptStore
import app.aaps.pump.ypsopump.tbr.YpsoTbrHistoryAccounting
import app.aaps.pump.ypsopump.tbr.YpsoTbrJournal
import app.aaps.pump.ypsopump.tbr.YpsoTbrRecordLookup
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class YpsoTbrHistoryAccountingTest {
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
    /** Percent of each record in [saved]. */
    private val percents = mutableMapOf<Long, Int>()
    /** Rows PumpSync does not save. */
    private val failing = mutableSetOf<Long>()
    /** Runs once right after the next record lookup returns, as another thread would. */
    private var afterLookup: (() -> Unit)? = null
    /** PumpSync binds no record by temporary ID. */
    private var tempIdSyncFails = false
    private var registeredAt = 0L
    private val statusSuspends = mutableListOf<YpsoTbrRecordLookup.StatusSuspend>()
    /** Shared with status shortening, as the plugin does. */
    private val recordLock = Any()
    private val accounting = YpsoTbrHistoryAccounting(sync, journal, recordLock = recordLock, lookup = object : YpsoTbrRecordLookup {
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
        override fun provisionalStarts(pumpSerial: String, from: Long) = provisional
            .filter { it.value.valid && it.value.start >= from }
            .map { YpsoTbrRecordLookup.Provisional(it.key, it.value.start, it.value.duration, it.value.percent) }
            .also { afterLookup?.let { work -> afterLookup = null; work() } }
    }, registeredAt = { registeredAt })
    private var suspendOpen = false
    private val suspends = mutableSetOf<Long>()

    init {
        whenever(sync.syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())).thenAnswer {
            val pumpId = it.getArgument<Long>(5)
            if (pumpId in invalid || pumpId in failing) return@thenAnswer false
            val start = it.getArgument<Long>(0)
            // As the transaction does: a new record ends the valid one running when it starts, if that
            // began earlier.
            if (pumpId !in saved) {
                val savedRunning = saved.entries.filter { (id, r) -> id !in invalid && r.first <= start && r.first + r.second > start }.maxByOrNull { it.value.first }
                val provisionalRunning = provisional.entries.filter { (_, r) -> r.valid && r.start <= start && r.start + r.duration > start }.maxByOrNull { it.value.start }
                val savedStart = savedRunning?.value?.first ?: Long.MIN_VALUE
                val provisionalStart = provisionalRunning?.value?.start ?: Long.MIN_VALUE
                if (provisionalStart > savedStart && provisionalStart < start) {
                    provisionalRunning!!.setValue(provisionalRunning.value.copy(duration = start - provisionalStart))
                } else if (savedStart >= provisionalStart && savedRunning != null && savedStart < start) {
                    savedRunning.setValue(savedStart to start - savedStart)
                }
            }
            saved[pumpId] = start to it.getArgument(2)
            percents[pumpId] = it.getArgument<Double>(1).toInt()
            if (it.getArgument<PumpSync.TemporaryBasalType?>(4) == PumpSync.TemporaryBasalType.PUMP_SUSPEND) suspends += pumpId
            true
        }
        whenever(sync.invalidateTemporaryBasalWithPumpId(any(), any(), any())).thenAnswer {
            invalid += it.getArgument<Long>(0)
            true
        }
        whenever(sync.invalidateTemporaryBasalWithTempId(any())).thenAnswer {
            val tempId = it.getArgument<Long>(0)
            val record = provisional[tempId] ?: return@thenAnswer false
            provisional[tempId] = record.copy(valid = false)
            record.valid
        }
        whenever(sync.syncTemporaryBasalWithTempId(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())).thenAnswer {
            val tempId = it.getArgument<Long>(4)
            val pumpId = it.getArgument<Long>(6)
            if (tempIdSyncFails) return@thenAnswer false
            val unbound = provisional.remove(tempId)
            if (unbound == null && boundTemporaryIds[pumpId] != tempId) return@thenAnswer false
            if (unbound?.valid == false) invalid += pumpId
            saved[pumpId] = it.getArgument<Long>(0) to it.getArgument(2)
            percents[pumpId] = it.getArgument<Double>(1).toInt()
            boundTemporaryIds[pumpId] = tempId
            true
        }
    }

    private data class Provisional(val start: Long, val duration: Long, val percent: Int, val valid: Boolean = true)

    private val pumpStartSeconds = 843_494_510L
    private val pumpStart = LocalDateTime.of(2000, 1, 1, 0, 0).plus(pumpStartSeconds, ChronoUnit.SECONDS)
        .toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun event(sequence: Long, type: Int, v1: Int, v2: Int, seconds: Long = pumpStartSeconds) = YpsoHistoryEvent(
        YpsoEventIdentity(serial, 0, sequence),
        YpsoHistoryEntry(seconds, type, v1, v2, 0, sequence, 0),
    )

    /** Applies [event] placed by a pump clock [offset] ahead of the phone. */
    private fun apply(event: YpsoHistoryEvent, offset: Long = 0L) =
        accounting.apply(event, serial, LocalDateTime.of(2000, 1, 1, 0, 0).plusSeconds(event.entry.factorySeconds).toInstant(ZoneOffset.UTC).toEpochMilli() - offset)

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
        assertNull(apply(event(48_224, 9, 110, 15)))

        assertEquals(pumpStart to 15 * 60_000L, saved[48_224L])
    }

    @Test
    fun `manual TBR cancelled on the pump shortens to its elapsed minutes`() {
        apply(event(48_221, 9, 80, 15))
        apply(event(48_221, 10, 80, 8))

        assertEquals(pumpStart to 8 * 60_000L, saved[48_221L])
    }

    @Test
    fun `AAPS started TBR binds its row to the provisional record at the AAPS start time`() {
        startedAttempt()

        assertNull(apply(event(48_224, 9, 120, 15)))

        assertEquals(pumpStart + 2_000L to 15 * 60_000L, saved[48_224L])
        verify(sync, never()).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())
        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `bound TBR cancelled on the pump shortens without moving its start`() {
        startedAttempt()
        apply(event(48_224, 9, 120, 15))

        apply(event(48_224, 10, 120, 6))

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

        apply(event(48_224, 10, 120, 2))

        assertEquals(pumpStart + 2_000L to 90_000L, saved[48_224L])
    }

    @Test
    fun `an unmatched start no longer takes rows with its percent`() {
        startedAttempt(at = pumpStart + 3 * 60_000L)
        journal.unmatched("a", 48_224L)

        assertNull(apply(event(48_230, 9, 120, 15, seconds = pumpStartSeconds + 3 * 60 + 2)))
        assertEquals(pumpStart + 3 * 60_000L + 2_000L to 15 * 60_000L, saved[48_230L])
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `a replayed pump row keeps the start first written despite a slightly different offset`() {
        assertNull(apply(event(48_224, 9, 110, 15), 1_000L))
        assertNull(apply(event(48_224, 10, 110, 4), 2_000L))

        assertEquals(pumpStart - 1_000L to 4 * 60_000L, saved[48_224L])
    }

    @Test
    fun `a row whose pump start does not match the AAPS start is imported on its own`() {
        startedAttempt(at = pumpStart + 3 * 60_000L)

        apply(event(48_224, 10, 120, 3), 0L)

        assertEquals(pumpStart to 3 * 60_000L, saved[48_224L])
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `a row older than the attempt baseline is never bound to it`() {
        startedAttempt(baseline = 48_230L)

        apply(event(48_224, 9, 120, 15))

        assertEquals(pumpStart to 15 * 60_000L, saved[48_224L])
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `two same-percent attempts bind to their own rows by start window`() {
        startedAttempt("a", percent = 0, minutes = 30, tempId = 1L)
        startedAttempt("b", percent = 0, minutes = 30, at = pumpStart + 4 * 60_000L, tempId = 2L)

        apply(event(48_224, 10, 0, 3))
        apply(event(48_226, 9, 0, 30, seconds = pumpStartSeconds + 240))

        assertEquals(48_224L, journal.find("a")!!.pumpId)
        assertEquals(48_226L, journal.find("b")!!.pumpId)
    }

    @Test
    fun `a row that fits two AAPS starts binds the earlier one and the next row the later`() {
        startedAttempt("a", percent = 0, minutes = 30, tempId = 1L)
        startedAttempt("b", percent = 0, minutes = 30, at = pumpStart + 30_000L, tempId = 2L)

        assertNull(apply(event(48_224, 10, 0, 0)))
        assertNull(apply(event(48_226, 9, 0, 30, seconds = pumpStartSeconds + 30)))

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
    fun `a row bound to its start's record before the journal recorded it binds that start when applied again`() {
        startedAttempt()
        // The app stopped after PumpSync bound the record and before the journal was written.
        provisional.remove(99L)
        saved[48_224L] = pumpStart + 2_000L to 15 * 60_000L
        boundTemporaryIds[48_224L] = 99L

        assertNull(apply(event(48_224, 9, 120, 15)))

        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `a journalled row identity from an earlier build still binds`() {
        startedAttempt(percent = 0, minutes = 120, at = pumpStart + 10 * 60_000L, row = 48_224L)

        assertNull(apply(event(48_224, 9, 0, 120)))
        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `with a measured pump clock a far-off row time still binds its AAPS start`() {
        // The pump clock runs ten minutes behind the phone.
        val offset = -10 * 60_000L
        startedAttempt(percent = 0, minutes = 120)

        assertNull(apply(event(48_224, 9, 0, 120, seconds = pumpStartSeconds - 600), offset))

        assertEquals(pumpStart + 2_000L to 120 * 60_000L, saved[48_224L])
        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `with a measured pump clock a TBR set again on the pump is never taken for the AAPS start`() {
        val offset = -10 * 60_000L
        startedAttempt(percent = 0, minutes = 30)
        // Cancelled on the pump after 30 s and set again two minutes later, identical.
        assertNull(apply(event(48_224, 10, 0, 0, seconds = pumpStartSeconds - 600), offset))
        assertNull(apply(event(48_226, 9, 0, 30, seconds = pumpStartSeconds - 600 + 150), offset))

        assertEquals(48_224L, store.attempts.single().pumpId)
        assertEquals(pumpStart + 150_000L to 30 * 60_000L, saved[48_226L])
    }

    @Test
    fun `a TBR that ended before the pump was registered in AAPS is passed over`() {
        registeredAt = pumpStart + 31 * 60_000L

        assertNull(apply(event(48_221, 10, 300, 30)))
        assertEquals(emptyMap<Long, Pair<Long, Long>>(), saved)
    }

    @Test
    fun `a TBR running at registration counts from registration until it ends`() {
        registeredAt = pumpStart + 10 * 60_000L

        assertNull(apply(event(48_221, 9, 300, 30)))
        assertEquals(registeredAt to 20 * 60_000L, saved[48_221L])

        assertNull(apply(event(48_221, 10, 300, 25)))
        assertEquals(registeredAt to 15 * 60_000L, saved[48_221L])
    }

    @Test
    fun `a TBR recorded from its running row and cancelled before registration is ended there`() {
        registeredAt = pumpStart + 10 * 60_000L
        apply(event(48_221, 9, 300, 30))

        assertNull(apply(event(48_221, 10, 300, 5)))

        assertEquals(registeredAt to 1L, saved[48_221L])
    }

    @Test
    fun `a pump stop running at registration counts from registration until its resume`() {
        registeredAt = pumpStart + 10 * 60_000L

        assertNull(apply(event(48_222, 14, 3, 0)))
        assertEquals(registeredAt to 24 * 60 * 60_000L - 10 * 60_000L, saved[48_222L])
        assertNull(apply(event(48_223, 14, 10, 0, seconds = pumpStartSeconds + 1_800)))

        assertEquals(registeredAt to 20 * 60_000L, saved[48_222L])
    }

    @Test
    fun `a pump stop and resume both before registration leave nothing and never block`() {
        registeredAt = pumpStart + 60 * 60_000L

        repeat(2) {
            assertNull(apply(event(48_222, 14, 3, 0)))
            assertNull(apply(event(48_223, 14, 10, 0, seconds = pumpStartSeconds + 600)))
        }

        assertTrue(saved.keys.all { it in invalid })
    }

    @Test
    fun `replaying an earlier stop and resume never removes a later stop still running at registration`() {
        registeredAt = pumpStart + 60 * 60_000L
        val stopA = event(48_222, 14, 3, 0)
        val resumeA = event(48_223, 14, 10, 0, seconds = pumpStartSeconds + 600)
        val stopB = event(48_224, 14, 3, 0, seconds = pumpStartSeconds + 1_200)

        repeat(2) { listOf(stopA, resumeA, stopB).forEach { assertNull(apply(it)) } }

        assertTrue(48_222L in invalid)
        assertTrue(48_224L !in invalid)
        assertEquals(registeredAt, saved[48_224L]?.first)
    }

    @Test
    fun `a stop running at registration still lapses a day after the pump stopped`() {
        registeredAt = pumpStart + 23 * 60 * 60_000L

        assertNull(apply(event(48_222, 14, 3, 0)))

        assertEquals(registeredAt to 60 * 60_000L, saved[48_222L])
    }

    @Test
    fun `a pump stop a day before registration has lapsed and is passed over`() {
        registeredAt = pumpStart + 24 * 60 * 60_000L

        assertNull(apply(event(48_222, 14, 3, 0)))

        assertEquals(emptyMap<Long, Pair<Long, Long>>(), saved)
    }

    @Test
    fun `an AAPS start whose row reads just before registration still binds`() {
        registeredAt = pumpStart + 1_000L
        startedAttempt()

        assertNull(apply(event(48_224, 10, 120, 3)))
        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `a second stop is recorded even when an earlier status stop ended just before it`() {
        statusSuspends += YpsoTbrRecordLookup.StatusSuspend(77L, pumpStart - 40_000L, 20_000L)

        assertNull(apply(event(48_224, 14, 3, 0)))

        assertEquals(pumpStart to 24 * 60 * 60_000L, saved[48_224L])
    }

    @Test
    fun `a stop row status already recorded covers only the time before status saw it`() {
        statusSuspends += YpsoTbrRecordLookup.StatusSuspend(77L, pumpStart + 3 * 60_000L, 24 * 60 * 60_000L)

        assertNull(apply(event(48_224, 14, 3, 0)))

        assertEquals(pumpStart to 3 * 60_000L, saved[48_224L])
    }

    @Test
    fun `a pump row whose AAPS start is not yet resolved by status waits instead of importing`() {
        YpsoTbrAttempt(
            id = "p", kind = YpsoTbrAttempt.Kind.START, pumpSerial = serial, percent = 0, durationMinutes = 30,
            type = "NORMAL", temporaryId = 7L, baselinePumpId = 48_222L, createdAt = pumpStart,
        ).also { journal.prepare(it); journal.dispatched("p", pumpStart) }

        assertNotNull(apply(event(48_224, 9, 0, 30)))
        assertNull(saved[48_224L])
    }

    @Test
    fun `a manual row overlapping an AAPS start it is not is imported on its own`() {
        startedAttempt(percent = 0, minutes = 30, at = pumpStart + 10 * 60_000L, row = 48_226L)

        assertNull(apply(event(48_224, 10, 0, 10)))
        assertEquals(pumpStart to 10 * 60_000L, saved[48_224L])
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `a manual row with a measured clock is imported beside an AAPS start it does not fit`() {
        startedAttempt(percent = 0, minutes = 30, at = pumpStart + 10 * 60_000L)

        assertNull(apply(event(48_224, 10, 0, 10), 0L))
        assertEquals(pumpStart to 10 * 60_000L, saved[48_224L])
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `resume never extends an earlier stop that other records followed`() {
        apply(event(48_215, 14, 3, 0))
        saved[48_215L] = pumpStart to 600_000L
        apply(event(48_216, 9, 110, 15, seconds = pumpStartSeconds + 1_200))

        apply(event(48_219, 14, 10, 0, seconds = pumpStartSeconds + 18_000))

        assertEquals(pumpStart to 600_000L, saved[48_215L])
    }

    @Test
    fun `a record the user removed is treated as applied`() {
        apply(event(48_224, 9, 110, 15))
        invalid += 48_224L

        assertNull(apply(event(48_224, 10, 110, 4)))
        assertEquals(pumpStart to 15 * 60_000L, saved[48_224L])
    }

    @Test
    fun `resume restores the exact stop window even after a status cut it short`() {
        apply(event(48_215, 14, 3, 0))
        saved[48_215L] = pumpStart to 1L

        assertNull(apply(event(48_219, 14, 10, 0, seconds = pumpStartSeconds + 600)))

        assertEquals(pumpStart to 600_000L, saved[48_215L])
    }

    @Test
    fun `replaying stop and resume rows is idempotent after resume ended the stop`() {
        apply(event(48_215, 14, 3, 0))
        apply(event(48_219, 14, 10, 0, seconds = pumpStartSeconds + 60))
        assertEquals(pumpStart to 60_000L, saved[48_215L])

        assertNull(apply(event(48_215, 14, 3, 0)))
        assertNull(apply(event(48_219, 14, 10, 0, seconds = pumpStartSeconds + 60)))
        assertEquals(pumpStart to 60_000L, saved[48_215L])
    }

    @Test
    fun `a resume that did not end the recorded stop blocks the cursor`() {
        suspendOpen = true

        assertNotNull(apply(event(48_219, 14, 10, 0)))
    }

    @Test
    fun `a record PumpSync did not save blocks the cursor`() {
        whenever(sync.syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())).thenReturn(false)

        assertNotNull(apply(event(48_224, 9, 110, 15)))
    }

    @Test
    fun `pump stop and resume rows bound a zero basal window at pump time`() {
        assertNull(apply(event(48_215, 14, 3, 0)))
        assertEquals(pumpStart to 24 * 60 * 60_000L, saved[48_215L])

        assertNull(apply(event(48_219, 14, 10, 0, seconds = pumpStartSeconds + 1_200)))
        assertEquals(pumpStart to 1_200_000L, saved[48_215L])
    }

    @Test
    fun `after a manual clock edit a TBR set on the pump is placed by its own clock and never taken for the AAPS start`() {
        // The pump ran half an hour ahead. A 0 % TBR set on it reads 16:01:50; at 16:11:50 the clock is set
        // back to 15:41:50, and the AAPS start's row then reads 16:01:52. Read as wall times, both fit the start.
        startedAttempt(percent = 0, minutes = 30)
        val manual = event(48_224, 10, 0, 5)
        val change = YpsoHistoryEvent(YpsoEventIdentity(serial, 0, 48_225), YpsoHistoryEntry(pumpStartSeconds + 600, 13, 15, 41, 50, 48_225, 0))
        val aaps = event(48_226, 9, 0, 30, seconds = pumpStartSeconds + 2)
        val rows = listOf(aaps.entry, change.entry, manual.entry)
        val times = YpsoPumpLocalTime.rowTimes(YpsoHistorySnapshot(3, 3, 21, 21, rows.first(), rows.first(), rows, true, pumpClockOffsetMs = 0L))

        assertNull(accounting.apply(manual, serial, checkNotNull(times.at(manual.entry))))
        assertNull(accounting.apply(aaps, serial, checkNotNull(times.at(aaps.entry))))

        assertEquals(pumpStart - 30 * 60_000L to 5 * 60_000L, saved[48_224L])
        assertEquals(48_226L, store.attempts.single().pumpId)
    }

    @Test
    fun `re-applying the same rows writes nothing more`() {
        apply(event(48_224, 9, 110, 15))
        apply(event(48_224, 9, 110, 15))

        verify(sync).syncTemporaryBasalWithPumpId(pumpStart, 110.0, 15 * 60_000L, false, PumpSync.TemporaryBasalType.NORMAL, 48_224L, PumpType.YPSOPUMP, serial)
    }

    /**
     * One ingestion pass as [app.aaps.pump.ypsopump.history.YpsoHistoryIngestion] runs it, with a
     * measured clock: [frozen] taken before the read, then low records invalidated, the rows applied,
     * and the other records invalidated. With [steps], the pass stops, as the app would, after that many.
     */
    private fun pass(
        vararg rows: YpsoHistoryEvent,
        frozen: List<YpsoTbrRecordLookup.Provisional> = accounting.provisional(serial, pumpStart + 3 * 60 * 60_000L),
        steps: Int = Int.MAX_VALUE,
    ): String? {
        var left = steps
        fun step(work: () -> String?): String? = if (left-- <= 0) "stopped" else work()
        step { accounting.invalidateLowering(serial, frozen) }?.let { return it }
        rows.forEach { row -> step { apply(row) }?.let { return it } }
        return step { accounting.invalidateRaising(serial, frozen) }
    }

    /** Net basal change of every valid TBR record, in percent-milliseconds; IOB sums records, overlaps included. */
    private fun netBasal(): Long =
        saved.filterKeys { it !in invalid && it !in suspends }.entries.sumOf { (percents.getValue(it.key) - 100L) * it.value.second } +
            provisional.values.filter { it.valid }.sumOf { (it.percent - 100L) * it.duration }

    /** Every TBR that counts towards insulin on board, as start to duration. */
    private fun counted(): Set<Pair<Long, Long>> =
        (saved.filterKeys { it !in invalid }.values + provisional.values.filter { it.valid }.map { it.start to it.duration }).toSet()

    @Test
    fun `a failed AAPS start whose record has no row is invalidated once history was read after it was saved`() {
        // Recorded at dispatch after status took some TBR for it; the command never reached the pump.
        provisional[1L] = Provisional(pumpStart, 30 * 60_000L, 0)

        assertNull(pass())

        assertTrue(counted().isEmpty())
    }

    @Test
    fun `an AAPS start the journal evicted is imported once by its pump ID and its record invalidated`() {
        startedAttempt("first", percent = 0, minutes = 30, tempId = 1L)
        stalledStarts(64)
        assertNull(journal.find("first"))

        assertNull(pass(event(48_224, 10, 0, 4)))

        assertEquals(pumpStart to 4 * 60_000L, saved[48_224L])
        assertEquals(false, provisional.getValue(1L).valid)
        assertTrue(boundTemporaryIds.isEmpty())
        verify(sync).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), eq(48_224L), any(), any())
        // Replaying the read writes nothing more.
        assertNull(pass(event(48_224, 10, 0, 4)))
        verify(sync).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), eq(48_224L), any(), any())
    }

    @Test
    fun `a TBR set on the pump that status took for a failed AAPS start counts at its own duration`() {
        // 10:00 AAPS asked for 200 % for 15 minutes, which never arrived; 10:10 200 % for 60 minutes was
        // set on the pump, and status at 10:55 took it for the AAPS start.
        provisional[1L] = Provisional(pumpStart - 10 * 60_000L, 15 * 60_000L, 200)

        assertNull(pass(event(48_224, 10, 200, 60)))

        assertEquals(setOf(pumpStart to 60 * 60_000L), counted())
    }

    @Test
    fun `a TBR set on the pump just before an AAPS start that never arrived is counted alone`() {
        // 10:01 50 % for 120 minutes set on the pump, cancelled at 11:05; the AAPS start for 60 minutes
        // was dispatched at 10:02 and never arrived, and status at 11:05 took the pump's TBR for it.
        provisional[1L] = Provisional(pumpStart + 60_000L, 60 * 60_000L, 50)

        assertNull(pass(event(48_224, 10, 50, 64)))

        assertEquals(setOf(pumpStart to 64 * 60_000L), counted())
    }

    @Test
    fun `a failed AAPS start's record leaves nothing beside the TBRs the pump ran after it`() {
        // 10:00 the AAPS start of 50 % for 120 minutes never arrived. The pump ran 200 % from 10:30 to
        // 10:40, then 50 % from 11:00, which status took for the AAPS start.
        provisional[1L] = Provisional(pumpStart, 120 * 60_000L, 50)
        val high = event(48_224, 10, 200, 10, seconds = pumpStartSeconds + 30 * 60)
        val low = event(48_226, 9, 50, 120, seconds = pumpStartSeconds + 60 * 60)

        assertNull(pass(high, low))

        assertEquals(setOf(pumpStart + 30 * 60_000L to 10 * 60_000L, pumpStart + 60 * 60_000L to 120 * 60_000L), counted())
    }

    @Test
    fun `a record the user removed stays removed and every TBR of its percent counts`() {
        // The AAPS start of 200 % for 30 minutes at 10:00 never arrived; status took a TBR set on the
        // pump for it, and the user removed that record. The pump ran 200 % from 10:00:02 for 20
        // minutes and from 10:25 for 30 minutes.
        provisional[1L] = Provisional(pumpStart, 30 * 60_000L, 200, valid = false)
        val first = event(48_224, 10, 200, 20, seconds = pumpStartSeconds + 2)
        val second = event(48_226, 10, 200, 30, seconds = pumpStartSeconds + 25 * 60)

        assertNull(pass(first, second))

        assertEquals(setOf(pumpStart + 2_000L to 20 * 60_000L, pumpStart + 25 * 60_000L to 30 * 60_000L), counted())
        assertTrue(boundTemporaryIds.isEmpty())
    }

    @Test
    fun `a record whose row may still arrive or is not history's to import stays`() {
        // Journalled: its row binds it when it arrives.
        startedAttempt("live", percent = 0, minutes = 30, tempId = 1L)
        // From before the pump was registered, whose rows are never imported.
        registeredAt = pumpStart - 60 * 60_000L
        provisional[3L] = Provisional(pumpStart - 2 * 60 * 60_000L, 30 * 60_000L, 0)
        val frozen = accounting.provisional(serial, pumpStart + 3 * 60 * 60_000L)
        // Saved after the records were taken, while history was read: its row may not be in the read.
        provisional[2L] = Provisional(pumpStart + 60 * 60_000L, 30 * 60_000L, 0)
        val before = provisional.toMap()

        assertNull(pass(frozen = frozen))

        assertEquals(before, provisional)
    }

    /** Clears every record, for a fresh replay within one test. */
    private fun clearRecords() {
        saved.clear(); percents.clear(); provisional.clear(); boundTemporaryIds.clear(); invalid.clear(); failing.clear()
    }

    /** An evicted start's record at [percent] beside its own row, read with a later row PumpSync fails to save. */
    private fun evictedWithFailingRow(percent: Int): Array<YpsoHistoryEvent> {
        clearRecords()
        provisional[1L] = Provisional(pumpStart + 60_000L, 60 * 60_000L, percent)
        failing += 48_226L
        // The later row is a 100 % TBR: it changes no basal, so only the first two records count.
        return arrayOf(event(48_224, 10, percent, 60), event(48_226, 10, 100, 10, seconds = pumpStartSeconds + 70 * 60))
    }

    @Test
    fun `a pass that blocks after importing an evicted start's row never undercounts insulin`() {
        for (percent in listOf(0, 200)) {
            val rows = evictedWithFailingRow(percent)
            val truth = (percent - 100L) * 60 * 60_000L

            assertNotNull(pass(*rows))
            assertNotNull(pass(*rows))

            assertTrue(netBasal() >= truth, "$percent %: ${netBasal()} < $truth")
            assertEquals(pumpStart to 60 * 60_000L, saved[48_224L])
        }
    }

    @Test
    fun `a pass stopped after any step never undercounts insulin`() {
        for (percent in listOf(0, 200)) {
            for (steps in 0..3) {
                clearRecords()
                provisional[1L] = Provisional(pumpStart + 60_000L, 60 * 60_000L, percent)
                val rows = arrayOf(event(48_224, 10, percent, 60), event(48_226, 10, 100, 10, seconds = pumpStartSeconds + 70 * 60))
                val truth = (percent - 100L) * 60 * 60_000L

                pass(*rows, steps = steps)
                assertTrue(netBasal() >= truth, "$percent % stopped after $steps: ${netBasal()} < $truth")
                // The app starts again and reads the same rows.
                assertNull(pass(*rows))
                assertEquals(truth, netBasal())
            }
        }
    }

    @Test
    fun `an unmatched start an earlier build left unbound keeps its stand-in once a new start pushes it out`() {
        // An earlier build marked it unmatched without binding its record to the row.
        startedAttempt("old", percent = 200, minutes = 30, at = pumpStart + 3 * 60_000L, tempId = 1L)
        journal.unmatched("old", 48_224L)
        repeat(63) {
            startedAttempt("other$it", percent = 150, minutes = 30, at = pumpStart + (it + 1) * 3_600_000L, tempId = 1_000L + it)
            journal.unmatched("other$it", 50_000L + it)
        }
        // The repair at start-up fails and says so; the next history pass retries it before reading.
        tempIdSyncFails = true
        assertNotNull(accounting.keepStandIns())
        assertTrue(boundTemporaryIds.isEmpty())
        tempIdSyncFails = false
        accounting.provisional(serial, pumpStart + 3 * 60 * 60_000L)
        assertEquals(1L, boundTemporaryIds[48_224L])

        // A new start pushes the oldest unmatched one out of the journal.
        startedAttempt("next", percent = 150, minutes = 30, at = pumpStart + 100 * 3_600_000L, tempId = 2_000L)
        assertNull(journal.find("old"))
        assertNull(pass())

        assertEquals(pumpStart + 3 * 60_000L to 30 * 60_000L, saved[48_224L])
        assertTrue(48_224L !in invalid)
    }

    /** A start that took effect but whose record AAPS has not saved yet. */
    private fun unsavedStart(id: String, tempId: Long, at: Long) {
        journal.prepare(
            YpsoTbrAttempt(
                id = id, kind = YpsoTbrAttempt.Kind.START, pumpSerial = serial, percent = 200, durationMinutes = 30,
                type = "NORMAL", temporaryId = tempId, baselinePumpId = 48_222L, createdAt = at - 1_000L,
            ),
        )
        journal.dispatched(id, at - 500L)
        journal.effective(id, at, at)
    }

    /** The controller saves the record on a later try. */
    private fun saveRecord(id: String, tempId: Long, at: Long) {
        provisional[tempId] = Provisional(at, 30 * 60_000L, 200)
        journal.accounted(id)
    }

    @Test
    fun `an earlier build's unmatched start whose record is saved later is still bound before history reads`() {
        val at = pumpStart + 3 * 60_000L
        unsavedStart("old", 1L, at)
        // An earlier build marked it unmatched before its record was saved.
        journal.unmatched("old", 48_224L)
        assertNull(accounting.keepStandIns())

        saveRecord("old", 1L, at)
        accounting.provisional(serial, pumpStart + 3 * 60 * 60_000L)

        assertEquals(1L, boundTemporaryIds[48_224L])
    }

    @Test
    fun `a record saved while the stand-in repair runs is still bound before history reads`() {
        val at = pumpStart + 3 * 60_000L
        unsavedStart("old", 1L, at)
        journal.unmatched("old", 48_224L)
        // The controller saves the record right after the repair looked it up and found none.
        afterLookup = { saveRecord("old", 1L, at) }
        assertNull(accounting.keepStandIns())

        accounting.provisional(serial, pumpStart + 3 * 60 * 60_000L)

        assertEquals(1L, boundTemporaryIds[48_224L])
    }

    /**
     * Status shortening as the plugin does it, under the shared lock: it reads the record, runs
     * [afterRead], and writes the shorter duration back with the pump ID it read, as
     * SyncTemporaryBasalWithTempIdTransaction does.
     */
    private fun statusShorten(tempId: Long, duration: Long, afterRead: () -> Unit = {}) = synchronized(recordLock) {
        val boundTo = boundTemporaryIds.entries.firstOrNull { it.value == tempId }?.key
        val start = boundTo?.let { saved.getValue(it).first } ?: provisional.getValue(tempId).start
        val percent = boundTo?.let { percents.getValue(it) } ?: provisional.getValue(tempId).percent
        afterRead()
        if (boundTo != null) saved[boundTo] = start to duration
        else {
            // Read unbound: the write clears any binding made since.
            boundTemporaryIds.filterValues { it == tempId }.keys.forEach { saved.remove(it); percents.remove(it); boundTemporaryIds.remove(it) }
            provisional[tempId] = Provisional(start, duration, percent)
        }
    }

    /** Runs [work] on another thread and returns once it is blocked on a lock or has finished. */
    private fun startAndSettle(work: () -> Unit): Thread = Thread(work).also { thread ->
        thread.start()
        while (thread.state != Thread.State.BLOCKED && thread.state != Thread.State.TERMINATED) Thread.yield()
    }

    @Test
    fun `status shortening waiting on the stand-in repair keeps the binding`() {
        startedAttempt("old", percent = 200, minutes = 30, at = pumpStart + 3 * 60_000L, tempId = 1L)
        journal.unmatched("old", 48_224L)
        var status: Thread? = null
        // Status reads the record right after the repair looked it up.
        afterLookup = { status = startAndSettle { statusShorten(1L, 10 * 60_000L) } }

        assertNull(accounting.keepStandIns())
        status!!.join()

        assertEquals(1L, boundTemporaryIds[48_224L])
        assertEquals(pumpStart + 3 * 60_000L to 10 * 60_000L, saved[48_224L])
    }

    @Test
    fun `the stand-in repair waiting on status shortening keeps the shorter end`() {
        startedAttempt("old", percent = 0, minutes = 30, at = pumpStart + 3 * 60_000L, tempId = 1L)
        journal.unmatched("old", 48_224L)
        var repair: Thread? = null

        statusShorten(1L, 10 * 60_000L) { repair = startAndSettle { accounting.keepStandIns() } }
        repair!!.join()

        assertEquals(1L, boundTemporaryIds[48_224L])
        assertEquals(pumpStart + 3 * 60_000L to 10 * 60_000L, saved[48_224L])
    }
}
