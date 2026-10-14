package app.aaps.ui.activities.stats

import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.PublishSubject
import org.junit.jupiter.api.Test

class StatsReloaderTest {
    private val io = TestScheduler()
    private val main = TestScheduler()
    private val states = mutableListOf<StatsUiState>()
    private val sources = mutableMapOf<Int, PublishSubject<StatsUiState>>()
    private val loader = StatsReloader({ sources.getOrPut(it) { PublishSubject.create() } }, io, main, states::add, {})

    @Test fun `range changes and pause reject queued results and resume can load again`() {
        loader.reload(7); io.triggerActions()
        sources.getValue(7).onNext(StatsUiState(loading = false, avgGlucose = "OLD"))
        loader.reload(90); io.triggerActions(); main.triggerActions()
        assertThat(states.last()).isEqualTo(StatsUiState(rangeDays = 90))
        sources.getValue(90).onNext(StatsUiState(loading = false, rangeDays = 90))
        loader.stop(); main.triggerActions()
        assertThat(states.last().loading).isTrue()
        loader.reload(90); io.triggerActions()
        sources.getValue(90).onNext(StatsUiState(loading = false, rangeDays = 90)); main.triggerActions()
        assertThat(states.last().loading).isFalse()
    }

    @Test fun `refresh keeps the shown figures through partial results and failures`() {
        loader.reload(7); io.triggerActions()
        sources.getValue(7).onNext(StatsUiState(loading = false, avgGlucose = "OLD", avgTdd = "30.0 U")); main.triggerActions()
        sources.remove(7)
        loader.refresh(7); io.triggerActions()
        sources.getValue(7).onNext(StatsUiState(loading = false, avgGlucose = "NEW", insulinLoading = true)); main.triggerActions()
        assertThat(states.last().avgTdd).isEqualTo("30.0 U")
        sources.getValue(7).onNext(StatsUiState(loading = false, avgGlucose = "NEW", avgTdd = "31.0 U")); main.triggerActions()
        assertThat(states.last().avgTdd).isEqualTo("31.0 U")
        sources.remove(7)
        loader.refresh(7); io.triggerActions()
        sources.getValue(7).onError(IllegalStateException("database")); main.triggerActions()
        assertThat(states.last().failed).isFalse()
        assertThat(states.last().avgGlucose).isEqualTo("NEW")
    }

    @Test fun `failure ends loading retry works and a queued partial result survives insulin failure`() {
        loader.reload(30); io.triggerActions()
        sources.getValue(30).onError(IllegalStateException("database")); main.triggerActions()
        assertThat(states.last().failed).isTrue()
        assertThat(states.last().loading).isFalse()
        sources.remove(30)
        loader.reload(30); io.triggerActions()
        sources.getValue(30).onNext(StatsUiState(loading = false, rangeDays = 30, avgGlucose = "100", insulinFailed = true))
        sources.getValue(30).onError(IllegalStateException("insulin")); main.triggerActions()
        assertThat(states.last().failed).isFalse()
        assertThat(states.last().insulinFailed).isTrue()
        assertThat(states.last().avgGlucose).isEqualTo("100")
    }
}
