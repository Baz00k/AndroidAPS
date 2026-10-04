package app.aaps.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aaps.database.di.DatabaseModule
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.Carbs
import app.aaps.database.entities.GlucoseValue
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.TherapyEvent
import app.aaps.database.entities.data.GlucoseUnit
import app.aaps.database.entities.embedments.InterfaceIDs
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MigrationPreservesTherapyRecordsTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "migration-history-${UUID.randomUUID()}.db"
    private val schemaDatabaseName = "$databaseName-schema"
    // Fixed instants straddle the Warsaw spring jump; this tests storage, not local-time calculations.
    private val before = Instant.parse("2026-03-29T00:55:00Z").toEpochMilli()
    private val after = Instant.parse("2026-03-29T01:05:00Z").toEpochMilli()

    @After
    fun deleteDatabase() {
        context.deleteDatabase(databaseName)
        context.deleteDatabase(schemaDatabaseName)
    }

    @Test
    fun oldestExportedSchemaPreservesTherapyRecords() {
        helper.createDatabase(databaseName, 22).use { old ->
            // Raw SQL freezes the old schema input independently of today's entities and insert paths.
            old.execSQL(
                """INSERT INTO boluses
                    (id, version, dateCreated, isValid, timestamp, utcOffset, amount, type, notes,
                     isBasalInsulin, pumpType, pumpSerial, pumpId, temporaryId)
                    VALUES (1, 2, $before, 1, $before, 3600000, 1.4, 'NORMAL', 'synthetic bolus',
                            0, 'GENERIC_AAPS', 'synthetic-pump', 801, 701)"""
            )
            old.execSQL(
                """INSERT INTO boluses
                    (id, version, dateCreated, isValid, referenceId, timestamp, utcOffset, amount, type,
                     isBasalInsulin, pumpType, pumpSerial, pumpId, temporaryId)
                    VALUES (2, 1, $before, 1, 1, $before, 3600000, 2.0, 'NORMAL',
                            0, 'GENERIC_AAPS', 'synthetic-pump', 801, 701)"""
            )
            old.execSQL(
                """INSERT INTO carbs
                    (id, version, dateCreated, isValid, timestamp, utcOffset, duration, amount, notes)
                    VALUES (1, 0, $after, 0, $after, 7200000, 0, 12.0, 'synthetic carbs')"""
            )
            old.execSQL(
                """INSERT INTO temporaryBasals
                    (id, version, dateCreated, isValid, timestamp, utcOffset, type, isAbsolute, rate,
                     duration, pumpType, pumpSerial, pumpId)
                    VALUES (1, 0, $before, 1, $before, 3600000, 'NORMAL', 1, 0.8,
                            1800000, 'GENERIC_AAPS', 'synthetic-pump', 802)"""
            )
            old.execSQL(
                """INSERT INTO glucoseValues
                    (id, version, dateCreated, isValid, timestamp, utcOffset, raw, value, trendArrow, noise, sourceSensor)
                    VALUES (1, 0, $after, 1, $after, 7200000, 100.0, 108.0, 'FLAT', 1.0, 'UNKNOWN')"""
            )
            old.execSQL(
                """INSERT INTO therapyEvents
                    (id, version, dateCreated, isValid, timestamp, utcOffset, duration, type, note,
                     enteredBy, glucose, glucoseType, glucoseUnit)
                    VALUES (1, 0, $after, 1, $after, 7200000, 0, 'FINGER_STICK_BG_VALUE', 'synthetic calibration',
                            'fixture', 6.0, 'FINGER', 'MMOL')"""
            )
        }

        // Validate the full schema separately so the populated database still upgrades through the production builder.
        helper.createDatabase(schemaDatabaseName, 22).close()
        helper.runMigrationsAndValidate(schemaDatabaseName, DATABASE_VERSION, true, *DatabaseModule().migrations).close()
        val db = DatabaseModule().provideAppDatabase(context, databaseName)
        try {
            assertThat(db.bolusDao.findById(1)).isEqualTo(
                Bolus(
                    id = 1, version = 2, dateCreated = before, timestamp = before, utcOffset = 3_600_000,
                    amount = 1.4, type = Bolus.Type.NORMAL, notes = "synthetic bolus", interfaceIDs_backing = bolusIds()
                )
            )
            assertThat(db.bolusDao.findById(2)).isEqualTo(
                Bolus(
                    id = 2, version = 1, dateCreated = before, referenceId = 1, timestamp = before, utcOffset = 3_600_000,
                    amount = 2.0, type = Bolus.Type.NORMAL, interfaceIDs_backing = bolusIds()
                )
            )
            assertThat(db.carbsDao.findById(1)).isEqualTo(
                Carbs(
                    id = 1, dateCreated = after, isValid = false, timestamp = after, utcOffset = 7_200_000,
                    duration = 0, amount = 12.0, notes = "synthetic carbs"
                )
            )
            assertThat(db.temporaryBasalDao.findById(1)).isEqualTo(
                TemporaryBasal(
                    id = 1, dateCreated = before, timestamp = before, utcOffset = 3_600_000,
                    type = TemporaryBasal.Type.NORMAL, isAbsolute = true, rate = 0.8, duration = 1_800_000,
                    interfaceIDs_backing = InterfaceIDs(pumpType = InterfaceIDs.PumpType.GENERIC_AAPS, pumpSerial = "synthetic-pump", pumpId = 802)
                )
            )
            assertThat(db.glucoseValueDao.findById(1)).isEqualTo(
                GlucoseValue(
                    id = 1, dateCreated = after, timestamp = after, utcOffset = 7_200_000,
                    raw = 100.0, value = 108.0, trendArrow = GlucoseValue.TrendArrow.FLAT, noise = 1.0,
                    sourceSensor = GlucoseValue.SourceSensor.UNKNOWN, interfaceIDs_backing = null
                )
            )
            assertThat(db.therapyEventDao.findById(1)).isEqualTo(
                TherapyEvent(
                    id = 1, dateCreated = after, timestamp = after, utcOffset = 7_200_000,
                    type = TherapyEvent.Type.FINGER_STICK_BG_VALUE, note = "synthetic calibration", enteredBy = "fixture",
                    glucose = 6.0, glucoseType = TherapyEvent.MeterType.FINGER, glucoseUnit = GlucoseUnit.MMOL
                )
            )
            for ((table, count) in listOf("boluses" to 2, "carbs" to 1, "temporaryBasals" to 1, "glucoseValues" to 1, "therapyEvents" to 1)) {
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getInt(0)).isEqualTo(count)
                }
            }
            val repository = AppRepository(db)
            assertThat(repository.getBolusesDataFromTime(before, true).blockingGet().sumOf { it.amount }).isEqualTo(1.4)
        } finally {
            db.close()
        }
    }

    private fun bolusIds() = InterfaceIDs(
        pumpType = InterfaceIDs.PumpType.GENERIC_AAPS, pumpSerial = "synthetic-pump", pumpId = 801, temporaryId = 701
    )
}
