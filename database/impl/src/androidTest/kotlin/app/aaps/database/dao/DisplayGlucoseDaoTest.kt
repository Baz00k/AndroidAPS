package app.aaps.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.aaps.database.AppDatabase
import app.aaps.database.entities.GlucoseValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayGlucoseDaoTest {
    @Test fun latestUsableDisplayReadingIsBoundedAndDeterministic() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.glucoseValueDao
            fun reading(time: Long, value: Double) = GlucoseValue(
                timestamp = time, raw = value, value = value, trendArrow = GlucoseValue.TrendArrow.FLAT,
                noise = null, sourceSensor = GlucoseValue.SourceSensor.UNKNOWN
            )
            val first = dao.insert(reading(1000, 120.0))
            val winner = dao.insert(reading(1000, 151.0))
            dao.insert(reading(1100, 38.0))
            dao.insert(reading(1200, 180.0).copy(isValid = false))
            dao.insert(reading(1300, Double.POSITIVE_INFINITY))
            dao.insert(reading(1400, 200.0).copy(referenceId = first))
            dao.insert(reading(2000, 190.0)) // future relative to query
            assertEquals(winner, dao.getLastUsableForDisplay(1500).blockingGet()?.id)
            assertEquals(151.0, dao.getLastUsableForDisplay(1500).blockingGet()?.value)
            assertNull(dao.getLastUsableForDisplay(999).blockingGet())
        } finally {
            db.close()
        }
    }
}
