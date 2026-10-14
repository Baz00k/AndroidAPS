package app.aaps.ui.activities.stats

import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.PublishSubject
import org.junit.jupiter.api.Test

class StatsReloaderTest {
    private val io = TestScheduler()
    private val main = TestScheduler()
    private val states = mutableListOf<StatsUiState>()
    private val errors = mutableListOf<Throwable>()
    private val sources = mutableMapOf<Int, PublishSubject<StatsUiState>>()
    private val loader = StatsReloader({ sources.getOrPut(it) { PublishSubject.create() } }, io, main, states::add, errors::add)

    @Test fun `queued old result cannot replace latest range and new selection clears old metrics`() {
        loader.reload(7); io.triggerActions()
        sources.getValue(7).onNext(StatsUiState(loading = false, avgGlucose = "100"))
        main.triggerActions()
        sources.getValue(7).onNext(StatsUiState(loading = false, avgGlucose = "OLD"))
        loader.reload(90); io.triggerActions(); main.triggerActions()
        assertThat(states.last().rangeDays).isEqualTo(90)
        assertThat(states.last().loading).isTrue()
        assertThat(states.last().avgGlucose).isEqualTo("--")
        sources.getValue(90).onNext(StatsUiState(loading = false, rangeDays = 90, avgGlucose = "120"))
        main.triggerActions()
        assertThat(states.last().avgGlucose).isEqualTo("120")
    }

    @Test fun `failure ends loading and can be retried`() {
        loader.reload(30); io.triggerActions()
        sources.getValue(30).onError(IllegalStateException("database")); main.triggerActions()
        assertThat(states.last().failed).isTrue()
        assertThat(states.last().loading).isFalse()
        sources.remove(30)
        loader.reload(30); io.triggerActions()
        sources.getValue(30).onNext(StatsUiState(loading = false, rangeDays = 30)); main.triggerActions()
        assertThat(states.last().failed).isFalse()
        assertThat(errors).hasSize(1)
    }

    @Test fun `insulin failure preserves usable glucose instead of blanking the screen`() {
        loader.reload(7); io.triggerActions()
        sources.getValue(7).onNext(StatsUiState(loading = false, avgGlucose = "100", insulinFailed = true))
        sources.getValue(7).onError(IllegalStateException("insulin")); main.triggerActions()
        assertThat(states.last().failed).isFalse()
        assertThat(states.last().insulinFailed).isTrue()
        assertThat(states.last().avgGlucose).isEqualTo("100")
    }

    @Test fun `pause cancels a load and resume starts selected range again`() {
        loader.reload(90); io.triggerActions()
        loader.stop()
        sources.getValue(90).onNext(StatsUiState(loading = false, rangeDays = 90)); main.triggerActions()
        assertThat(states).hasSize(1)
        loader.reload(90); io.triggerActions()
        sources.getValue(90).onNext(StatsUiState(loading = false, rangeDays = 90)); main.triggerActions()
        assertThat(states.last().loading).isFalse()
    }

    @Test fun `synchronous load construction failure is recoverable`() {
        val reloader = StatsReloader({ throw IllegalStateException("failure") }, io, main, states::add, errors::add)
        reloader.reload(7); io.triggerActions(); main.triggerActions()
        assertThat(states.last().failed).isTrue()
    }
}
