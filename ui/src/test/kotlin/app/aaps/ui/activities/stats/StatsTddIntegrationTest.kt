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
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class StatsTddIntegrationTest : TestBase() {
    /**
     * The real calculator over mocked records: 1 U/h basal from a profile that exists at any time, so a
     * day's total is its length in hours. Every cache lookup would answer a stale 99 U.
     */
    private class Fixture(test: TestBase, val now: Long, oldestBolus: Long? = now - 365L * 86_400_000L, profileFrom: Long = Long.MIN_VALUE) {
        val persistence = mock<PersistenceLayer>()
        val loader: StatsLoader

        init {
            val profileFunction = mock<ProfileFunction>()
            whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
            val profile = mock<Profile>()
            whenever(profileFunction.getProfile(any())).thenAnswer { if (it.arguments[0] as Long >= profileFrom) profile else null }
            val iob = mock<IobCobCalculator>()
            whenever(iob.getBasalData(any(), any())).thenAnswer { BasalData().apply { tempBasalAbsolute = 1.0 } }
            val active = mock<ActivePlugin>()
            whenever(active.activePump).thenReturn(mock<Pump>())
            val date = mock<DateUtil>()
            whenever(date.now()).thenReturn(now)
            whenever(persistence.getCalculatedTotalDailyDose(any())).thenAnswer {
                TDD(timestamp = it.arguments[0] as Long, totalAmount = 99.0).apply { ids.pumpType = PumpType.CACHE }
            }
            whenever(persistence.getBolusesFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
            whenever(persistence.getCarbsFromTimeToTimeExpanded(any(), any(), any())).thenReturn(emptyList())
            whenever(persistence.getBgReadingsDataFromTimeToTime(any(), any(), eq(true))).thenReturn(emptyList())
            whenever(persistence.getOldestBolus()).thenReturn(oldestBolus?.let { BS(timestamp = it, amount = 1.0, type = BS.Type.NORMAL) })
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

    private fun hours(date: LocalDate, zone: ZoneId) =
        Duration.between(date.atStartOfDay(zone), date.plusDays(1).atStartOfDay(zone)).toHours().toDouble()

    @Test fun `each total covers its calendar day and the shared cache is neither read nor written`() {
        // Havana skips midnight on 2026-03-08 (23 hours) and repeats it on 2026-11-01 (25 hours); Warsaw's 2026-03-29 has 23 hours.
        val cases = listOf("America/Havana" to "2026-03-09T16:00:00Z", "America/Havana" to "2026-11-01T17:00:00Z",
                           "America/Havana" to "2026-11-08T17:00:00Z", "Europe/Warsaw" to "2026-03-30T10:00:00Z")
        val states = HashMap<String, StatsUiState>()
        for ((zoneId, text) in cases) inZone(zoneId) {
            val zone = ZoneId.of(zoneId)
            val fixture = Fixture(this, Instant.parse(text).toEpochMilli())
            val state = fixture.loader.load(7).toList().blockingGet().last().also { states[text] = it }
            // The cycle history is long; one run across both Havana transitions is enough.
            val cycle = if (text.startsWith("2026-11-08")) fixture.loader.loadCycle().toList().blockingGet().last().totals!! else emptyMap()
            verify(fixture.persistence, never()).getCalculatedTotalDailyDose(any())
            verify(fixture.persistence, never()).insertOrUpdateCachedTotalDailyDose(any())
            assertThat(state.insulinDays).isEqualTo(7)
            state.insulinRows.forEach { assertThat(it.total).isWithin(1e-6).of(hours(it.date, zone)) }
            cycle.forEach { (date, total) -> assertThat(total).isWithin(1e-6).of(hours(date, zone)) }
            if (cycle.isNotEmpty()) assertThat(cycle).hasSize(CYCLE_HISTORY_DAYS)
        }
        fun total(text: String, date: LocalDate) = states.getValue(text).insulinRows.single { it.date == date }.total
        assertThat(total("2026-03-09T16:00:00Z", LocalDate.of(2026, 3, 8))).isWithin(1e-6).of(23.0)
        assertThat(total("2026-11-08T17:00:00Z", LocalDate.of(2026, 11, 1))).isWithin(1e-6).of(25.0)
        assertThat(total("2026-03-30T10:00:00Z", LocalDate.of(2026, 3, 29))).isWithin(1e-6).of(23.0)
        // At noon on the day midnight repeats, 13 hours have passed since the first midnight.
        assertThat(states.getValue("2026-11-01T17:00:00Z").insulinToday!!.total).isWithin(1e-6).of(13.0)
    }

    @Test fun `missing history gives no total instead of a profile-only or partial one`() = inZone("UTC") {
        val now = Instant.parse("2026-10-14T12:00:00Z").toEpochMilli()
        // Cleanup removed everything before 2026-07-13 10:00 but kept the profile, which would supply basal for any day.
        val retained = Fixture(this, now, Instant.parse("2026-07-13T10:00:00Z").toEpochMilli()).loader.loadCycle().toList().blockingGet().last().totals!!
        assertThat(retained.keys.minOrNull()).isEqualTo(LocalDate.of(2026, 7, 14))
        assertThat(retained).hasSize(92)
        // With no treatment records at all, no past day is calculated.
        val empty = Fixture(this, now, oldestBolus = null).loader.load(7).toList().blockingGet().last()
        assertThat(empty.insulinDays).isEqualTo(0)
        assertThat(empty.insulinRows.none { it.recorded }).isTrue()
        // No profile for the first six hours of today: no total rather than six hours short.
        val partial = Fixture(this, now, profileFrom = Instant.parse("2026-10-14T06:00:00Z").toEpochMilli()).loader.load(7).toList().blockingGet().last()
        assertThat(partial.insulinToday).isNull()
        assertThat(empty.insulinToday!!.total).isWithin(1e-6).of(12.0)
    }
}
