package app.aaps.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aaps.database.di.DatabaseModule
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.embedments.InterfaceIDs
import app.aaps.database.transactions.InvalidateTemporaryBasalTransaction
import app.aaps.database.transactions.InsertBolusWithTempIdTransaction
import app.aaps.database.transactions.InsertTemporaryBasalWithTempIdTransaction
import app.aaps.database.transactions.SyncBolusWithTempIdTransaction
import app.aaps.database.transactions.SyncPumpBolusTransaction
import app.aaps.database.transactions.SyncPumpTemporaryBasalTransaction
import app.aaps.database.transactions.SyncTemporaryBasalWithTempIdTransaction
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
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

    @Test
    fun independentlyImportedHistoryAndProvisionalBasalMergeWithoutDoubleCounting() {
        repository.runTransactionForResult(
            InsertTemporaryBasalWithTempIdTransaction(basal(pumpId = null, temporaryId = 702, rate = 1.0))
        ).blockingGet()
        syncBasal()
        val importedId = db.temporaryBasalDao.findByPumpIds(802, InterfaceIDs.PumpType.GENERIC_AAPS, "synthetic-pump")!!.id

        repository.runTransactionForResult(SyncTemporaryBasalWithTempIdTransaction(basal(temporaryId = 702), null)).blockingGet()
        assertSingleBasal()
        // Both the IOB starting-time query and the active-basal query must see only one current valid record.
        assertThat(repository.getTemporaryBasalsStartingFromTime(start, true).blockingGet()).hasSize(1)
        assertThat(repository.getTemporaryBasalsStartingFromTimeIncludingInvalid(start, true).blockingGet()).hasSize(2)
        assertThat(db.temporaryBasalDao.findByPumpIds(802, InterfaceIDs.PumpType.GENERIC_AAPS, "synthetic-pump")!!.id).isEqualTo(importedId)

        repeat(2) {
            repository.runTransactionForResult(SyncTemporaryBasalWithTempIdTransaction(basal(temporaryId = 702), null)).blockingGet()
            syncBasal()
            reopenDatabase()
            assertSingleBasal()
            val records = repository.getTemporaryBasalsStartingFromTimeIncludingInvalid(start, true).blockingGet()
            assertThat(records.single { it.isValid }.id).isEqualTo(importedId)
            assertThat(records.single { it.isValid }.interfaceIDs.temporaryId).isEqualTo(702L)
            assertThat(records.single { !it.isValid }.interfaceIDs.temporaryId).isNull()
            assertThat(records.single { !it.isValid }.interfaceIDs.pumpId).isNull()
        }
    }

    @Test
    fun lateBasalBindingKeepsNewerReplacementEndAcrossReplay() {
        // The imported pump timestamp is 2 s later than the phone's provisional start. Importing
        // it cuts the provisional at that time, but that cut is not an actual delivery stop.
        repository.runTransactionForResult(
            InsertTemporaryBasalWithTempIdTransaction(basal(pumpId = null, temporaryId = 702))
        ).blockingGet()
        val imported = basal().copy(timestamp = start + 2_000L)
        repository.runTransactionForResult(SyncPumpTemporaryBasalTransaction(imported, null)).blockingGet()
        val replacementTime = start + 10 * 60_000L
        repository.runTransactionForResult(
            SyncPumpTemporaryBasalTransaction(basal(pumpId = 803, rate = 0.6).copy(timestamp = replacementTime), null)
        ).blockingGet()

        repeat(2) {
            repository.runTransactionForResult(SyncTemporaryBasalWithTempIdTransaction(basal(temporaryId = 702), null)).blockingGet()
            syncBasal()
            reopenDatabase()

            val records = repository.getTemporaryBasalsStartingFromTime(start, true).blockingGet()
            assertThat(records).hasSize(2)
            val merged = records.single { it.interfaceIDs.pumpId == 802L }
            assertThat(merged.timestamp).isEqualTo(start)
            assertThat(merged.duration).isEqualTo(10 * 60_000L)
            assertThat(merged.interfaceIDs.endId).isEqualTo(803L)
            assertThat(db.temporaryBasalDao.findByPumpEndIds(803, InterfaceIDs.PumpType.GENERIC_AAPS, "synthetic-pump")!!.id).isEqualTo(merged.id)
            assertThat(repository.getTemporaryBasalActiveAt(replacementTime - 1).blockingGet()!!.id).isEqualTo(merged.id)
            assertThat(repository.getTemporaryBasalActiveAt(replacementTime).blockingGet()!!.interfaceIDs.pumpId).isEqualTo(803L)
        }
    }

    @Test
    fun importingSameBasalDoesNotTurnTimestampSkewIntoAnEarlyStop() {
        repository.runTransactionForResult(
            InsertTemporaryBasalWithTempIdTransaction(basal(pumpId = null, temporaryId = 702))
        ).blockingGet()
        repository.runTransactionForResult(SyncPumpTemporaryBasalTransaction(basal().copy(timestamp = start + 2_000), null)).blockingGet()
        repository.runTransactionForResult(SyncTemporaryBasalWithTempIdTransaction(basal(temporaryId = 702), null)).blockingGet()
        reopenDatabase()

        assertSingleBasal()
        assertThat(repository.getTemporaryBasalsStartingFromTime(start, true).blockingGet().single().interfaceIDs.endId).isNull()
    }

    @Test
    fun removedBasalRepresentationStaysRemovedAfterMergeAndHistoryReplay() {
        for (removeImported in listOf(false, true)) {
            val pumpId = if (removeImported) 804L else 802L
            val tempId = if (removeImported) 704L else 702L
            repository.runTransactionForResult(
                InsertTemporaryBasalWithTempIdTransaction(basal(pumpId = null, temporaryId = tempId))
            ).blockingGet()
            repository.runTransactionForResult(SyncPumpTemporaryBasalTransaction(basal(pumpId = pumpId), null)).blockingGet()
            val removed = if (removeImported)
                db.temporaryBasalDao.findByPumpIds(pumpId, InterfaceIDs.PumpType.GENERIC_AAPS, "synthetic-pump")!!
            else db.temporaryBasalDao.findByPumpTempIds(tempId, InterfaceIDs.PumpType.GENERIC_AAPS, "synthetic-pump")!!
            repository.runTransactionForResult(InvalidateTemporaryBasalTransaction(removed.id)).blockingGet()

            repeat(2) {
                repository.runTransactionForResult(SyncTemporaryBasalWithTempIdTransaction(basal(pumpId = pumpId, temporaryId = tempId), null)).blockingGet()
                repository.runTransactionForResult(SyncPumpTemporaryBasalTransaction(basal(pumpId = pumpId), null)).blockingGet()
                reopenDatabase()
                assertThat(repository.getTemporaryBasalsStartingFromTime(start, true).blockingGet()).isEmpty()
                val canonical = db.temporaryBasalDao.findByPumpIds(pumpId, InterfaceIDs.PumpType.GENERIC_AAPS, "synthetic-pump")!!
                assertThat(canonical.isValid).isFalse()
                assertThat(canonical.interfaceIDs.temporaryId).isEqualTo(tempId)
            }
        }
    }

    @Test
    fun percentBasalMergePreservesUnitsAndOneNetInsulinContribution() {
        repository.runTransactionForResult(
            InsertTemporaryBasalWithTempIdTransaction(basal(pumpId = null, temporaryId = 702, rate = 120.0).copy(isAbsolute = false))
        ).blockingGet()
        val confirmed = basal(rate = 80.0).copy(isAbsolute = false)
        repository.runTransactionForResult(SyncPumpTemporaryBasalTransaction(confirmed, null)).blockingGet()
        repository.runTransactionForResult(
            SyncTemporaryBasalWithTempIdTransaction(confirmed.copy(interfaceIDs_backing = ids(802, 702)), null)
        ).blockingGet()
        reopenDatabase()

        val records = repository.getTemporaryBasalsStartingFromTimeToTime(start, start + duration, true).blockingGet()
        assertThat(records).hasSize(1)
        assertThat(records.single().isAbsolute).isFalse()
        assertThat(records.single().rate).isEqualTo(80.0)
        // At a synthetic scheduled basal of 1 U/h, 80% for 30 min is -0.1 U relative to
        // schedule. The IOB consumer sums a contribution from each row returned by this query.
        val netUnits = records.sumOf { (it.rate / 100.0 - 1.0) * it.duration / 3_600_000.0 }
        assertThat(netUnits).isWithin(1e-12).of(-0.1)
    }

    @Test
    fun conflictingBasalBindingLeavesBothRecordsUnchanged() {
        repository.runTransactionForResult(
            InsertTemporaryBasalWithTempIdTransaction(basal(pumpId = null, temporaryId = 702))
        ).blockingGet()
        syncBasal()
        repository.runTransactionForResult(SyncTemporaryBasalWithTempIdTransaction(basal(temporaryId = 702), null)).blockingGet()
        repository.runTransactionForResult(
            InsertTemporaryBasalWithTempIdTransaction(basal(pumpId = null, temporaryId = 703))
        ).blockingGet()
        val before = repository.getTemporaryBasalsStartingFromTimeIncludingInvalid(start, true).blockingGet()

        assertThrows(IllegalStateException::class.java) {
            repository.runTransactionForResult(SyncTemporaryBasalWithTempIdTransaction(basal(temporaryId = 703), null)).blockingGet()
        }
        reopenDatabase()

        assertThat(repository.getTemporaryBasalsStartingFromTimeIncludingInvalid(start, true).blockingGet()).containsExactlyElementsIn(before)
    }

    @Test
    fun failedBasalMergeRollsBackBothRecordsAndCanBeRetriedAfterReopen() {
        repository.runTransactionForResult(
            InsertTemporaryBasalWithTempIdTransaction(basal(pumpId = null, temporaryId = 702))
        ).blockingGet()
        syncBasal()
        val importedId = db.temporaryBasalDao.findByPumpIds(802, InterfaceIDs.PumpType.GENERIC_AAPS, "synthetic-pump")!!.id
        val before = repository.getTemporaryBasalsStartingFromTimeIncludingInvalid(start, true).blockingGet()
        // Fail the second write, after the provisional invalidation, on actual SQLite.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_basal_merge BEFORE UPDATE ON temporaryBasals " +
                "WHEN OLD.id = $importedId BEGIN SELECT RAISE(ABORT, 'synthetic merge failure'); END"
        )
        try {
            assertThrows(RuntimeException::class.java) {
                repository.runTransactionForResult(SyncTemporaryBasalWithTempIdTransaction(basal(temporaryId = 702), null)).blockingGet()
            }
            assertThat(repository.getTemporaryBasalsStartingFromTimeIncludingInvalid(start, true).blockingGet()).containsExactlyElementsIn(before)
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_basal_merge")
        }
        reopenDatabase()
        repository.runTransactionForResult(SyncTemporaryBasalWithTempIdTransaction(basal(temporaryId = 702), null)).blockingGet()
        reopenDatabase()

        assertSingleBasal()
        assertThat(repository.getTemporaryBasalsStartingFromTime(start, true).blockingGet().single().id).isEqualTo(importedId)
    }

    @Test
    fun basalMergeNeverUsesAnotherPumpsRecordWithTheSamePumpId() {
        val otherPump = basal().copy(interfaceIDs_backing = ids(802, null).copy(pumpSerial = "other-synthetic-pump"))
        repository.runTransactionForResult(SyncPumpTemporaryBasalTransaction(otherPump, null)).blockingGet()
        val otherId = db.temporaryBasalDao.findByPumpIds(802, InterfaceIDs.PumpType.GENERIC_AAPS, "other-synthetic-pump")!!.id
        repository.runTransactionForResult(
            InsertTemporaryBasalWithTempIdTransaction(basal(pumpId = null, temporaryId = 702))
        ).blockingGet()
        repository.runTransactionForResult(SyncTemporaryBasalWithTempIdTransaction(basal(temporaryId = 702), null)).blockingGet()
        reopenDatabase()

        val other = db.temporaryBasalDao.findById(otherId)!!
        assertThat(other.isValid).isTrue()
        assertThat(other.interfaceIDs.temporaryId).isNull()
        val own = db.temporaryBasalDao.findByPumpIds(802, InterfaceIDs.PumpType.GENERIC_AAPS, "synthetic-pump")!!
        assertThat(own.id).isNotEqualTo(otherId)
        assertThat(own.interfaceIDs.temporaryId).isEqualTo(702L)
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
