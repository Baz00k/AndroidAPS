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
 * replace a newer one (for example restoring a row that was just removed), and [stop] cancels both so nothing
 * refreshes while the screen is paused.
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

    fun reload() {
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

    fun stop() {
        timer.set(null)
        loading.set(null)
    }
}
