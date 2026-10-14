package app.aaps.ui.activities.stats

import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.PublishSubject
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
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
        whenever(loader.newestCompletedDay()).thenReturn(LocalDate.of(2026, 10, 13))
    }

    private fun run() {
        io.triggerActions()
        main.triggerActions()
    }

    @Test fun `a history burst refreshes once without blanking the screen`() {
        controller.start(); run()
        assertThat(controller.stats.value.avgGlucose).isEqualTo("load 1")
        history.onNext(3_000)
        io.advanceTimeBy(5, TimeUnit.SECONDS)
        history.onNext(1_000)
        io.advanceTimeBy(5, TimeUnit.SECONDS)
        history.onNext(2_000)
        io.advanceTimeBy(HISTORY_SETTLE_SECONDS - 1, TimeUnit.SECONDS); run()
        verify(loader, times(1)).load(any())
        io.advanceTimeBy(1, TimeUnit.SECONDS)
        main.triggerActions()
        // The old figures stay until the refresh lands.
        assertThat(controller.stats.value.loading).isFalse()
        assertThat(controller.stats.value.avgGlucose).isEqualTo("load 1")
        run()
        assertThat(controller.stats.value.avgGlucose).isEqualTo("load 2")
    }

    @Test fun `the cycle pattern reloads only when completed days may have changed`() {
        val loaded = Observable.just(CycleLoad(CYCLE_HISTORY_DAYS, LocalDate.of(2026, 10, 13), emptyMap()))
        whenever(loader.loadCycle()).thenAnswer { Observable.never<CycleLoad>() }
        whenever(loader.beforeToday(any())).thenAnswer { (it.arguments[0] as Long) < 5_000 }
        fun change(from: Long) {
            history.onNext(from)
            io.advanceTimeBy(HISTORY_SETTLE_SECONDS, TimeUnit.SECONDS); run(); run()
        }
        controller.start(); run()
        controller.showCycle(); run()
        // While loading, even a change about today restarts it: the history being read may be out of date.
        change(9_000)
        verify(loader, times(2)).loadCycle()
        whenever(loader.loadCycle()).thenReturn(loaded)
        change(9_000)
        verify(loader, times(3)).loadCycle()
        assertThat(controller.cycle.value.loading).isFalse()
        // Once loaded, today's changes leave it; changes to completed days reload it.
        change(9_000)
        verify(loader, times(3)).loadCycle()
        change(1_000)
        verify(loader, times(4)).loadCycle()
        // Past midnight, October 14 is complete, so a change about the new day brings it in.
        whenever(loader.newestCompletedDay()).thenReturn(LocalDate.of(2026, 10, 14))
        change(9_000)
        verify(loader, times(5)).loadCycle()
    }

    @Test fun `deleting saved totals runs once, keeps the figures and reports failure`() {
        controller.start(); run()
        controller.recalculate()
        controller.recalculate()
        assertThat(controller.recalculation.value).isEqualTo(Recalculation.RUNNING)
        run()
        verify(loader, times(1)).recalculateTdd()
        verify(loader, times(1)).load(any())
        assertThat(controller.recalculation.value).isEqualTo(Recalculation.DONE)
        doAnswer { throw IllegalStateException("database") }.whenever(loader).recalculateTdd()
        controller.recalculate(); run()
        assertThat(controller.recalculation.value).isEqualTo(Recalculation.FAILED)
        assertThat(controller.stats.value.avgGlucose).isEqualTo("load 1")
    }

    @Test fun `day 1 moves over loaded totals and is kept when an alignment lacks data so it can be undone`() {
        val newest = LocalDate.of(2026, 10, 13)
        // 49 days: two cycles at offset 0, but only 20 days in the older one after shifting by a day.
        val totals = (0 until 49).associate { newest.minusDays(it.toLong()) to 30.0 }
        whenever(loader.loadCycle()).thenReturn(Observable.just(CycleLoad(CYCLE_HISTORY_DAYS, newest, totals)))
        controller.showCycle(); run()
        assertThat(controller.cycle.value.pattern!!.firstDay).isEqualTo(newest.minusDays(27))
        controller.setCycleOffset(1)
        assertThat(controller.cycle.value.pattern).isNull()
        assertThat(controller.cycle.value.firstDay).isEqualTo(newest.minusDays(28))
        controller.setCycleOffset(30)
        assertThat(controller.cycle.value.offset).isEqualTo(CYCLE_DAYS - 1)
        controller.setCycleOffset(0)
        assertThat(controller.cycle.value.pattern).isNotNull()
        verify(loader, times(1)).loadCycle()
    }
}
