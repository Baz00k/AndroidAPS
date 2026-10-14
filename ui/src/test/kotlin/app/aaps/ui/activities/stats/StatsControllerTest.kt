package app.aaps.ui.activities.stats

import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.PublishSubject
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** No database, pump or therapy settings: the loader is a mock and both threads are test schedulers. */
class StatsControllerTest {
    private val io = TestScheduler()
    private val main = TestScheduler()
    private val history = PublishSubject.create<Long>()
    private val loader = mock<StatsLoader>()
    private var loads = 0
    private val controller = StatsController(loader, history, io, main, {})

    init {
        whenever(loader.load(any())).thenAnswer { Observable.just(StatsUiState(loading = false, rangeDays = 7, avgGlucose = "load ${++loads}")) }
        whenever(loader.loadCycle()).thenAnswer { Observable.just(CycleLoad(CYCLE_HISTORY_DAYS, LocalDate.of(2026, 10, 13), emptyMap())) }
        whenever(loader.beforeToday(any())).thenReturn(true)
    }

    private fun run() {
        io.triggerActions()
        main.triggerActions()
    }

    @Test fun `a history burst invalidates from its oldest change once and refreshes without blanking the screen`() {
        controller.start(); run()
        assertThat(controller.stats.value.avgGlucose).isEqualTo("load 1")
        history.onNext(3_000)
        io.advanceTimeBy(5, TimeUnit.SECONDS)
        history.onNext(1_000)
        io.advanceTimeBy(5, TimeUnit.SECONDS)
        history.onNext(2_000)
        io.advanceTimeBy(HISTORY_SETTLE_SECONDS - 1, TimeUnit.SECONDS)
        verify(loader, never()).clearCachedTdd(any())
        io.advanceTimeBy(1, TimeUnit.SECONDS)
        main.triggerActions()
        io.triggerActions()
        verify(loader, times(1)).clearCachedTdd(1_000)
        verify(loader, times(1)).load(any())
        // The refresh starts only after the cache is invalidated, and the old figures stay until it lands.
        main.triggerActions()
        assertThat(controller.stats.value.loading).isFalse()
        assertThat(controller.stats.value.avgGlucose).isEqualTo("load 1")
        run()
        assertThat(controller.stats.value.avgGlucose).isEqualTo("load 2")
    }

    @Test fun `a history change stops running calculations before clearing the cache, then restarts them`() {
        val order = mutableListOf<String>()
        whenever(loader.loadCycle()).thenAnswer { Observable.never<CycleLoad>().doOnDispose { order += "cycle stopped" } }
        whenever(loader.clearCachedTdd(any())).thenAnswer { order += "cache cleared"; Unit }
        controller.start(); run()
        controller.showCycle(); run()
        history.onNext(1_000)
        io.advanceTimeBy(HISTORY_SETTLE_SECONDS, TimeUnit.SECONDS)
        main.triggerActions()
        assertThat(controller.cycle.value.loading).isFalse()
        run()
        assertThat(order).containsExactly("cycle stopped", "cache cleared").inOrder()
        verify(loader, times(2)).loadCycle()
        assertThat(controller.cycle.value.loading).isTrue()
    }

    @Test fun `a cache that cannot be invalidated marks the figures as out of date`() {
        doAnswer { throw IllegalStateException("database") }.whenever(loader).clearCachedTdd(any())
        controller.start(); run()
        history.onNext(1_000)
        io.advanceTimeBy(HISTORY_SETTLE_SECONDS, TimeUnit.SECONDS)
        run(); run()
        assertThat(controller.stats.value.refreshFailed).isTrue()
        assertThat(controller.stats.value.avgGlucose).isEqualTo("load 1")
        controller.select(7); run()
        assertThat(controller.stats.value.refreshFailed).isFalse()
    }

    @Test fun `history changes while paused are ignored`() {
        controller.start(); run()
        controller.stop()
        history.onNext(1_000)
        io.advanceTimeBy(1, TimeUnit.MINUTES); run()
        verify(loader, never()).clearCachedTdd(any())
    }

    @Test fun `recalculation runs once, then reloads totals and the requested cycle pattern`() {
        controller.start(); run()
        controller.showCycle(); run()
        controller.recalculate()
        controller.recalculate()
        assertThat(controller.recalculation.value).isEqualTo(Recalculation.RUNNING)
        run(); run()
        verify(loader, times(1)).recalculateTdd()
        verify(loader, times(2)).loadCycle()
        assertThat(controller.recalculation.value).isEqualTo(Recalculation.IDLE)
        assertThat(controller.stats.value.avgGlucose).isEqualTo("load 2")
    }

    @Test fun `failed recalculation is reported and leaves the figures alone`() {
        doAnswer { throw IllegalStateException("database") }.whenever(loader).recalculateTdd()
        controller.start(); run()
        controller.recalculate(); run()
        assertThat(controller.recalculation.value).isEqualTo(Recalculation.FAILED)
        assertThat(controller.stats.value.avgGlucose).isEqualTo("load 1")
    }

    @Test fun `cycle offset is recomputed from loaded totals without reading history again`() {
        val newest = LocalDate.of(2026, 10, 13)
        val totals = (0 until CYCLE_HISTORY_DAYS).associate { newest.minusDays(it.toLong()) to 30.0 }
        whenever(loader.loadCycle()).thenReturn(Observable.just(CycleLoad(CYCLE_HISTORY_DAYS, newest, totals)))
        controller.showCycle(); run()
        assertThat(controller.cycle.value.pattern!!.firstDay).isEqualTo(newest.minusDays(27))
        controller.setCycleOffset(30)
        assertThat(controller.cycle.value.offset).isEqualTo(CYCLE_DAYS - 1)
        assertThat(controller.cycle.value.pattern!!.firstDay).isEqualTo(newest.minusDays(54))
        verify(loader, times(1)).loadCycle()
    }

    @Test fun `an alignment without enough totals keeps its day 1 so the shift can be undone`() {
        val newest = LocalDate.of(2026, 10, 13)
        // 49 days: two cycles at offset 0, but only 20 days in the older one after shifting by a day.
        val totals = (0 until 49).associate { newest.minusDays(it.toLong()) to 30.0 }
        whenever(loader.loadCycle()).thenReturn(Observable.just(CycleLoad(CYCLE_HISTORY_DAYS, newest, totals)))
        controller.showCycle(); run()
        controller.setCycleOffset(1)
        assertThat(controller.cycle.value.pattern).isNull()
        assertThat(controller.cycle.value.firstDay).isEqualTo(newest.minusDays(28))
        controller.setCycleOffset(0)
        assertThat(controller.cycle.value.pattern).isNotNull()
    }
}
