package app.aaps.database.transactions

import app.aaps.database.DelegatedAppDatabase
import app.aaps.database.daos.BolusDao
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.embedments.InterfaceIDs
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SyncBolusWithTempIdTransactionTest {

    private lateinit var database: DelegatedAppDatabase
    private lateinit var bolusDao: BolusDao

    @BeforeEach
    fun setup() {
        bolusDao = mock()
        database = mock()
        whenever(database.bolusDao).thenReturn(bolusDao)
    }

    @Test
    fun `updates existing bolus when found by temp id`() {
        val bolus = createBolus(tempId = 500L, pumpId = 100L, amount = 7.0, timestamp = 2000L)
        val existing = createBolus(tempId = 500L, pumpId = null, amount = 5.0, timestamp = 1000L)

        whenever(bolusDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(existing)

        val transaction = SyncBolusWithTempIdTransaction(bolus, null)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.updated).hasSize(1)
        assertThat(existing.timestamp).isEqualTo(2000L)
        assertThat(existing.amount).isEqualTo(7.0)
        assertThat(existing.interfaceIDs.pumpId).isEqualTo(100L)

        verify(bolusDao).updateExistingEntry(existing)
    }

    @Test
    fun `late binding merges an already imported pump row without double counting`() {
        val provisional = createBolus(500L, null, 2.0, 1000L).also { it.id = 1 }
        val imported = createBolus(900L, 100L, 0.54, 1000L).also { it.id = 2; it.interfaceIDs.temporaryId = null }
        whenever(bolusDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(provisional)
        whenever(bolusDao.findByPumpIds(100L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(imported)
        val transaction = SyncBolusWithTempIdTransaction(createBolus(500L, 100L, 0.54, 1000L), null)
        transaction.database = database
        transaction.run()
        assertThat(listOf(provisional, imported).filter { it.isValid }.sumOf { it.amount }).isEqualTo(0.54)
        assertThat(imported.interfaceIDs.temporaryId).isEqualTo(500L)
        assertThat(provisional.isValid).isFalse()
        whenever(bolusDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(imported)
        transaction.run()
        assertThat(listOf(provisional, imported).filter { it.isValid }.sumOf { it.amount }).isEqualTo(0.54)
    }

    @Test
    fun `conflicting temporary identity does not invalidate either record`() {
        val provisional = createBolus(500L, null, 2.0, 1000L).also { it.id = 1 }
        val imported = createBolus(600L, 100L, 0.54, 1000L).also { it.id = 2 }
        whenever(bolusDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(provisional)
        whenever(bolusDao.findByPumpIds(100L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(imported)
        val transaction = SyncBolusWithTempIdTransaction(createBolus(500L, 100L, 0.54, 1000L), null)
        transaction.database = database
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) { transaction.run() }
        verify(bolusDao, never()).updateExistingEntry(any())
        assertThat(provisional.isValid).isTrue()
        assertThat(imported.isValid).isTrue()
    }

    @Test
    fun `removed provisional stays removed after binding and repeated history replay`() {
        assertRemovedProvisionalStaysRemoved(importedAlreadyExists = false)
    }

    @Test
    fun `removed provisional stays removed after duplicate merge and repeated history replay`() {
        assertRemovedProvisionalStaysRemoved(importedAlreadyExists = true)
    }

    private fun assertRemovedProvisionalStaysRemoved(importedAlreadyExists: Boolean) {
        val provisional = createBolus(500L, null, 2.0, 1000L).also { it.id = 1 }
        val rows = mutableListOf(provisional)
        if (importedAlreadyExists) {
            rows += createBolus(900L, 100L, 0.54, 1000L).also {
                it.id = 2
                it.interfaceIDs.temporaryId = null
            }
        }
        whenever(bolusDao.findById(1L)).thenAnswer { provisional }
        // These DAO lookups deliberately include invalid records, as the production queries do.
        whenever(bolusDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.DANA_I, "ABC123"))
            .thenAnswer { rows.singleOrNull { it.interfaceIDs.temporaryId == 500L } }
        whenever(bolusDao.findByPumpIds(100L, InterfaceIDs.PumpType.DANA_I, "ABC123"))
            .thenAnswer { rows.singleOrNull { it.interfaceIDs.pumpId == 100L } }

        InvalidateBolusTransaction(1L).also { it.database = database }.run()
        assertThat(provisional.isValid).isFalse()

        repeat(2) {
            val confirmed = createBolus(500L, 100L, 0.54, 1000L)
            SyncBolusWithTempIdTransaction(confirmed, null).also { it.database = database }.run()
            SyncPumpBolusTransaction(confirmed, null).also { it.database = database }.run()

            assertThat(rows.filter { it.isValid }).isEmpty()
            val bound = rows.single { it.interfaceIDs.pumpId == 100L }
            assertThat(bound.interfaceIDs.temporaryId).isEqualTo(500L)
            assertThat(bound.amount).isEqualTo(0.54)
        }
        verify(bolusDao, never()).insertNewEntry(any())
    }

    @Test
    fun `an inferred link binds nothing when either record was removed`() {
        for (removed in listOf("provisional", "imported")) {
            val provisional = createBolus(500L, null, 0.4, 1000L).also { it.id = 1; it.isValid = removed != "provisional" }
            val imported = createBolus(900L, 100L, 0.1, 1000L).also {
                it.id = 2
                it.interfaceIDs.temporaryId = null
                it.isValid = removed != "imported"
            }
            whenever(bolusDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(provisional)
            whenever(bolusDao.findByPumpIds(100L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(imported)

            val result = SyncBolusWithTempIdTransaction(createBolus(500L, 100L, 0.1, 1000L), null, requireValid = true)
                .also { it.database = database }.run()

            assertThat(result.refused).isTrue()
            assertThat(result.updated).isEmpty()
            assertThat(provisional.interfaceIDs.pumpId).isNull()
            assertThat(imported.interfaceIDs.temporaryId).isNull()
        }
        verify(bolusDao, never()).updateExistingEntry(any())
    }

    @Test
    fun `an inferred link merges two valid records into one and reports a missing provisional record`() {
        val provisional = createBolus(500L, null, 0.4, 1000L).also { it.id = 1 }
        val imported = createBolus(900L, 100L, 0.4, 1000L).also { it.id = 2; it.interfaceIDs.temporaryId = null }
        whenever(bolusDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(provisional)
        whenever(bolusDao.findByPumpIds(100L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(imported)

        val bound = SyncBolusWithTempIdTransaction(createBolus(500L, 100L, 0.4, 1000L), null, requireValid = true)
            .also { it.database = database }.run()

        assertThat(bound.refused).isFalse()
        assertThat(bound.found).isTrue()
        assertThat(listOf(provisional, imported).filter { it.isValid }.sumOf { it.amount }).isEqualTo(0.4)

    }

    @Test
    fun `without a provisional record an inferred link reports whether the pump record stands alone`() {
        whenever(bolusDao.findByPumpTempIds(501L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(null)
        fun run() = SyncBolusWithTempIdTransaction(createBolus(501L, 100L, 0.4, 1000L), null, requireValid = true)
            .also { it.database = database }.run()

        whenever(bolusDao.findByPumpIds(100L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(null)
        run().let { assertThat(it.found || it.pumpRecordOnly || it.refused).isFalse() }

        val imported = createBolus(900L, 100L, 0.4, 1000L).also { it.id = 2; it.interfaceIDs.temporaryId = null }
        whenever(bolusDao.findByPumpIds(100L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(imported)
        assertThat(run().pumpRecordOnly).isTrue()

        imported.isValid = false
        run().let { assertThat(it.refused).isTrue(); assertThat(it.pumpRecordOnly).isFalse() }

        imported.isValid = true
        imported.interfaceIDs.temporaryId = 777L
        assertThat(run().refused).isTrue()
        verify(bolusDao, never()).updateExistingEntry(any())
    }

    @Test
    fun `does not update when not found by temp id`() {
        val bolus = createBolus(tempId = 500L, pumpId = 100L, amount = 7.0, timestamp = 2000L)

        whenever(bolusDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(null)

        val transaction = SyncBolusWithTempIdTransaction(bolus, null)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.updated).isEmpty()

        verify(bolusDao, never()).updateExistingEntry(any())
    }

    @Test
    fun `updates type when provided`() {
        val bolus = createBolus(tempId = 500L, pumpId = 100L, amount = 5.0, timestamp = 1000L, type = Bolus.Type.NORMAL)
        val existing = createBolus(tempId = 500L, pumpId = null, amount = 5.0, timestamp = 1000L, type = Bolus.Type.NORMAL)

        whenever(bolusDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.DANA_I, "ABC123")).thenReturn(existing)

        val transaction = SyncBolusWithTempIdTransaction(bolus, Bolus.Type.SMB)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.updated).hasSize(1)
        assertThat(existing.type).isEqualTo(Bolus.Type.SMB)
    }

    private fun createBolus(
        tempId: Long,
        pumpId: Long?,
        amount: Double,
        timestamp: Long,
        type: Bolus.Type = Bolus.Type.NORMAL
    ): Bolus = Bolus(
        timestamp = timestamp,
        amount = amount,
        type = type,
        interfaceIDs_backing = InterfaceIDs(
            temporaryId = tempId,
            pumpId = pumpId,
            pumpType = InterfaceIDs.PumpType.DANA_I,
            pumpSerial = "ABC123"
        )
    )
}
