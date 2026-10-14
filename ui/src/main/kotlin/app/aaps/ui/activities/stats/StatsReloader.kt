package app.aaps.ui.activities.stats

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.SerialDisposable

/** Owned by the UI thread. The generation guard also rejects results already queued on main. */
internal class StatsReloader(
    private val load: (Int) -> Observable<StatsUiState>,
    private val io: Scheduler,
    private val main: Scheduler,
    private val onState: (StatsUiState) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private val request = SerialDisposable()
    private var generation = 0L
    private var state = StatsUiState()

    fun reload(days: Int) {
        val id = ++generation
        request.set(null)
        state = StatsUiState(rangeDays = days)
        onState(state)
        request.set(Observable.defer { load(days) }.subscribeOn(io).observeOn(main, true).subscribe({ result ->
            if (id == generation) {
                state = result
                onState(result)
            }
        }, { error ->
            if (id == generation) {
                state = state.copy(loading = false, insulinLoading = false, failed = state.loading)
                onState(state)
                onError(error)
            }
        }))
    }

    fun stop() {
        generation++
        request.set(null)
    }
}
