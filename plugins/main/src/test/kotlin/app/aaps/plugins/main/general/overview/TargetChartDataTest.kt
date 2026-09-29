package app.aaps.plugins.main.general.overview

import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.keys.StringKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class TargetChartDataTest : TestBaseWithProfile() {
    private val persistence: PersistenceLayer = mock()
    private val from = 1_000_000L
    private val step = 5 * 60_000L
    private lateinit var chart: TargetChartData

    private fun profile(low: Double, high: Double): Profile = mock<Profile>().also {
        whenever(it.getTargetLowMgdl(any())).thenReturn(low)
        whenever(it.getTargetHighMgdl(any())).thenReturn(high)
    }

    @BeforeEach fun setupChart() {
        val profile = profile(90.0, 110.0)
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(profileFunction.getProfile(any())).thenReturn(profile)
        whenever(persistence.getTemporaryTargetDataFromTime(from, true)).thenReturn(Single.just(emptyList()))
        whenever(persistence.getEffectiveProfileSwitchesFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        chart = TargetChartData(persistence, profileFunction, profileUtil)
    }

    @Test fun `profile switch preserves targets before the switch`() {
        val switchTime = from + step + 1
        val old = profile(90.0, 110.0)
        val current = profile(140.0, 160.0)
        val switch: EPS = mock()
        whenever(switch.timestamp).thenReturn(switchTime)
        whenever(switch.isValid).thenReturn(true)
        whenever(persistence.getEffectiveProfileSwitchesFromTimeToTime(from, from + 3 * step, true)).thenReturn(listOf(switch))
        whenever(profileFunction.getProfile()).thenReturn(current)
        whenever(profileFunction.getProfile(any())).thenAnswer { if (it.getArgument<Long>(0) < switchTime) old else current }

        assertThat(chart.build(from, from + 3 * step).map { it.value }).containsExactly(100.0, 100.0, 150.0, 150.0).inOrder()
        verify(profileFunction).getProfile(from)
        verify(profileFunction).getProfile(switchTime)
        verify(profileFunction, times(2)).getProfile(any())
        verify(profileFunction, never()).getProfile()
    }

    @Test fun `24 hour chart uses bounded target and profile lookups`() {
        val points = chart.build(from, from + 24 * 60 * 60_000L)

        assertThat(points).hasSize(289)
        verify(persistence, times(1)).getTemporaryTargetActiveAt(any())
        verify(persistence).getTemporaryTargetDataFromTime(from, true)
        verify(profileFunction, times(1)).getProfile(any())
        verify(persistence).getEffectiveProfileSwitchesFromTimeToTime(from, from + 24 * 60 * 60_000L, true)
    }

    private fun target(start: Long, duration: Long, low: Double = 140.0, high: Double = 160.0) = TT(
        timestamp = start, duration = duration, lowTarget = low, highTarget = high, reason = TT.Reason.ACTIVITY
    )

    @Test fun `target overlapping window start expires back to historical profile`() {
        val active = target(from - step, 2 * step)
        whenever(persistence.getTemporaryTargetActiveAt(from)).thenReturn(active)

        assertThat(chart.build(from, from + 2 * step).map { it.value }).containsExactly(150.0, 100.0, 100.0).inOrder()
    }

    @Test fun `batch resolves target starts replacements cancellation and invalid entries`() {
        val first = target(from + step, step)
        val replacement = target(from + 2 * step, step, 108.0, 108.0)
        val invalid = target(from, 10 * step, 200.0, 200.0).copy(isValid = false)
        val future = target(from + 10 * step, step)
        whenever(persistence.getTemporaryTargetDataFromTime(from, true))
            .thenReturn(Single.just(listOf(invalid, first, replacement, future)))

        assertThat(chart.build(from, from + 4 * step).map { it.value }).containsExactly(100.0, 150.0, 108.0, 100.0, 100.0).inOrder()
    }

    @Test fun `missing historical profile does not borrow current profile but permits temporary targets`() {
        whenever(profileFunction.getProfile(from)).thenReturn(null)
        whenever(persistence.getTemporaryTargetDataFromTime(from, true))
            .thenReturn(Single.just(listOf(target(from + step, step))))

        val points = chart.build(from, from + 3 * step)
        assertThat(points.map { it.time }).containsExactly(from + step)
        assertThat(points.map { it.value }).containsExactly(150.0)
        verify(profileFunction, never()).getProfile()
    }

    @Test fun `profile schedule blocks are evaluated at each sample and endpoint is included`() {
        val scheduled = profile(90.0, 110.0)
        whenever(scheduled.getTargetLowMgdl(any())).thenAnswer { if (it.getArgument<Long>(0) < from + step) 90.0 else 110.0 }
        whenever(scheduled.getTargetHighMgdl(any())).thenAnswer { if (it.getArgument<Long>(0) < from + step) 110.0 else 130.0 }
        whenever(profileFunction.getProfile(from)).thenReturn(scheduled)

        val points = chart.build(from, from + step + 60_000L)
        assertThat(points.map { it.time }).containsExactly(from, from + step, from + step + 60_000L).inOrder()
        assertThat(points.map { it.value }).containsExactly(100.0, 120.0, 120.0).inOrder()
    }

    @Test fun `midpoints are converted once to mmol`() {
        whenever(preferences.get(StringKey.GeneralUnits)).thenReturn(GlucoseUnit.MMOL.asText)
        whenever(persistence.getTemporaryTargetActiveAt(from)).thenReturn(target(from, 2 * step, 108.0, 144.0))

        assertThat(chart.build(from, from + step).map { it.value }).containsExactly(7.0, 7.0)
    }

    @Test fun `future reference follows profile schedule after active temporary target expires`() {
        val now = from + step
        val scheduled = profile(90.0, 110.0)
        whenever(scheduled.getTargetLowMgdl(any())).thenAnswer { if (it.getArgument<Long>(0) < now + 2 * step) 90.0 else 110.0 }
        whenever(scheduled.getTargetHighMgdl(any())).thenAnswer { if (it.getArgument<Long>(0) < now + 2 * step) 110.0 else 130.0 }
        whenever(profileFunction.getProfile(from)).thenReturn(scheduled)
        whenever(persistence.getTemporaryTargetActiveAt(from)).thenReturn(target(from, 2 * step))

        val points = chart.build(from, now + 4 * step)
        assertThat(points.filter { it.time >= now }.map { it.value })
            .containsExactly(150.0, 100.0, 120.0, 120.0, 120.0).inOrder()
    }

    @Test fun `empty or reversed window performs no provider queries`() {
        assertThat(chart.build(from, from)).isEmpty()
        assertThat(chart.build(from, from - 1)).isEmpty()
        verifyNoInteractions(persistence, profileFunction)
    }
}
