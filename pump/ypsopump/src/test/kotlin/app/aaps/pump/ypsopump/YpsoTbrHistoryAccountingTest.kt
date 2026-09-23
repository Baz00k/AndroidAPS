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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
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
    private val accounting = YpsoTbrHistoryAccounting(sync, journal)

    private val pumpStartSeconds = 843_494_510L
    private val pumpStart = LocalDateTime.of(2000, 1, 1, 0, 0).plus(pumpStartSeconds, ChronoUnit.SECONDS)
        .atZone(zone).toInstant().toEpochMilli()

    private fun event(sequence: Long, type: Int, v1: Int, v2: Int) = YpsoHistoryEvent(
        YpsoEventIdentity(serial, 0, sequence),
        YpsoHistoryEntry(pumpStartSeconds, type, v1, v2, 0, sequence, 0),
    )

    private fun startedAttempt(percent: Int = 120, minutes: Int = 15, baseline: Long = 48_222L) = YpsoTbrAttempt(
        id = "a", pumpSerial = serial, percent = percent, durationMinutes = minutes, type = "NORMAL", temporaryId = 99L,
        baselinePumpId = baseline, createdAt = 1L,
    ).also {
        journal.prepare(it)
        journal.dispatched("a", 2_000L)
        journal.started("a", 2_500L)
    }

    @Test
    fun `manual running TBR imports at pump time as a percent record`() {
        assertNull(accounting.apply(event(48_224, 9, 110, 15), serial, zone))

        verify(sync).syncTemporaryBasalWithPumpId(pumpStart, 110.0, 15 * 60_000L, false, PumpSync.TemporaryBasalType.NORMAL, 48_224L, PumpType.YPSOPUMP, serial)
    }

    @Test
    fun `manual TBR cancelled on the pump shortens to its elapsed minutes`() {
        accounting.apply(event(48_221, 10, 80, 8), serial, zone)

        verify(sync).syncTemporaryBasalWithPumpId(pumpStart, 80.0, 8 * 60_000L, false, PumpSync.TemporaryBasalType.NORMAL, 48_221L, PumpType.YPSOPUMP, serial)
    }

    @Test
    fun `AAPS started TBR binds its row to the provisional record at the AAPS start time`() {
        startedAttempt()
        whenever(sync.syncTemporaryBasalWithTempId(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())).thenReturn(true)

        accounting.apply(event(48_224, 9, 120, 15), serial, zone)

        verify(sync).syncTemporaryBasalWithTempId(2_500L, 120.0, 15 * 60_000L, false, 99L, PumpSync.TemporaryBasalType.NORMAL, 48_224L, PumpType.YPSOPUMP, serial)
        verify(sync, never()).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())
        assertEquals(48_224L, store.attempts.single().pumpId)
    }

    @Test
    fun `bound TBR cancelled on the pump shortens without moving its start`() {
        startedAttempt()
        whenever(sync.syncTemporaryBasalWithTempId(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())).thenReturn(true)
        accounting.apply(event(48_224, 9, 120, 15), serial, zone)

        accounting.apply(event(48_224, 10, 120, 6), serial, zone)

        verify(sync).syncTemporaryBasalWithPumpId(2_500L, 120.0, 6 * 60_000L, false, PumpSync.TemporaryBasalType.NORMAL, 48_224L, PumpType.YPSOPUMP, serial)
    }

    @Test
    fun `a TBR AAPS already stopped is not extended by its terminal row`() {
        startedAttempt()
        whenever(sync.syncTemporaryBasalWithTempId(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())).thenReturn(true)
        journal.stopped(2_500L + 90_000L)

        accounting.apply(event(48_224, 10, 120, 1), serial, zone)

        verify(sync).syncTemporaryBasalWithTempId(2_500L, 120.0, 60_000L, false, 99L, PumpSync.TemporaryBasalType.NORMAL, 48_224L, PumpType.YPSOPUMP, serial)
        accounting.apply(event(48_224, 10, 120, 1), serial, zone)
        verify(sync, never()).syncTemporaryBasalWithPumpId(any(), any(), any(), any(), anyOrNull(), any(), any(), any())
    }

    @Test
    fun `a row older than the attempt baseline is never bound to it`() {
        startedAttempt(baseline = 48_230L)

        accounting.apply(event(48_224, 9, 120, 15), serial, zone)

        verify(sync).syncTemporaryBasalWithPumpId(pumpStart, 120.0, 15 * 60_000L, false, PumpSync.TemporaryBasalType.NORMAL, 48_224L, PumpType.YPSOPUMP, serial)
        assertNull(store.attempts.single().pumpId)
    }

    @Test
    fun `pump stop and resume rows bound a zero basal window at pump time`() {
        accounting.apply(event(48_215, 14, 3, 0), serial, zone)
        accounting.apply(event(48_219, 14, 10, 0), serial, zone)

        verify(sync).syncTemporaryBasalWithPumpId(eq(pumpStart), eq(0.0), any(), eq(true), eq(PumpSync.TemporaryBasalType.PUMP_SUSPEND), eq(48_215L), eq(PumpType.YPSOPUMP), eq(serial))
        verify(sync).syncStopTemporaryBasalWithPumpId(pumpStart, 48_219L, PumpType.YPSOPUMP, serial)
    }
}
