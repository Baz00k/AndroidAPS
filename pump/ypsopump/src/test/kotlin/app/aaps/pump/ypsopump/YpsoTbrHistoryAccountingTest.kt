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
import app.aaps.pump.ypsopump.tbr.YpsoTbrObservation
import app.aaps.pump.ypsopump.tbr.YpsoTbrRecordLookup
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
    private var status: YpsoTbrObservation? = null
    private var registeredAt = 0L
    private val statusSuspends = mutableListOf<YpsoTbrRecordLookup.StatusSuspend>()
    private val rowPercent = mutableMapOf<Long, Int>()
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
        override fun tbrRowsBetween(pumpSerial: String, percent: Int, after: Long, before: Long, runningAt: Long) =
            saved.filter { (id, record) -> id !in suspends && id in rowPercent.filterValues { it == percent }.keys && id > after && id < before && record.first + record.second > runningAt }.keys.toList()
    }, observation = { status }, registeredAt = { registeredAt })
    private var suspendOpen = false
    private val suspends = mutableSetOf<Long>()

    init {
        whenever(sync.syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())).thenAnswer {
            val pumpId = it.getArgument<Long>(5)
            if (pumpId in invalid) return@thenAnswer false
            saved[pumpId] = it.getArgument<Long>(0) to it.getArgument(2)
            rowPercent[pumpId] = it.getArgument<Double>(1).toInt()
            if (it.getArgument<PumpSync.TemporaryBasalType?>(4) == PumpSync.TemporaryBasalType.PUMP_SUSPEND) suspends += pumpId
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

    private fun runningStatus(percent: Int, remaining: Int, at: Long) {
        status = YpsoTbrObservation(true, percent, remaining, at)
    }

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
        runningStatus(120, 14, pumpStart + 60_000L)

        assertNull(accounting.apply(event(48_224, 9, 120, 15), serial, zone, readAt = pumpStart + 70_000L))

        assertEquals(pumpStart + 2_000L to 15 * 60_000L, saved[48_224L])
        verify(sync, never()).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())
        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `bound TBR cancelled on the pump shortens without moving its start`() {
        startedAttempt()
        runningStatus(120, 14, pumpStart + 60_000L)
        accounting.apply(event(48_224, 9, 120, 15), serial, zone, readAt = pumpStart + 70_000L)

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
    fun `a row whose pump start does not match the AAPS start is imported on its own`() {
        startedAttempt(at = pumpStart + 3 * 60_000L)

        accounting.apply(event(48_224, 10, 120, 3), serial, zone)

        assertEquals(pumpStart to 3 * 60_000L, saved[48_224L])
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `a row older than the attempt baseline is never bound to it`() {
        startedAttempt(baseline = 48_230L)
        runningStatus(120, 14, pumpStart + 60_000L)

        accounting.apply(event(48_224, 9, 120, 15), serial, zone, readAt = pumpStart + 70_000L)

        assertEquals(pumpStart to 15 * 60_000L, saved[48_224L])
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `two same-percent attempts bind to their own rows by start window`() {
        startedAttempt("a", percent = 0, minutes = 30, tempId = 1L)
        startedAttempt("b", percent = 0, minutes = 30, at = pumpStart + 4 * 60_000L, tempId = 2L)

        accounting.apply(event(48_224, 10, 0, 3), serial, zone)
        runningStatus(0, 29, pumpStart + 5 * 60_000L)
        accounting.apply(event(48_226, 9, 0, 30, seconds = pumpStartSeconds + 240), serial, zone, readAt = pumpStart + 5 * 60_000L)

        assertEquals(48_224L, journal.find("a")!!.pumpId)
        assertEquals(48_226L, journal.find("b")!!.pumpId)
    }

    @Test
    fun `a row that fits two AAPS starts binds the earlier one and the next row the later`() {
        startedAttempt("a", percent = 0, minutes = 30, tempId = 1L)
        startedAttempt("b", percent = 0, minutes = 30, at = pumpStart + 30_000L, tempId = 2L)

        assertNull(accounting.apply(event(48_224, 10, 0, 0), serial, zone))
        runningStatus(0, 29, pumpStart + 90_000L)
        assertNull(accounting.apply(event(48_226, 9, 0, 30, seconds = pumpStartSeconds + 30), serial, zone, readAt = pumpStart + 90_000L))

        assertEquals(48_224L, journal.find("a")!!.pumpId)
        assertEquals(48_226L, journal.find("b")!!.pumpId)
    }

    @Test
    fun `a running row status shows is an AAPS start binds to it whatever the pump clock says`() {
        startedAttempt(percent = 0, minutes = 120, at = pumpStart + 10 * 60_000L)
        runningStatus(0, 115, pumpStart + 15 * 60_000L)

        assertNull(accounting.apply(event(48_224, 9, 0, 120), serial, zone, readAt = pumpStart + 15 * 60_000L + 5_000L))

        assertEquals(pumpStart + 10 * 60_000L to 120 * 60_000L, saved[48_224L])
        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `a status-matched start binds even when its row falls in a DST overlap`() {
        val warsaw = ZoneId.of("Europe/Warsaw")
        val overlapSeconds = ChronoUnit.SECONDS.between(LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2026, 10, 25, 2, 30))
        startedAttempt()
        runningStatus(120, 14, pumpStart + 60_000L)

        assertNull(accounting.apply(event(48_224, 9, 120, 15, seconds = overlapSeconds), serial, warsaw, readAt = pumpStart + 70_000L))
        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `a journalled row identity from an earlier build still binds`() {
        startedAttempt(percent = 0, minutes = 120, at = pumpStart + 10 * 60_000L, row = 48_224L)

        assertNull(accounting.apply(event(48_224, 9, 0, 120), serial, zone))
        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `a running row read before its start took effect waits for a later scan`() {
        startedAttempt(percent = 0, minutes = 30, at = pumpStart + 10 * 60_000L)
        runningStatus(0, 30, pumpStart + 10 * 60_000L + 1_000L)

        assertNotNull(accounting.apply(event(48_224, 9, 0, 30), serial, zone, readAt = pumpStart + 9 * 60_000L))
        assertNull(saved[48_224L])
    }

    @Test
    fun `a running row with status showing another TBR is imported as the pump's`() {
        startedAttempt(percent = 0, minutes = 30, at = pumpStart + 10 * 60_000L)
        runningStatus(50, 60, pumpStart + 12 * 60_000L)

        assertNull(accounting.apply(event(48_224, 9, 0, 30), serial, zone, readAt = pumpStart + 12 * 60_000L + 1_000L))
        assertEquals(pumpStart to 30 * 60_000L, saved[48_224L])
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `a running row waits while its AAPS start was stopped after the read began`() {
        startedAttempt(percent = 0, minutes = 30, at = pumpStart + 10 * 60_000L)
        val stop = YpsoTbrAttempt(
            id = "stop", kind = YpsoTbrAttempt.Kind.STOP, pumpSerial = serial, percent = 100, durationMinutes = 0,
            type = "", temporaryId = 5L, baselinePumpId = null, createdAt = pumpStart + 12 * 60_000L,
        )
        journal.prepare(stop)
        journal.dispatched("stop", pumpStart + 12 * 60_000L)
        journal.stopEffective("stop", pumpStart + 12 * 60_000L)
        runningStatus(100, 0, pumpStart + 12 * 60_000L + 1_000L)

        assertNotNull(accounting.apply(event(48_224, 9, 0, 30), serial, zone, readAt = pumpStart + 11 * 60_000L))
        assertNull(saved[48_224L])
    }

    @Test
    fun `rows from before the pump was registered in AAPS are passed over`() {
        registeredAt = pumpStart + 60_000L

        assertNull(accounting.apply(event(48_221, 10, 300, 30), serial, zone))
        assertNull(accounting.apply(event(48_222, 14, 3, 0), serial, zone))
        assertEquals(emptyMap<Long, Pair<Long, Long>>(), saved)
    }

    @Test
    fun `a manual TBR set again after cancelling an unmatched AAPS start is never taken for it`() {
        // The AAPS start's own row ended with the pump clock far off, so it was imported on its own.
        startedAttempt(percent = 0, minutes = 30, at = pumpStart + 10 * 60_000L)
        assertNull(accounting.apply(event(48_224, 10, 0, 2), serial, zone))
        runningStatus(0, 30, pumpStart + 13 * 60_000L)

        assertNull(accounting.apply(event(48_226, 9, 0, 30, seconds = pumpStartSeconds + 120), serial, zone, readAt = pumpStart + 13 * 60_000L + 1_000L))

        assertEquals(pumpStart + 120_000L to 30 * 60_000L, saved[48_226L])
        assertNull(store.attempts.single().pumpId)
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
    fun `a running row waits without fresh status while an AAPS start could be it`() {
        startedAttempt(percent = 0, minutes = 120, at = pumpStart + 10 * 60_000L)

        assertNotNull(accounting.apply(event(48_224, 9, 0, 120), serial, zone, readAt = pumpStart + 15 * 60_000L))
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
