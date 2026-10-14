package app.aaps.ui.activities.stats

import app.aaps.core.data.aps.BasalData
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TDD
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.stats.TddCalculatorImpl
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

class StatsTddIntegrationTest : TestBase() {
    /** The real calculator over mocked records: 1 U/h basal from a profile that exists at any time. */
    private class Fixture(test: TestBase, val now: Long, oldestBolus: Long? = null) {
        val persistence = mock<PersistenceLayer>()
        val writes = mutableListOf<Long>()
        val loader: StatsLoader

        init {
            val profileFunction = mock<ProfileFunction>()
            whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
            whenever(profileFunction.getProfile(any())).thenReturn(mock<Profile>())
            val iob = mock<IobCobCalculator>()
            whenever(iob.getBasalData(any(), any())).thenAnswer { BasalData().apply { tempBasalAbsolute = 1.0 } }
            val active = mock<ActivePlugin>()
            whenever(active.activePump).thenReturn(mock<Pump>())
            val date = mock<DateUtil>()
            whenever(date.now()).thenReturn(now)
            whenever(persistence.getCalculatedTotalDailyDose(any())).thenReturn(null)
            whenever(persistence.getBolusesFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
            whenever(persistence.getCarbsFromTimeToTimeExpanded(any(), any(), any())).thenReturn(emptyList())
            whenever(persistence.getBgReadingsDataFromTimeToTime(any(), any(), eq(true))).thenReturn(emptyList())
            whenever(persistence.getOldestBolus()).thenReturn(oldestBolus?.let { BS(timestamp = it, amount = 1.0, type = BS.Type.NORMAL) })
            whenever(persistence.insertOrUpdateCachedTotalDailyDose(any())).thenAnswer {
                writes += (it.arguments[0] as TDD).timestamp
                Single.just(PersistenceLayer.TransactionResult<TDD>())
            }
            val calculator = TddCalculatorImpl(test.aapsLogger, mock<ResourceHelper>(), active, profileFunction, date, iob, persistence)
            val util = mock<ProfileUtil>()
            whenever(util.convertToMgdl(any(), eq(GlucoseUnit.MGDL))).thenAnswer { it.arguments[0] }
            val preferences = mock<Preferences>()
            whenever(preferences.get(UnitDoubleKey.OverviewLowMark)).thenReturn(70.0)
            whenever(preferences.get(UnitDoubleKey.OverviewHighMark)).thenReturn(180.0)
            loader = StatsLoader(persistence, profileFunction, util, preferences, date, calculator, mock())
        }
    }

    private fun inZone(zone: String, block: () -> Unit) {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            MidnightTime.resetCache()
            block()
        } finally {
            TimeZone.setDefault(original)
            MidnightTime.resetCache()
        }
    }

    @Test fun `days with a skipped or repeated midnight are missing rather than cached past the end of yesterday`() = inZone("America/Havana") {
        // Havana skips midnight on 2026-03-08 and repeats it on 2026-11-01. A whole-range request starting at
        // either one ends an hour into today, and the calculator then caches today's unfinished total.
        for (text in listOf("2026-03-09T16:00:00Z", "2026-03-15T16:00:00Z", "2026-09-19T16:00:00Z", "2026-11-01T17:00:00Z", "2026-11-08T17:00:00Z")) {
            val fixture = Fixture(this, Instant.parse(text).toEpochMilli())
            val zone = ZoneId.of("America/Havana")
            val todayStart = Instant.ofEpochMilli(fixture.now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            val state = fixture.loader.load(7).toList().blockingGet().last()
            val cycle = fixture.loader.loadCycle().toList().blockingGet().last()
            assertThat(fixture.writes).isNotEmpty()
            assertThat(fixture.writes.filter { it >= todayStart }).isEmpty()
            assertThat(fixture.writes.map { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }.distinct()).containsExactly(LocalTime.MIDNIGHT)
            assertThat(cycle.totals!!.keys.filter { !it.isBefore(Instant.ofEpochMilli(fixture.now).atZone(zone).toLocalDate()) }).isEmpty()
            if (text == "2026-03-15T16:00:00Z") {
                assertThat(state.insulinDays).isEqualTo(6)
                assertThat(state.insulinRows.single { !it.recorded }.date).isEqualTo(LocalDate.of(2026, 3, 8))
            }
            assertThat(state.avgTdd).isEqualTo("24.0 U")
        }
    }

    @Test fun `days older than the retained treatments are not rebuilt from the profile alone`() = inZone("UTC") {
        // Cleanup removed everything before 2026-07-13 10:00 but kept the profile, which would supply basal for any day.
        val fixture = Fixture(this, Instant.parse("2026-10-14T12:00:00Z").toEpochMilli(), Instant.parse("2026-07-13T10:00:00Z").toEpochMilli())
        val totals = fixture.loader.loadCycle().toList().blockingGet().last().totals!!
        assertThat(totals.keys.minOrNull()).isEqualTo(LocalDate.of(2026, 7, 14))
        assertThat(totals).hasSize(92)
        assertThat(fixture.writes.minOrNull()).isEqualTo(Instant.parse("2026-07-14T00:00:00Z").toEpochMilli())
    }

    @Test fun `missing oldest day does not recompute later cached days with real calculator`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val now = Instant.parse("2026-10-14T12:00:00Z").toEpochMilli()
            val oldest = Instant.parse("2026-10-07T00:00:00Z").toEpochMilli()
            val persistence = mock<PersistenceLayer>()
            val profiles = mock<ProfileFunction>()
            whenever(profiles.getUnits()).thenReturn(GlucoseUnit.MGDL)
            val calls = mutableListOf<Long>()
            whenever(profiles.getProfile(any())).thenAnswer { calls += it.arguments[0] as Long; null }
            whenever(persistence.getCalculatedTotalDailyDose(any())).thenAnswer {
                val timestamp = it.arguments[0] as Long
                if (timestamp == oldest) null else TDD(timestamp = timestamp, totalAmount = 30.0).apply { ids.pumpType = PumpType.CACHE }
            }
            whenever(persistence.getBolusesFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
            whenever(persistence.getCarbsFromTimeToTimeExpanded(any(), any(), any())).thenReturn(emptyList())
            whenever(persistence.getBgReadingsDataFromTimeToTime(any(), any(), eq(true))).thenReturn(emptyList())
            val date = mock<DateUtil>()
            whenever(date.now()).thenReturn(now)
            val calculator = TddCalculatorImpl(aapsLogger, mock(), mock(), profiles, date, mock(), persistence)
            val util = mock<ProfileUtil>()
            whenever(util.convertToMgdl(any(), eq(GlucoseUnit.MGDL))).thenAnswer { it.arguments[0] }
            val preferences = mock<Preferences>()
            whenever(preferences.get(UnitDoubleKey.OverviewLowMark)).thenReturn(70.0)
            whenever(preferences.get(UnitDoubleKey.OverviewHighMark)).thenReturn(180.0)
            val state = StatsLoader(persistence, profiles, util, preferences, date, calculator, mock()).load(7).toList().blockingGet().last()
            // Profile lookups from today onward are the partial "today so far" total, not a recomputed cached day.
            assertThat(calls.filter { it < Instant.parse("2026-10-14T00:00:00Z").toEpochMilli() }).containsExactly(oldest)
            assertThat(state.insulinDays).isEqualTo(6)
            assertThat(state.avgTdd).isEqualTo("30.0 U")
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
