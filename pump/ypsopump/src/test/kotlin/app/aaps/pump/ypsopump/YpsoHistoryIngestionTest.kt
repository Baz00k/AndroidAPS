package app.aaps.pump.ypsopump

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.pump.PumpSync
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
        whenever(sync.syncExtendedBolusWithPumpId(any(), any(), any(), any(), any(), any(), any())).thenAnswer {
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

        assertEquals("AAPS is still syncing with the pump. Please try again shortly.", ingestion.bolusReadiness("10000001", 21))
        store.value = YpsoHistoryState(
            cursor = YpsoHistoryCursor(YpsoEventIdentity("10000001", 0, 100), row(100, 2, 80).fingerprint(), 21),
        )
        assertNull(ingestion.bolusReadiness("10000001", 21))
        assertEquals("The pump was restarted. AAPS needs to sync before the next bolus.", ingestion.bolusReadiness("10000001", 22))
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
    fun `square and combination terminal rows stay unaccounted until timing semantics exist`() {
        val store = Store()
        val sync: PumpSync = mock()
        val ingestion = YpsoHistoryIngestion(store, sync)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(100, 2, 80))))

        val result = ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(102, 3, 8), row(101, 18, 40), row(100, 2, 80))))

        assertTrue(result is YpsoHistoryIngestionResult.Applied)
        assertEquals(102, store.value.cursor?.identity?.sequence)
        assertNull(store.value.pendingBolus)
        verify(sync, org.mockito.kotlin.never()).replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any())
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
