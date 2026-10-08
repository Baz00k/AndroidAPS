package app.aaps.database.transactions

import app.aaps.database.DelegatedAppDatabase
import app.aaps.database.daos.TemporaryBasalDao
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.embedments.InterfaceIDs
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SyncTemporaryBasalWithTempIdTransactionTest {

    private lateinit var database: DelegatedAppDatabase
    private lateinit var temporaryBasalDao: TemporaryBasalDao

    @BeforeEach
    fun setup() {
        temporaryBasalDao = mock()
        database = mock()
        whenever(database.temporaryBasalDao).thenReturn(temporaryBasalDao)
    }

    @Test
    fun `updates existing temporary basal when found by temp id`() {
        val tb = createTemporaryBasal(tempId = 500L, pumpId = 100L, rate = 80.0, duration = 30_000L, timestamp = 2000L).copy(isAbsolute = false)
        val existing = createTemporaryBasal(tempId = 500L, pumpId = null, rate = 1.5, duration = 60_000L, timestamp = 1000L)

        whenever(temporaryBasalDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(existing)

        val transaction = SyncTemporaryBasalWithTempIdTransaction(tb, null)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.updated).hasSize(1)
        val (old, updated) = result.updated[0]
        assertThat(updated.timestamp).isEqualTo(2000L)
        assertThat(updated.rate).isEqualTo(80.0)
        assertThat(updated.isAbsolute).isFalse()
        assertThat(updated.duration).isEqualTo(30_000L)
        assertThat(updated.interfaceIDs.pumpId).isEqualTo(100L)

        assertThat(old.timestamp).isEqualTo(1000L)
        assertThat(old.rate).isEqualTo(1.5)
        verify(temporaryBasalDao).updateExistingEntry(updated)
    }

    @Test
    fun `does not update when not found by temp id`() {
        val tb = createTemporaryBasal(tempId = 500L, pumpId = 100L, rate = 2.0, duration = 30_000L, timestamp = 2000L)

        whenever(temporaryBasalDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(null)

        val transaction = SyncTemporaryBasalWithTempIdTransaction(tb, null)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.updated).isEmpty()

        verify(temporaryBasalDao, never()).updateExistingEntry(any())
    }

    @Test
    fun `updates type when provided`() {
        val tb = createTemporaryBasal(tempId = 500L, pumpId = 100L, rate = 1.5, duration = 60_000L, timestamp = 1000L, type = TemporaryBasal.Type.NORMAL)
        val existing = createTemporaryBasal(tempId = 500L, pumpId = null, rate = 1.5, duration = 60_000L, timestamp = 1000L, type = TemporaryBasal.Type.NORMAL)

        whenever(temporaryBasalDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(existing)

        val transaction = SyncTemporaryBasalWithTempIdTransaction(tb, TemporaryBasal.Type.EMULATED_PUMP_SUSPEND)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.updated).hasSize(1)
        assertThat(result.updated.single().second.type).isEqualTo(TemporaryBasal.Type.EMULATED_PUMP_SUSPEND)
    }

    @Test
    fun `null pump id duration update retains a previously bound identity`() {
        val existing = createTemporaryBasal(500, 100, 1.5, 60_000, 1000).copy(id = 1)
        whenever(temporaryBasalDao.findByPumpTempIds(500, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(existing)

        val updated = run(createTemporaryBasal(500, null, 1.5, 30_000, 1000)).updated.single().second

        assertThat(updated.interfaceIDs.pumpId).isEqualTo(100L)
        assertThat(updated.duration).isEqualTo(30_000L)
    }

    @Test
    fun `conflicting identities fail before writing`() {
        val existing = createTemporaryBasal(500, 101, 1.5, 60_000, 1000).copy(id = 1)
        whenever(temporaryBasalDao.findByPumpTempIds(500, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(existing)
        assertThrows(IllegalStateException::class.java) { run(createTemporaryBasal(500, 100, 1.5, 60_000, 1000)) }
        existing.interfaceIDs.pumpId = null
        val imported = createTemporaryBasal(501, 100, 1.5, 60_000, 1000).copy(id = 2)
        whenever(temporaryBasalDao.findByPumpIds(100, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(imported)
        assertThrows(IllegalStateException::class.java) { run(createTemporaryBasal(500, 100, 1.5, 60_000, 1000)) }
        verify(temporaryBasalDao, never()).updateExistingEntry(any())
    }

    @Test
    fun `known end is preserved when timestamp changes and minimum duration is one millisecond`() {
        for ((timestamp, expectedDuration) in listOf(2000L to 29_000L, 32_000L to 1L)) {
            val existing = createTemporaryBasal(500, null, 1.5, 30_000, 1000).copy(id = 1)
            existing.interfaceIDs.endId = 200
            whenever(temporaryBasalDao.findByPumpTempIds(500, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(existing)

            val updated = run(createTemporaryBasal(500, 100, 1.5, 60_000, timestamp)).updated.single().second

            assertThat(updated.duration).isEqualTo(expectedDuration)
            assertThat(updated.interfaceIDs.endId).isEqualTo(200L)
        }
    }

    private fun run(basal: TemporaryBasal): SyncTemporaryBasalWithTempIdTransaction.TransactionResult =
        SyncTemporaryBasalWithTempIdTransaction(basal, null).also { it.database = database }.run()

    private fun createTemporaryBasal(
        tempId: Long,
        pumpId: Long?,
        rate: Double,
        duration: Long,
        timestamp: Long,
        type: TemporaryBasal.Type = TemporaryBasal.Type.NORMAL
    ): TemporaryBasal = TemporaryBasal(
        timestamp = timestamp,
        rate = rate,
        duration = duration,
        type = type,
        isAbsolute = true,
        interfaceIDs_backing = InterfaceIDs(
            temporaryId = tempId,
            pumpId = pumpId,
            pumpType = InterfaceIDs.PumpType.DANA_I,
            pumpSerial = "ABC123"
        )
    )
}
