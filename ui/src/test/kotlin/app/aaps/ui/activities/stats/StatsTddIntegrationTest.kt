package app.aaps.ui.activities.stats

import app.aaps.core.data.aps.BasalData
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
import java.util.TimeZone

class StatsTddIntegrationTest : TestBase() {
    @Test fun `real calculator uses the original cache keys and writes across exceptional midnights`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Havana"))
            for (text in listOf("2026-03-09T16:00:00Z", "2026-11-01T17:00:00Z", "2026-11-08T17:00:00Z")) {
                val now = Instant.parse(text).toEpochMilli()
                val persistence = mock<PersistenceLayer>()
                val profileFunction = mock<ProfileFunction>()
                whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
                whenever(profileFunction.getProfile(any())).thenReturn(mock<Profile>())
                val iob = mock<IobCobCalculator>()
                whenever(iob.getBasalData(any(), any())).thenAnswer { BasalData().apply { tempBasalAbsolute = 1.0 } }
                val active = mock<ActivePlugin>()
                whenever(active.activePump).thenReturn(mock<Pump>())
                val date = mock<DateUtil>()
                whenever(date.now()).thenReturn(now)
                val lookups = mutableListOf<Long>()
                val writes = mutableListOf<Long>()
                whenever(persistence.getCalculatedTotalDailyDose(any())).thenAnswer { lookups += it.arguments[0] as Long; null }
                whenever(persistence.getBolusesFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
                whenever(persistence.getCarbsFromTimeToTimeExpanded(any(), any(), any())).thenReturn(emptyList())
                whenever(persistence.getBgReadingsDataFromTimeToTime(any(), any(), eq(true))).thenReturn(emptyList())
                whenever(persistence.insertOrUpdateCachedTotalDailyDose(any())).thenAnswer {
                    writes += (it.arguments[0] as TDD).timestamp
                    Single.just(PersistenceLayer.TransactionResult<TDD>())
                }
                val calculator = TddCalculatorImpl(aapsLogger, mock<ResourceHelper>(), active, profileFunction, date, iob, persistence)
                val expected = calculator.calculate(now, 7, true)!!
                val expectedLookups = lookups.toList()
                val expectedWrites = writes.toList()
                lookups.clear(); writes.clear()
                val util = mock<ProfileUtil>()
                whenever(util.convertToMgdl(any(), eq(GlucoseUnit.MGDL))).thenAnswer { it.arguments[0] }
                val preferences = mock<Preferences>()
                whenever(preferences.get(UnitDoubleKey.OverviewLowMark)).thenReturn(70.0)
                whenever(preferences.get(UnitDoubleKey.OverviewHighMark)).thenReturn(180.0)
                val state = StatsLoader(persistence, profileFunction, util, preferences, date, calculator).load(7).toList().blockingGet().last()
                assertThat(lookups).containsExactlyElementsIn(expectedLookups).inOrder()
                assertThat(writes).containsExactlyElementsIn(expectedWrites).inOrder()
                assertThat(writes.all { it < now }).isTrue()
                assertThat(state.insulinDays).isEqualTo(expected.size())
                assertThat(state.avgTdd).isEqualTo("24.0 U")
            }
        } finally {
            TimeZone.setDefault(original)
        }
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
            val state = StatsLoader(persistence, profiles, util, preferences, date, calculator).load(7).toList().blockingGet().last()
            assertThat(calls).containsExactly(oldest)
            assertThat(state.insulinDays).isEqualTo(6)
            assertThat(state.avgTdd).isEqualTo("30.0 U")
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
