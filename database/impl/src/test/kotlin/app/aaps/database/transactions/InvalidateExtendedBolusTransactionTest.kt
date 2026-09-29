package app.aaps.database.transactions

import app.aaps.database.DelegatedAppDatabase
import app.aaps.database.daos.ExtendedBolusDao
import app.aaps.database.entities.ExtendedBolus
import app.aaps.database.entities.embedments.InterfaceIDs
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class InvalidateExtendedBolusTransactionTest {

    private lateinit var database: DelegatedAppDatabase
    private lateinit var extendedBolusDao: ExtendedBolusDao

    @BeforeEach
    fun setup() {
        extendedBolusDao = mock()
        database = mock()
        whenever(database.extendedBolusDao).thenReturn(extendedBolusDao)
    }

    @Test
    fun `invalidates valid extended bolus`() {
        val eb = createExtendedBolus(id = 1, isValid = true)

        whenever(extendedBolusDao.findById(1)).thenReturn(eb)

        val transaction = InvalidateExtendedBolusTransaction(id = 1)
        transaction.database = database
        val result = transaction.run()

        assertThat(eb.isValid).isFalse()
        assertThat(result.invalidated).hasSize(1)

        verify(extendedBolusDao).updateExistingEntry(eb)
    }

    @Test
    fun `does not update already invalid extended bolus`() {
        val eb = createExtendedBolus(id = 1, isValid = false)

        whenever(extendedBolusDao.findById(1)).thenReturn(eb)

        val transaction = InvalidateExtendedBolusTransaction(id = 1)
        transaction.database = database
        val result = transaction.run()

        assertThat(result.invalidated).isEmpty()

        verify(extendedBolusDao, never()).updateExistingEntry(any())
    }

    @Test
    fun `refuses a extended bolus still running at the guard time`() {
        val eb = createExtendedBolus(id = 1, isValid = true).also { it.timestamp = 1_000L; it.duration = 60_000L }
        whenever(extendedBolusDao.findById(1)).thenReturn(eb)

        val transaction = InvalidateExtendedBolusTransaction(id = 1, refuseIfActiveAt = { 60_999L })
        transaction.database = database
        val result = transaction.run()

        assertThat(eb.isValid).isTrue()
        assertThat(result.invalidated).isEmpty()
        assertThat(result.refusedActive).containsExactly(eb)
        verify(extendedBolusDao, never()).updateExistingEntry(any())
    }

    @Test
    fun `invalidates a extended bolus that ended at the guard time`() {
        val eb = createExtendedBolus(id = 1, isValid = true).also { it.timestamp = 1_000L; it.duration = 60_000L }
        whenever(extendedBolusDao.findById(1)).thenReturn(eb)

        val transaction = InvalidateExtendedBolusTransaction(id = 1, refuseIfActiveAt = { 61_000L })
        transaction.database = database
        val result = transaction.run()

        assertThat(eb.isValid).isFalse()
        assertThat(result.invalidated).containsExactly(eb)
        assertThat(result.refusedActive).isEmpty()
        verify(extendedBolusDao).updateExistingEntry(eb)
    }

    @Test
    fun `invalidates a running extended bolus when no guard is requested`() {
        val eb = createExtendedBolus(id = 1, isValid = true).also { it.timestamp = System.currentTimeMillis(); it.duration = 3_600_000L }
        whenever(extendedBolusDao.findById(1)).thenReturn(eb)

        val transaction = InvalidateExtendedBolusTransaction(id = 1)
        transaction.database = database
        val result = transaction.run()

        assertThat(eb.isValid).isFalse()
        assertThat(result.invalidated).containsExactly(eb)
    }

    @Test
    fun `guard reads the clock when the transaction runs so a clock moved backwards cannot remove a running extended bolus`() {
        val eb = createExtendedBolus(id = 1, isValid = true).also { it.timestamp = 1_000L; it.duration = 60_000L }
        whenever(extendedBolusDao.findById(1)).thenReturn(eb)
        var now = 61_001L
        val transaction = InvalidateExtendedBolusTransaction(id = 1, refuseIfActiveAt = { now })
        transaction.database = database

        now = 60_999L
        val result = transaction.run()

        assertThat(eb.isValid).isTrue()
        assertThat(result.refusedActive).containsExactly(eb)
        verify(extendedBolusDao, never()).updateExistingEntry(any())
    }

    @Test
    fun `throws exception when extended bolus not found`() {
        whenever(extendedBolusDao.findById(999)).thenReturn(null)

        val transaction = InvalidateExtendedBolusTransaction(id = 999)
        transaction.database = database

        try {
            transaction.run()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("There is no such Extended Bolus with the specified ID.")
        }
    }

    private fun createExtendedBolus(
        id: Long,
        isValid: Boolean
    ): ExtendedBolus = ExtendedBolus(
        timestamp = System.currentTimeMillis(),
        amount = 5.0,
        duration = 120_000L,
        isValid = isValid,
        interfaceIDs_backing = InterfaceIDs()
    ).also { it.id = id }
}
