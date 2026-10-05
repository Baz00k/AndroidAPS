package app.aaps.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aaps.database.di.DatabaseModule
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.embedments.InterfaceIDs
import app.aaps.database.transactions.InsertBolusWithTempIdTransaction
import app.aaps.database.transactions.InsertTemporaryBasalWithTempIdTransaction
import app.aaps.database.transactions.SyncBolusWithTempIdTransaction
import app.aaps.database.transactions.SyncPumpBolusTransaction
import app.aaps.database.transactions.SyncPumpTemporaryBasalTransaction
import app.aaps.database.transactions.SyncTemporaryBasalWithTempIdTransaction
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PumpHistoryReplayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "pump-history-${UUID.randomUUID()}.db"
    private val start = Instant.parse("2026-03-29T00:55:00Z").toEpochMilli()
    private val duration = 30 * 60_000L
    private lateinit var db: AppDatabase
    private lateinit var repository: AppRepository

    @Before
    fun openDatabase() {
        db = DatabaseModule().provideAppDatabase(context, databaseName)
        repository = AppRepository(db)
    }

    @After
    fun deleteDatabase() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun correctedPumpAmountReplacesPreviousAmountWithoutCountingHistory() {
        syncBolus(2.0)
        val id = repository.getBolusesDataFromTime(start, true).blockingGet().single().id
        syncBolus(1.4)
        val lastId = repository.getLastBolusId()
        syncBolus(1.4)
        reopenDatabase()

        assertSingleBolus(1.4)
        assertThat(repository.getBolusesDataFromTime(start, true).blockingGet().single().id).isEqualTo(id)
        assertThat(repository.getLastBolusId()).isEqualTo(lastId)
    }

    @Test
    fun provisionalBolusAndConfirmedHistoryHaveOneIdentity() {
        repository.runTransactionForResult(InsertBolusWithTempIdTransaction(bolus(2.0, pumpId = null, temporaryId = 701))).blockingGet()
        repository.runTransactionForResult(SyncBolusWithTempIdTransaction(bolus(1.4, temporaryId = 701), null)).blockingGet()
        assertSingleBolus(1.4)
        syncBolus(1.4)
        reopenDatabase()

        assertSingleBolus(1.4)
        assertThat(repository.getBolusesDataFromTime(start, true).blockingGet().single().interfaceIDs.temporaryId).isEqualTo(701L)
    }

    @Test
    fun independentlyImportedHistoryAndProvisionalBolusMergeWithoutDoubleCounting() {
        repository.runTransactionForResult(InsertBolusWithTempIdTransaction(bolus(2.0, pumpId = null, temporaryId = 701))).blockingGet()
        syncBolus(1.4)
        repository.runTransactionForResult(SyncBolusWithTempIdTransaction(bolus(1.4, temporaryId = 701), null)).blockingGet()
        assertSingleBolus(1.4)
        syncBolus(1.4)
        reopenDatabase()

        assertSingleBolus(1.4)
        val records = repository.getBolusesIncludingInvalidFromTime(start, true).blockingGet()
        assertThat(records).hasSize(2)
        assertThat(records.count { !it.isValid }).isEqualTo(1)
        assertThat(records.single { it.isValid }.interfaceIDs.temporaryId).isEqualTo(701L)
    }

    @Test
    fun replayedTemporaryBasalIsActiveOnceAndEndsAtItsDuration() {
        syncBasal()
        val lastId = db.temporaryBasalDao.getLastId()
        syncBasal()
        reopenDatabase()

        assertSingleBasal()
        assertThat(db.temporaryBasalDao.getLastId()).isEqualTo(lastId)
    }

    @Test
    fun replayDoesNotExtendTemporaryBasalReplacedByNewerHistory() {
        val replacementTime = start + 10 * 60_000L
        syncBasal()
        repository.runTransactionForResult(
            SyncPumpTemporaryBasalTransaction(basal(pumpId = 803, rate = 0.6).copy(timestamp = replacementTime), null)
        ).blockingGet()
        syncBasal()
        reopenDatabase()

        val records = repository.getTemporaryBasalsActiveBetweenTimeAndTime(start, start + duration - 1).blockingGet()
        assertThat(records).hasSize(2)
        val replaced = records.single { it.interfaceIDs.pumpId == 802L }
        assertThat(replaced.duration).isEqualTo(10 * 60_000L)
        assertThat(repository.getTemporaryBasalActiveAt(replacementTime - 1).blockingGet()!!.interfaceIDs.pumpId).isEqualTo(802L)
        assertThat(repository.getTemporaryBasalActiveAt(replacementTime).blockingGet()!!.interfaceIDs.pumpId).isEqualTo(803L)
    }

    @Test
    fun provisionalTemporaryBasalReconcilesWithConfirmedHistory() {
        repository.runTransactionForResult(
            InsertTemporaryBasalWithTempIdTransaction(basal(pumpId = null, temporaryId = 702, rate = 1.0, duration = 1_200_000))
        ).blockingGet()
        repository.runTransactionForResult(SyncTemporaryBasalWithTempIdTransaction(basal(temporaryId = 702), null)).blockingGet()
        assertSingleBasal()
        syncBasal()
        reopenDatabase()

        assertSingleBasal()
        assertThat(repository.getTemporaryBasalActiveAt(start).blockingGet()!!.interfaceIDs.temporaryId).isEqualTo(702L)
    }

    private fun syncBolus(amount: Double) {
        repository.runTransactionForResult(SyncPumpBolusTransaction(bolus(amount), null)).blockingGet()
    }

    private fun syncBasal() {
        repository.runTransactionForResult(SyncPumpTemporaryBasalTransaction(basal(), null)).blockingGet()
    }

    private fun assertSingleBolus(amount: Double) {
        // This is the current, valid history query consumed by insulin accounting, not the transaction's write list.
        val records = repository.getBolusesDataFromTime(start, true).blockingGet()
        assertThat(records).hasSize(1)
        assertThat(records.sumOf { it.amount }).isEqualTo(amount)
        val record = records.single()
        assertThat(record.timestamp).isEqualTo(start)
        assertThat(record.utcOffset).isEqualTo(3_600_000L)
        assertThat(record.type).isEqualTo(Bolus.Type.NORMAL)
        assertThat(record.interfaceIDs.pumpId).isEqualTo(801L)
        assertThat(record.interfaceIDs.pumpType).isEqualTo(InterfaceIDs.PumpType.GENERIC_AAPS)
        assertThat(record.interfaceIDs.pumpSerial).isEqualTo("synthetic-pump")
    }

    private fun assertSingleBasal() {
        val records = repository.getTemporaryBasalsActiveBetweenTimeAndTime(start, start + duration - 1).blockingGet()
        assertThat(records).hasSize(1)
        val active = repository.getTemporaryBasalActiveAt(start + duration / 2).blockingGet()!!
        assertThat(active.rate).isEqualTo(0.8)
        assertThat(active.isAbsolute).isTrue()
        assertThat(active.timestamp).isEqualTo(start)
        assertThat(active.utcOffset).isEqualTo(3_600_000L)
        assertThat(active.duration).isEqualTo(duration)
        assertThat(active.interfaceIDs.pumpId).isEqualTo(802L)
        assertThat(repository.getTemporaryBasalActiveAt(start - 1).blockingGet()).isNull()
        assertThat(repository.getTemporaryBasalActiveAt(start).blockingGet()).isNotNull()
        assertThat(repository.getTemporaryBasalActiveAt(start + duration - 1).blockingGet()).isNotNull()
        assertThat(repository.getTemporaryBasalActiveAt(start + duration).blockingGet()).isNull()
    }

    private fun reopenDatabase() {
        db.close()
        openDatabase()
    }

    private fun bolus(amount: Double, pumpId: Long? = 801, temporaryId: Long? = null) = Bolus(
        timestamp = start,
        utcOffset = 3_600_000,
        amount = amount,
        type = Bolus.Type.NORMAL,
        interfaceIDs_backing = ids(pumpId, temporaryId)
    )

    private fun basal(pumpId: Long? = 802, temporaryId: Long? = null, rate: Double = 0.8, duration: Long = this.duration) = TemporaryBasal(
        timestamp = start,
        utcOffset = 3_600_000,
        type = TemporaryBasal.Type.NORMAL,
        isAbsolute = true,
        rate = rate,
        duration = duration,
        interfaceIDs_backing = ids(pumpId, temporaryId)
    )

    private fun ids(pumpId: Long?, temporaryId: Long?) = InterfaceIDs(
        pumpType = InterfaceIDs.PumpType.GENERIC_AAPS,
        pumpSerial = "synthetic-pump",
        pumpId = pumpId,
        temporaryId = temporaryId
    )
}
