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
        verify(sync, org.mockito.kotlin.never()).syncBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `new confirmed bolus persists intent before idempotent PumpSync acknowledgement`() {
        val store = Store()
        val sync: PumpSync = mock()
        whenever(sync.syncBolusWithPumpIdDetailed(any(), eq(1.0), any(), any(), any(), eq("10000001")))
            .thenReturn(PumpSync.BolusSyncResult.UNCHANGED)
        val ingestion = YpsoHistoryIngestion(store, sync)
        ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(100, 2, 80))))

        val result = ingestion.ingest("10000001", ZoneId.of("UTC"), 21, snapshot(listOf(row(101, 2, 100), row(100, 2, 80))))

        assertTrue(result is YpsoHistoryIngestionResult.Applied)
        assertEquals(101, store.value.cursor?.identity?.sequence)
        assertNull(store.value.pendingBolus)
        verify(sync).syncBolusWithPumpIdDetailed(any(), eq(1.0), any(), eq(101L), any(), eq("10000001"))
    }

    @Test
    fun `attributed SMB type is retained by the durable PumpSync outbox`() {
        val store = Store()
        val sync: PumpSync = mock()
        whenever(sync.syncBolusWithPumpIdDetailed(any(), eq(1.0), eq(BS.Type.SMB), any(), any(), eq("10000001")))
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
        verify(sync).syncBolusWithPumpIdDetailed(any(), eq(1.0), eq(BS.Type.SMB), eq(101L), any(), eq("10000001"))
    }

    @Test
    fun `rejected DB operation leaves durable pending insulin and cursor unadvanced`() {
        val store = Store()
        val sync: PumpSync = mock()
        whenever(sync.syncBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any()))
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
        verify(sync, org.mockito.kotlin.never()).syncBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any())
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
        verify(sync, org.mockito.kotlin.never()).syncBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any())
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
