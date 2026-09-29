package app.aaps.pump.ypsopump

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.ypsopump.bolus.YpsoBolusMessage
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoEventIdentity
import app.aaps.pump.ypsopump.history.YpsoHistoryCursor
import app.aaps.pump.ypsopump.history.YpsoHistoryIngestion
import app.aaps.pump.ypsopump.history.YpsoHistoryIngestionResult
import app.aaps.pump.ypsopump.history.YpsoHistorySnapshot
import app.aaps.pump.ypsopump.history.YpsoHistoryState
import app.aaps.pump.ypsopump.history.YpsoHistoryStateStore
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class YpsoHistoryIngestionTest {
    @Test
    fun `cancelled square row updates existing extended amount and retains short delivery window`() {
        val store = Store()
        val sync: PumpSync = mock()
        var saved = app.aaps.core.data.model.EB(timestamp = 1_000L, amount = 0.5, duration = 20_000L)
        whenever(sync.getExtendedBolusWithPumpId(eq(100L), any(), eq("10000001"))).thenAnswer { saved }
        whenever(sync.correctExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())).thenAnswer {
            saved = saved.copy(amount = it.getArgument(1), duration = it.getArgument(2))
            true
        }
        val ingestion = YpsoHistoryIngestion(store, sync)
        val running = row(100, 1, 50).copy(value2 = 15)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(running)))
        val result = ingestion.ingest("10000001", ZoneId.of("UTC"), 21,
            snapshot(listOf(running.copy(eventType = 3, value1 = 8, value2 = 0))))
        assertTrue(result is YpsoHistoryIngestionResult.Applied)
        assertEquals(0.08, saved.amount)
        assertEquals(20_000L, saved.duration)
        verify(sync, org.mockito.kotlin.never()).replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any())
    }
    @Test
    fun `cancelled immediate cursor corrects provisional amount including zero without replay duplicates`() {
        for (amount in listOf(54, 0)) {
            val store = Store()
            val sync: PumpSync = mock()
            whenever(sync.replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any()))
                .thenReturn(PumpSync.BolusSyncResult.UNCHANGED)
            val bindings = mutableListOf<Pair<Long, Double>>()
            val ingestion = YpsoHistoryIngestion(store, sync) { _, id, _, delivered, _ -> bindings += id to delivered }
            val running = row(100, 19, 200)
            ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(running)))
            val terminal = running.copy(eventType = 2, value1 = amount)
            repeat(2) {
                assertTrue(ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(terminal))) is YpsoHistoryIngestionResult.Applied)
            }
            assertEquals(listOf(100L to amount / 100.0), bindings)
            verify(sync).replayConfirmedBolusWithPumpIdDetailed(any(), eq(amount / 100.0), any(), eq(100L), any(), eq("10000001"))
        }
    }
    private class Store(var value: YpsoHistoryState = YpsoHistoryState()) : YpsoHistoryStateStore {
        override fun load() = value
        override fun commit(value: YpsoHistoryState) { this.value = value }
    }

    @Test
    fun `bootstrap anchors current head without importing old insulin`() {
        val store = Store()
        val sync: PumpSync = mock()
        val ingestion = YpsoHistoryIngestion(store, sync)

        val result = ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(100, 2, 100))))

        assertTrue(result is YpsoHistoryIngestionResult.Applied)
        assertEquals(100, store.value.cursor?.identity?.sequence)
        assertNull(store.value.pendingBolus)
    }

    @Test
    fun `snapshot from another reboot epoch is rejected without changing state`() {
        val store = Store()
        val ingestion = YpsoHistoryIngestion(store, mock())

        val result = ingestion.ingest("10000001", ZoneId.of("UTC"), 22, snapshot(listOf(row(100, 2, 100))))

        assertTrue(result is YpsoHistoryIngestionResult.Blocked)
        assertNull(store.value.cursor)
        assertNull(store.value.pendingBolus)
    }

    @Test
    fun `bolus readiness is local and requires an initialized matching cursor`() {
        val sync: PumpSync = mock()
        val store = Store()
        val ingestion = YpsoHistoryIngestion(store, sync)

        assertEquals(YpsoBolusMessage.SYNC_IN_PROGRESS, ingestion.bolusReadiness("10000001", 21))
        store.value = YpsoHistoryState(
            cursor = YpsoHistoryCursor(YpsoEventIdentity("10000001", 0, 100), row(100, 2, 80).fingerprint(), 21),
        )
        assertNull(ingestion.bolusReadiness("10000001", 21))
        assertEquals(YpsoBolusMessage.PUMP_RESTARTED, ingestion.bolusReadiness("10000001", 22))
        verify(sync, org.mockito.kotlin.never()).replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `pump switch bootstraps the new serial without importing its existing history`() {
        val oldHead = row(100, 2, 80)
        val store = Store(
            YpsoHistoryState(
                cursor = YpsoHistoryCursor(YpsoEventIdentity("10000001", 0, 100), oldHead.fingerprint(), 21),
            ),
        )
        val sync: PumpSync = mock()
        val ingestion = YpsoHistoryIngestion(store, sync)

        val result = ingestion.ingest("20000002", ZoneId.of("UTC"), 21, snapshot(listOf(row(101, 2, 100))))

        assertTrue(result is YpsoHistoryIngestionResult.Applied)
        assertEquals("20000002", store.value.cursor?.identity?.pumpSerial)
        assertEquals(101, store.value.cursor?.identity?.sequence)
        verify(sync, org.mockito.kotlin.never()).replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any())
    }

    /** Records which clock offset each TBR row reached accounting with, and holds it on request. */
    private class OffsetProbe {
        val seen = mutableMapOf<Long, Long?>()
        var hold: String? = null
        val accounting: app.aaps.pump.ypsopump.tbr.YpsoTbrHistoryAccounting = mock {
            on { apply(any(), any(), any(), org.mockito.kotlin.anyOrNull(), any()) } doAnswer {
                seen[it.getArgument<app.aaps.pump.ypsopump.history.YpsoHistoryEvent>(0).identity.sequence] = it.getArgument(3)
                hold
            }
        }
    }

    @Test
    fun `the measured clock offset is not applied to rows older than a pump clock change`() {
        val store = Store()
        val probe = OffsetProbe()
        val ingestion = YpsoHistoryIngestion(store, mock(), probe.accounting)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(100, 2, 0))))

        ingestion.ingest("10000001", ZoneId.of("UTC"), 21,
            snapshot(listOf(row(104, 9, 0), row(103, 13, 0), row(102, 10, 0), row(100, 2, 0))).copy(pumpClockOffsetMs = 5_000L))

        assertEquals(mapOf(102L to null, 104L to 5_000L), probe.seen.filterKeys { it != 103L })
    }

    @Test
    fun `a TBR row waits for a clock reading only a bounded number of scans`() {
        val store = Store()
        val probe = OffsetProbe()
        val waits = mutableListOf<Boolean>()
        val accounting: app.aaps.pump.ypsopump.tbr.YpsoTbrHistoryAccounting = mock {
            on { apply(any(), any(), any(), org.mockito.kotlin.anyOrNull(), any()) } doAnswer {
                val wait = it.getArgument<Boolean>(4)
                waits += wait
                if (wait) "TBR row may belong to AAPS start; waiting for a pump clock reading" else null
            }
        }
        val ingestion = YpsoHistoryIngestion(store, mock(), accounting)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(100, 2, 0))))
        val scan = snapshot(listOf(row(101, 10, 0), row(100, 2, 0)))

        val results = (1..4).map { ingestion.ingest("10000001", ZoneId.of("UTC"), 21, scan) }

        assertEquals(listOf(true, true, true, false), waits)
        results.take(3).forEach { assertEquals(YpsoHistoryIngestionResult.Retry.BOUNDED, (it as YpsoHistoryIngestionResult.Blocked).retry) }
        assertTrue(results.last() is YpsoHistoryIngestionResult.Applied)
        assertEquals(101, store.value.cursor?.identity?.sequence)
        assertTrue(probe.seen.isEmpty())
    }

    @Test
    fun `unrecorded immediate bolus before activation is skipped without blocking newer history`() {
        val store = Store()
        val sync: PumpSync = mock()
        val ingestion = YpsoHistoryIngestion(store, sync, hasRecordedBolus = { _, _ -> false })
        val anchor = row(100, 2, 0)
        val oldDose = row(101, 2, 80)
        val oldTime = (app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.resolve(oldDose.factorySeconds, ZoneId.of("UTC"))
            as app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.Resolution.Resolved).instant.toEpochMilli()
        whenever(sync.isHistoryRecordBeforeActivePump(eq(oldTime), any(), eq("10000001"))).thenReturn(true)
        whenever(sync.replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any()))
            .thenReturn(PumpSync.BolusSyncResult.UNCHANGED)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(anchor)))
        val scan = snapshot(listOf(row(102, 2, 90), oldDose, anchor))

        repeat(2) {
            assertTrue(ingestion.ingest("10000001", ZoneId.of("UTC"), 21, scan) is YpsoHistoryIngestionResult.Applied)
            assertEquals(102L, store.value.cursor?.identity?.sequence)
            assertNull(store.value.pendingBolus)
        }
        verify(sync, org.mockito.kotlin.never()).replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), eq(101L), any(), any())
        verify(sync).replayConfirmedBolusWithPumpIdDetailed(any(), eq(0.9), any(), eq(102L), any(), any())
    }

    @Test
    fun `an already owned immediate bolus is corrected even before activation`() {
        val store = Store()
        val sync: PumpSync = mock()
        whenever(sync.isHistoryRecordBeforeActivePump(any(), any(), any())).thenReturn(true)
        whenever(sync.replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any()))
            .thenReturn(PumpSync.BolusSyncResult.UPDATED)
        val bindings = mutableListOf<Long>()
        val ingestion = YpsoHistoryIngestion(store, sync,
            hasRecordedBolus = { serial, id -> serial == "10000001" && id == 101L },
            resolveProvisional = { _, id, _, _, _ -> bindings += id })
        val anchor = row(100, 2, 0)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(anchor)))

        val result = ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(101, 2, 0), anchor)))

        assertTrue(result is YpsoHistoryIngestionResult.Applied)
        assertEquals(listOf(101L), bindings)
        verify(sync).replayConfirmedBolusWithPumpIdDetailed(any(), eq(0.0), any(), eq(101L), any(), any())
    }

    @Test
    fun `a persisted bolus outbox is never discarded by a later activation cutoff`() {
        val sync: PumpSync = mock()
        val pending = app.aaps.pump.ypsopump.history.YpsoPendingBolusSync("10000001", 101L, 1_700_000_000_000L, 80, 101L)
        val store = Store(YpsoHistoryState(cursor = cursor(100), pendingBolus = pending))
        whenever(sync.isHistoryRecordBeforeActivePump(any(), any(), any())).thenReturn(true)
        whenever(sync.replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any()))
            .thenReturn(PumpSync.BolusSyncResult.REJECTED, PumpSync.BolusSyncResult.UNCHANGED)

        assertEquals(false, YpsoHistoryIngestion(store, sync).retryPending("10000001"))
        assertEquals(pending, store.value.pendingBolus)
        // Restart, then recover the original intent, without asking the import filter to discard it.
        assertTrue(YpsoHistoryIngestion(store, sync).retryPending("10000001"))
        assertNull(store.value.pendingBolus)
        verify(sync, org.mockito.kotlin.never()).isHistoryRecordBeforeActivePump(any(), any(), any())
    }

    @Test
    fun `a bolus at activation is imported but a database failure still blocks the scan`() {
        val store = Store()
        val sync: PumpSync = mock()
        val dose = row(101, 2, 80)
        val timestamp = (app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.resolve(dose.factorySeconds, ZoneId.of("UTC"))
            as app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.Resolution.Resolved).instant.toEpochMilli()
        whenever(sync.isHistoryRecordBeforeActivePump(any(), any(), any())).thenAnswer { it.getArgument<Long>(0) < timestamp }
        whenever(sync.replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any()))
            .thenReturn(PumpSync.BolusSyncResult.REJECTED)
        val ingestion = YpsoHistoryIngestion(store, sync)
        val anchor = row(100, 2, 0)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(anchor)))

        val result = ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(dose, anchor)))

        assertTrue(result is YpsoHistoryIngestionResult.Blocked)
        assertEquals(100L, store.value.cursor?.identity?.sequence)
        assertNotNull(store.value.pendingBolus)
        verify(sync).replayConfirmedBolusWithPumpIdDetailed(eq(timestamp), any(), any(), eq(101L), any(), any())
    }

    @Test
    fun `new confirmed bolus persists intent before idempotent PumpSync acknowledgement`() {
        val store = Store()
        val sync: PumpSync = mock()
        whenever(sync.replayConfirmedBolusWithPumpIdDetailed(any(), eq(1.0), any(), any(), any(), eq("10000001")))
            .thenReturn(PumpSync.BolusSyncResult.UNCHANGED)
        val ingestion = YpsoHistoryIngestion(store, sync)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(100, 2, 80))))

        val result = ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(101, 2, 100), row(100, 2, 80))))

        assertTrue(result is YpsoHistoryIngestionResult.Applied)
        assertEquals(101, store.value.cursor?.identity?.sequence)
        assertNull(store.value.pendingBolus)
        verify(sync).replayConfirmedBolusWithPumpIdDetailed(any(), eq(1.0), any(), eq(101L), any(), eq("10000001"))
    }

    @Test
    fun `attributed SMB type is retained by the durable PumpSync outbox`() {
        val store = Store()
        val sync: PumpSync = mock()
        whenever(sync.replayConfirmedBolusWithPumpIdDetailed(any(), eq(1.0), eq(BS.Type.SMB), any(), any(), eq("10000001")))
            .thenReturn(PumpSync.BolusSyncResult.UNCHANGED)
        val ingestion = YpsoHistoryIngestion(store, sync)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(100, 2, 80))))

        val result = ingestion.ingest(
            "10000001",
            ZoneId.of("UTC"),
            21,
            snapshot(listOf(row(101, 2, 100), row(100, 2, 80))),
        ) { BS.Type.SMB }

        assertTrue(result is YpsoHistoryIngestionResult.Applied)
        verify(sync).replayConfirmedBolusWithPumpIdDetailed(any(), eq(1.0), eq(BS.Type.SMB), eq(101L), any(), eq("10000001"))
    }

    @Test
    fun `rejected DB operation leaves durable pending insulin and cursor unadvanced`() {
        val store = Store()
        val sync: PumpSync = mock()
        whenever(sync.replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any()))
            .thenReturn(PumpSync.BolusSyncResult.REJECTED)
        val ingestion = YpsoHistoryIngestion(store, sync)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(100, 2, 80))))

        val result = ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(101, 2, 100), row(100, 2, 80))))

        assertTrue(result is YpsoHistoryIngestionResult.Blocked)
        assertEquals(100, store.value.cursor?.identity?.sequence)
        assertNotNull(store.value.pendingBolus)
    }

    @Test
    fun `pending insulin cannot be relabelled to another pump after restart`() {
        val store = Store(
            YpsoHistoryState(
                pendingBolus = app.aaps.pump.ypsopump.history.YpsoPendingBolusSync(
                    "10000001", 101, 1_700_000_000_000, 100, 101,
                ),
            ),
        )
        val sync: PumpSync = mock()
        val ingestion = YpsoHistoryIngestion(store, sync)

        assertEquals(false, ingestion.retryPending("20000002"))
        assertNotNull(store.value.pendingBolus)
        verify(sync, org.mockito.kotlin.never()).replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `a completed unrecorded square wholly before pump activation does not cause endless rescans`() {
        val store = Store()
        val sync: PumpSync = mock()
        val ingestion = YpsoHistoryIngestion(store, sync)
        val cursor = row(100, 2, 0)
        val oldDose = row(101, 3, 80).copy(value2 = 3)
        val start = (app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.resolve(oldDose.factorySeconds, ZoneId.of("UTC"))
            as app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.Resolution.Resolved).instant.toEpochMilli()
        // Whole-minute elapsed time is rounded down: exclude only after the entire next minute.
        whenever(sync.isHistoryRecordBeforeActivePump(eq(start + 4 * 60_000L), any(), eq("10000001"))).thenReturn(true)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(cursor)))
        val rows = snapshot(listOf(row(102, 4, 0), oldDose, cursor))
        repeat(2) {
            assertTrue(ingestion.ingest("10000001", ZoneId.of("UTC"), 21, rows) is YpsoHistoryIngestionResult.Applied)
            assertEquals(102L, store.value.cursor?.identity?.sequence)
        }
        verify(sync, org.mockito.kotlin.never()).syncExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `a dose overlapping activation or an ordinary persistence rejection still blocks cursor advancement`() {
        for (activationDelta in listOf(120_000L, 240_000L, -1L)) {
            val store = Store()
            val sync: PumpSync = mock()
            val ingestion = YpsoHistoryIngestion(store, sync)
            val cursor = row(100, 2, 0)
            val dose = row(101, 3, 80).copy(value2 = 3)
            val start = (app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.resolve(dose.factorySeconds, ZoneId.of("UTC"))
                as app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.Resolution.Resolved).instant.toEpochMilli()
            whenever(sync.isHistoryRecordBeforeActivePump(any(), any(), eq("10000001"))).thenAnswer {
                it.getArgument<Long>(0) < start + activationDelta
            }
            ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(cursor)))
            val result = ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(dose, cursor)))
            assertTrue(result is YpsoHistoryIngestionResult.Blocked)
            assertEquals(100L, store.value.cursor?.identity?.sequence)
            verify(sync).syncExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `square bolus started on the pump is accounted over its elapsed window`() {
        val store = Store()
        val sync: PumpSync = mock()
        var saved: app.aaps.core.data.model.EB? = null
        whenever(sync.getExtendedBolusWithPumpId(any(), any(), eq("10000001"))).thenAnswer { saved }
        whenever(sync.syncExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())).thenAnswer {
            saved = app.aaps.core.data.model.EB(
                timestamp = it.getArgument(0), amount = it.getArgument(1), duration = it.getArgument(2),
            )
            true
        }
        val ingestion = YpsoHistoryIngestion(store, sync)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(100, 2, 80))))

        // Type 3 carries the delivered amount and the elapsed whole minutes of a pump-started square.
        val terminal = row(102, 3, 8).copy(value2 = 3)
        val result = ingestion.ingest("10000001", ZoneId.of("UTC"), 21,
            snapshot(listOf(terminal, row(101, 18, 40), row(100, 2, 80))))

        assertTrue(result is YpsoHistoryIngestionResult.Applied)
        assertEquals(102, store.value.cursor?.identity?.sequence)
        assertEquals(0.08, saved?.amount)
        assertEquals(180_000L, saved?.duration)
        // A combination row still carries an immediate part that cannot be separated here.
        verify(sync, org.mockito.kotlin.times(1))
            .syncExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())
        verify(sync, org.mockito.kotlin.never()).replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `a square bolus running at registration counts its share delivered from then on`() {
        val store = Store()
        val sync: PumpSync = mock()
        var saved: app.aaps.core.data.model.EB? = null
        whenever(sync.getExtendedBolusWithPumpId(any(), any(), eq("10000001"))).thenAnswer { saved }
        whenever(sync.syncExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())).thenAnswer {
            saved = app.aaps.core.data.model.EB(timestamp = it.getArgument(0), amount = it.getArgument(1), duration = it.getArgument(2))
            true
        }
        whenever(sync.correctExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())).thenAnswer {
            saved = app.aaps.core.data.model.EB(timestamp = it.getArgument(0), amount = it.getArgument(1), duration = it.getArgument(2))
            true
        }
        // Delivered 1.00 U over 29 whole minutes (up to 30); registration came 10 minutes in.
        val dose = row(101, 3, 100).copy(value2 = 29)
        val start = (app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.resolve(dose.factorySeconds, ZoneId.of("UTC"))
            as app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.Resolution.Resolved).instant.toEpochMilli()
        val registered = start + 10 * 60_000L
        whenever(sync.isHistoryRecordBeforeActivePump(any(), any(), eq("10000001"))).thenAnswer { it.getArgument<Long>(0) < registered }
        val ingestion = YpsoHistoryIngestion(store, sync, registeredAt = { registered })
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(100, 2, 0))))
        val before = store.value
        val rows = snapshot(listOf(dose, row(100, 2, 0)))

        assertTrue(ingestion.ingest("10000001", ZoneId.of("UTC"), 21, rows) is YpsoHistoryIngestionResult.Applied)

        // 20 of at most 30 minutes, rounded up to 0.01 U.
        assertEquals(registered, saved?.timestamp)
        assertEquals(0.67, saved?.amount)
        assertEquals(19 * 60_000L, saved?.duration)

        // A replay keeps the cut record instead of restoring the whole amount.
        store.value = before
        assertTrue(ingestion.ingest("10000001", ZoneId.of("UTC"), 21, rows) is YpsoHistoryIngestionResult.Applied)
        assertEquals(registered to 0.67, saved?.timestamp to saved?.amount)
        assertEquals(19 * 60_000L, saved?.duration)
    }

    @Test
    fun `a cut square keeps its cut when the pump is registered again later`() {
        val store = Store()
        val sync: PumpSync = mock()
        val dose = row(101, 3, 100).copy(value2 = 29)
        val start = (app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.resolve(dose.factorySeconds, ZoneId.of("UTC"))
            as app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.Resolution.Resolved).instant.toEpochMilli()
        val cut = app.aaps.core.data.model.EB(timestamp = start + 10 * 60_000L, amount = 0.67, duration = 19 * 60_000L)
        whenever(sync.getExtendedBolusWithPumpId(any(), any(), eq("10000001"))).thenReturn(cut)
        val ingestion = YpsoHistoryIngestion(store, sync, registeredAt = { start + 60 * 60 * 60_000L })
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(100, 2, 0))))

        val result = ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(dose, row(100, 2, 0))))

        assertTrue(result is YpsoHistoryIngestionResult.Applied)
        verify(sync, org.mockito.kotlin.never()).correctExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())
        verify(sync, org.mockito.kotlin.never()).syncExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `an AAPS extended bolus starting after its row's latest end is corrected, never mistaken for a cut`() {
        val store = Store()
        val sync: PumpSync = mock()
        val dose = row(101, 3, 20).copy(value2 = 2)
        val start = (app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.resolve(dose.factorySeconds, ZoneId.of("UTC"))
            as app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.Resolution.Resolved).instant.toEpochMilli()
        // Recorded on the phone clock; the pump clock runs five minutes behind.
        val recorded = app.aaps.core.data.model.EB(timestamp = start + 5 * 60_000L, amount = 1.0, duration = 30 * 60_000L)
        whenever(sync.getExtendedBolusWithPumpId(any(), any(), eq("10000001"))).thenReturn(recorded)
        whenever(sync.correctExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())).thenReturn(true)
        val ingestion = YpsoHistoryIngestion(store, sync)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(100, 2, 0))))

        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(dose, row(100, 2, 0))))

        verify(sync).correctExtendedBolusWithPumpId(eq(start + 5 * 60_000L), eq(0.2), eq(2 * 60_000L), any(), any(), any(), eq("10000001"))
    }

    @Test
    fun `a square whose latest end is exactly registration is passed over, not blocked`() {
        val store = Store()
        val sync: PumpSync = mock()
        val dose = row(101, 3, 100).copy(value2 = 29)
        val start = (app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.resolve(dose.factorySeconds, ZoneId.of("UTC"))
            as app.aaps.pump.ypsopump.history.YpsoPumpLocalTime.Resolution.Resolved).instant.toEpochMilli()
        val registered = start + 30 * 60_000L
        whenever(sync.isHistoryRecordBeforeActivePump(any(), any(), eq("10000001"))).thenAnswer { it.getArgument<Long>(0) < registered }
        val ingestion = YpsoHistoryIngestion(store, sync, registeredAt = { registered })
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(100, 2, 0))))

        assertTrue(ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(dose, row(100, 2, 0)))) is YpsoHistoryIngestionResult.Applied)
        verify(sync, org.mockito.kotlin.never()).syncExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `square share after registration never rounds insulin down`() {
        val part = app.aaps.pump.ypsopump.bolus.YpsoExtendedBolusAccounting.squareFrom(
            registeredAt = 60_000L, start = 0L, elapsedMinutes = 2, amount = 0.10,
        )
        // Latest end at 3 minutes: 2 of 3 minutes, 0.0667 U, rounds up.
        assertEquals(0.07, part.amount)
        assertEquals(60_000L to 60_000L, part.start to part.duration)
        val whole = app.aaps.pump.ypsopump.bolus.YpsoExtendedBolusAccounting.squareFrom(0L, 0L, 2, 0.10)
        assertEquals(0.10, whole.amount)
    }

    @Test
    fun `an already recorded extended bolus is corrected rather than imported again`() {
        val store = Store()
        val sync: PumpSync = mock()
        val existing = app.aaps.core.data.model.EB(timestamp = 5_000L, amount = 0.5, duration = 900_000L)
        whenever(sync.getExtendedBolusWithPumpId(any(), any(), eq("10000001"))).thenReturn(existing)
        whenever(sync.correctExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())).thenReturn(true)
        val ingestion = YpsoHistoryIngestion(store, sync)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(100, 2, 80))))

        ingestion.ingest("10000001", ZoneId.of("UTC"), 21,
            snapshot(listOf(row(102, 3, 50).copy(value2 = 15), row(100, 2, 80))))

        verify(sync, org.mockito.kotlin.never())
            .syncExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())
        verify(sync).correctExtendedBolusWithPumpId(eq(5_000L), eq(0.5), eq(900_000L), any(), any(), any(), eq("10000001"))
        verify(sync, org.mockito.kotlin.never()).isHistoryRecordBeforeActivePump(any(), any(), any())
    }

    @Test
    fun `provisional accounting is bound to pump identity before the authoritative row is written`() {
        val store = Store(YpsoHistoryState(cursor = cursor(100)))
        val sync: PumpSync = mock()
        whenever(sync.replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any()))
            .thenReturn(PumpSync.BolusSyncResult.UPDATED)
        val resolved = mutableListOf<Triple<Long, Double, BS.Type>>()
        val ingestion = YpsoHistoryIngestion(store, sync) { _, pumpId, _, amount, type ->
            resolved += Triple(pumpId, amount, type)
        }

        val result = ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(101, 2, 91), row(100, 2, 100))))

        assertTrue(result is YpsoHistoryIngestionResult.Applied)
        // The provisional record must be linked to the pump id before the authoritative amount is
        // written, otherwise PumpSync inserts a second record and the dose is counted twice.
        assertEquals(1, resolved.size)
        assertEquals(0.91, resolved.single().second)
        val order = org.mockito.kotlin.inOrder(sync)
        order.verify(sync).replayConfirmedBolusWithPumpIdDetailed(any(), eq(0.91), any(), any(), any(), any())
    }

    private fun cursor(sequence: Long): YpsoHistoryCursor =
        YpsoHistoryIngestion(Store(), mock()).let {
            val bootstrapStore = Store()
            YpsoHistoryIngestion(bootstrapStore, mock())
                .ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(sequence, 2, 100))))
            requireNotNull(bootstrapStore.value.cursor)
        }

    private fun row(sequence: Long, type: Int, value1: Int): YpsoHistoryEntry = YpsoHistoryEntry(
        ChronoUnit.SECONDS.between(LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2026, 9, 18, 12, sequence.toInt() % 60)),
        type, value1, 0, 0, sequence, 0,
    )

    private fun snapshot(rows: List<YpsoHistoryEntry>): YpsoHistorySnapshot {
        val indexed = rows.mapIndexed { index, row -> row.copy(index = index) }
        return YpsoHistorySnapshot(indexed.size, indexed.size, 21, 21, indexed.firstOrNull(), indexed.firstOrNull(), indexed, true)
    }
}
