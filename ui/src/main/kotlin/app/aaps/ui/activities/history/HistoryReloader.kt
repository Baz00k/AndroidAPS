package app.aaps.ui.activities.history

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.SerialDisposable
import java.util.concurrent.TimeUnit

/**
 * Loads History and keeps it current while it is on screen. Running rows are snapshots, so it reloads again
 * when the earliest one ends: a record that finished or was cancelled becomes removable with its final values.
 *
 * At most one load and one timer exist. Starting a load cancels the previous one, so an older snapshot can never
 * replace a newer one (for example restoring a row that was just removed). Nothing loads or ticks unless the screen
 * is active: [resume] activates it and loads, [stop] deactivates it and cancels everything, so a removal finishing
 * while the screen is paused cannot restart refreshing. [cancel] only drops pending work.
 */
internal class HistoryReloader(
    private val load: () -> List<HistoryItem>,
    private val io: Scheduler,
    private val main: Scheduler,
    private val now: () -> Long,
    private val onLoaded: (List<HistoryItem>) -> Unit,
    private val onError: (Throwable) -> Unit
) {

    private val loading = SerialDisposable()
    private val timer = SerialDisposable()
    private var active = false

    fun resume() {
        active = true
        reload()
    }

    fun reload() {
        if (!active) return
        timer.set(null)
        loading.set(
            Single.fromCallable { load() }
                .subscribeOn(io)
                .observeOn(main)
                .subscribe({ items ->
                               onLoaded(items)
                               items.nextRunningEnd()?.let { end ->
                                   timer.set(
                                       Completable.timer((end - now()).coerceAtLeast(0L) + 1_000L, TimeUnit.MILLISECONDS, main)
                                           .subscribe({ reload() }, onError)
                                   )
                               }
                           }, onError)
        )
    }

    fun cancel() {
        timer.set(null)
        loading.set(null)
    }

    fun stop() {
        active = false
        cancel()
    }
}
