package app.aaps.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aaps.database.AppDatabase
import app.aaps.database.AppRepository
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.Carbs
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.TotalDailyDose
import app.aaps.database.entities.embedments.InterfaceIDs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The Statistics "recalculate" action: only derived daily totals may go, never what they are derived from. */
@RunWith(AndroidJUnit4::class)
class ClearCachedTddTest {

    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        .allowMainThreadQueries().build()

    @After fun close() = database.close()

    @Test
    fun clearingTheCacheKeepsPumpTotalsAndTherapyRecords() {
        val day = 1_760_000_000_000L
        val cached = database.totalDailyDoseDao.insert(tdd(day, InterfaceIDs.PumpType.CACHE))
        val pump = database.totalDailyDoseDao.insert(tdd(day, InterfaceIDs.PumpType.GENERIC_AAPS))
        val bolus = database.bolusDao.insert(Bolus(timestamp = day + 1, amount = 1.5, type = Bolus.Type.NORMAL))
        val carbs = database.carbsDao.insert(Carbs(timestamp = day + 2, duration = 0, amount = 20.0))
        val basal = database.temporaryBasalDao.insert(
            TemporaryBasal(timestamp = day + 3, duration = 1_800_000, type = TemporaryBasal.Type.NORMAL, isAbsolute = true, rate = 0.8)
        )

        AppRepository(database).clearCachedTddData(0)

        assertNull(database.totalDailyDoseDao.findById(cached))
        assertEquals(20.0, database.totalDailyDoseDao.findById(pump)!!.totalAmount, 0.0)
        assertEquals(1.5, database.bolusDao.findById(bolus)!!.amount, 0.0)
        assertEquals(20.0, database.carbsDao.findById(carbs)!!.amount, 0.0)
        assertEquals(0.8, database.temporaryBasalDao.findById(basal)!!.rate, 0.0)
    }

    private fun tdd(timestamp: Long, type: InterfaceIDs.PumpType) =
        TotalDailyDose(timestamp = timestamp, basalAmount = 8.0, bolusAmount = 12.0, totalAmount = 20.0).apply {
            interfaceIDs.pumpType = type
            interfaceIDs.pumpSerial = "test-serial"
        }
}
