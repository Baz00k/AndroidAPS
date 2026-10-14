package app.aaps.ui.activities.stats

import androidx.collection.LongSparseArray
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TDD
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

class StatsLoaderTest : TestBase() {
    @Mock lateinit var persistence: PersistenceLayer
    @Mock lateinit var profile: ProfileFunction
    @Mock lateinit var util: ProfileUtil
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var date: DateUtil
    @Mock lateinit var tdd: TddCalculator
    private lateinit var loader: StatsLoader
    private val now = Instant.parse("2026-10-14T12:00:00Z").toEpochMilli()

    @BeforeEach fun setup() {
        whenever(profile.getUnits()).thenReturn(GlucoseUnit.MGDL)
        whenever(preferences.get(UnitDoubleKey.OverviewLowMark)).thenReturn(70.0)
        whenever(preferences.get(UnitDoubleKey.OverviewHighMark)).thenReturn(180.0)
        whenever(util.convertToMgdl(any(), eq(GlucoseUnit.MGDL))).thenAnswer { it.arguments[0] }
        whenever(date.now()).thenReturn(now)
        whenever(persistence.getBgReadingsDataFromTimeToTime(any(), any(), eq(true))).thenReturn(emptyList())
        loader = StatsLoader(persistence, profile, util, preferences, date, tdd)
    }

    @Test fun `each range queries glucose once and visits every completed day independently`() {
        for (days in listOf(7, 30, 90)) {
            val ends = mutableListOf<Long>()
            whenever(tdd.calculate(any(), eq(1L), eq(true))).thenAnswer {
                ends += it.arguments[0] as Long
                LongSparseArray<TDD>()
            }
            val states = loader.load(days).toList().blockingGet()
            assertThat(states).hasSize(2)
            assertThat(states.first().insulinLoading).isTrue()
            assertThat(states.last().insulinLoading).isFalse()
            assertThat(states.last().rangeDays).isEqualTo(days)
            val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            assertThat(ends).containsExactlyElementsIn((0 until days).map { today.minusDays(it.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }).inOrder()
            val from = minOf(now - days * 86_400_000L, today.minusDays(days.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            verify(persistence).getBgReadingsDataFromTimeToTime(from, now, true)
        }
    }

    @Test fun `missing days do not prevent later cached history and average uses only available days`() {
        var calls = 0
        whenever(tdd.calculate(any(), eq(1L), eq(true))).thenAnswer {
            calls++
            LongSparseArray<TDD>().apply { if (calls == 3) put(1, TDD(timestamp = 1, totalAmount = 30.0)) }
        }
        val states = loader.load(7).toList().blockingGet()
        assertThat(calls).isEqualTo(7)
        assertThat(states.last().insulinDays).isEqualTo(1)
        verify(tdd).averageTDD(org.mockito.kotlin.argThat { size() == 1 && valueAt(0).totalAmount == 30.0 })
    }

    @Test fun `cancellation after glucose avoids all insulin work`() {
        loader.load(90).take(1).test().assertComplete().assertValue { !it.loading && it.insulinLoading }
        org.mockito.kotlin.verify(tdd, org.mockito.kotlin.never()).calculate(any(), any(), any())
    }

    @Test fun `insulin failure emits recoverable partial state before reporting error`() {
        whenever(tdd.calculate(any(), eq(1L), eq(true))).thenThrow(IllegalStateException("history"))
        val observer = loader.load(30).test()
        observer.assertError(IllegalStateException::class.java).assertValueCount(2)
        assertThat(observer.values().last().insulinFailed).isTrue()
        assertThat(observer.values().last().loading).isFalse()
    }

    @Test fun `day endpoints follow local calendar across DST`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            whenever(date.now()).thenReturn(Instant.parse("2026-03-10T12:00:00Z").toEpochMilli())
            val ends = mutableListOf<Long>()
            whenever(tdd.calculate(any(), eq(1L), eq(true))).thenAnswer { ends += it.arguments[0] as Long; LongSparseArray<TDD>() }
            loader.load(7).blockingSubscribe()
            assertThat(ends[1] - ends[2]).isEqualTo(23 * 3_600_000L)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun `midnight DST does not split the established TDD request`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Havana"))
            val timestamp = Instant.parse("2026-03-09T16:00:00Z").toEpochMilli()
            whenever(date.now()).thenReturn(timestamp)
            loader.load(7).blockingSubscribe()
            verify(tdd).calculate(timestamp, 7, true)
            org.mockito.kotlin.verify(tdd, org.mockito.kotlin.never()).calculate(any(), eq(1L), eq(true))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
