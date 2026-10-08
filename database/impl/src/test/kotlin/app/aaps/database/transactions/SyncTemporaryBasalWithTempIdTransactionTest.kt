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
        val tb = createTemporaryBasal(tempId = 500L, pumpId = 100L, rate = 2.0, duration = 30_000L, timestamp = 2000L)
        val existing = createTemporaryBasal(tempId = 500L, pumpId = null, rate = 1.5, duration = 60_000L, timestamp = 1000L)

        whenever(temporaryBasalDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(existing)

        val transaction = SyncTemporaryBasalWithTempIdTransaction(tb, null)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.updated).hasSize(1)
        val (old, updated) = result.updated[0]
        assertThat(updated.timestamp).isEqualTo(2000L)
        assertThat(updated.rate).isEqualTo(2.0)
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
    fun `merge retains imported identity and invalidates only the provisional copy`() {
        val input = createTemporaryBasal(500, 100, 80.0, 30_000, 2000).copy(isAbsolute = false)
        val provisional = createTemporaryBasal(500, null, 2.0, 60_000, 1000).copy(id = 1, type = TemporaryBasal.Type.SUPERBOLUS)
        provisional.interfaceIDs.endId = 200
        val imported = createTemporaryBasal(500, 100, 1.5, 60_000, 1000).copy(id = 2)
        imported.interfaceIDs.temporaryId = null
        imported.interfaceIDs.nightscoutId = "imported-ns-id"
        whenever(temporaryBasalDao.findByPumpTempIds(500, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(provisional)
        whenever(temporaryBasalDao.findByPumpIds(100, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(imported)

        val result = run(input)

        val retired = result.updated.single { it.second.id == 1L }.second
        val canonical = result.updated.single { it.second.id == 2L }.second
        assertThat(retired.isValid).isFalse()
        assertThat(retired.interfaceIDs.temporaryId).isNull()
        assertThat(retired.interfaceIDs.pumpId).isNull()
        assertThat(retired.interfaceIDs.endId).isNull()
        assertThat(canonical.interfaceIDs.endId).isEqualTo(200L)
        assertThat(canonical.isValid).isTrue()
        assertThat(canonical.interfaceIDs.temporaryId).isEqualTo(500L)
        assertThat(canonical.interfaceIDs.pumpId).isEqualTo(100L)
        assertThat(canonical.interfaceIDs.nightscoutId).isEqualTo("imported-ns-id")
        assertThat(canonical.timestamp).isEqualTo(2000L)
        assertThat(canonical.rate).isEqualTo(80.0)
        assertThat(canonical.isAbsolute).isFalse()
        assertThat(canonical.duration).isEqualTo(30_000L)
        assertThat(canonical.type).isEqualTo(TemporaryBasal.Type.SUPERBOLUS)
        verify(temporaryBasalDao).updateExistingEntry(retired)
        verify(temporaryBasalDao).updateExistingEntry(canonical)
    }

    @Test
    fun `removal of either representation survives merging`() {
        for ((provisionalValid, importedValid) in listOf(false to true, true to false, false to false)) {
            val provisional = createTemporaryBasal(500, null, 1.5, 60_000, 1000).copy(id = 1, isValid = provisionalValid)
            val imported = createTemporaryBasal(500, 100, 1.5, 60_000, 1000).copy(id = 2, isValid = importedValid)
            imported.interfaceIDs.temporaryId = null
            whenever(temporaryBasalDao.findByPumpTempIds(500, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(provisional)
            whenever(temporaryBasalDao.findByPumpIds(100, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(imported)

            val result = run(createTemporaryBasal(500, 100, 1.5, 60_000, 1000))

            assertThat(result.updated.map { it.second.isValid }).containsExactly(false, false)
        }
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
    fun `missing required identity fields fail before writing`() {
        for (field in listOf("temporaryId", "pumpType", "pumpSerial")) {
            val input = createTemporaryBasal(500, 100, 1.5, 60_000, 1000)
            when (field) {
                "temporaryId" -> input.interfaceIDs.temporaryId = null
                "pumpType" -> input.interfaceIDs.pumpType = null
                "pumpSerial" -> input.interfaceIDs.pumpSerial = null
            }
            assertThrows(IllegalStateException::class.java) { run(input) }
        }
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

    @Test
    fun `identical retry of an already bound row updates only that row`() {
        val existing = createTemporaryBasal(500, 100, 1.5, 60_000, 1000).copy(id = 1)
        whenever(temporaryBasalDao.findByPumpTempIds(500, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(existing)
        whenever(temporaryBasalDao.findByPumpIds(100, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(existing)

        val result = run(createTemporaryBasal(500, 100, 1.5, 60_000, 1000))
        assertThat(result.updated).hasSize(1)
        assertThat(result.updated.single().second.id).isEqualTo(1L)
        verify(temporaryBasalDao).updateExistingEntry(existing)
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
