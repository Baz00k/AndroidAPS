package app.aaps.ui.activities.stats

import androidx.compose.runtime.mutableStateOf
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.SerialDisposable
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * A sync writes history in many chunks; refresh once it settles rather than once per chunk.
 * Longer than the loop's own 5-second history batching, so the two rarely recalculate the same days at once.
 */
internal const val HISTORY_SETTLE_SECONDS = 10L

/**
 * Owned by the UI thread while the screen is resumed: the selected range, the on-demand cycle pattern,
 * the confirmed cache recalculation and refreshes after history changes. [history] emits the oldest
 * timestamp of each change.
 */
internal class StatsController(
    private val loader: StatsLoader,
    private val history: Observable<Long>,
    private val io: Scheduler,
    private val main: Scheduler,
    private val onError: (Throwable) -> Unit,
    rangeDays: Int = 7,
    cycleOffset: Int = 0
) {
    val stats = mutableStateOf(StatsUiState(rangeDays = rangeDays))
    val cycle = mutableStateOf(CycleUiState(offset = cycleOffset.coerceIn(0, CYCLE_DAYS - 1)))
    val recalculation = mutableStateOf(Recalculation.IDLE)

    private val reloader = StatsReloader(loader::load, io, main, { stats.value = it }, onError)
    private val changes = SerialDisposable()
    private val invalidation = SerialDisposable()
    private val recalculationRequest = SerialDisposable()
    private val cycleRequest = SerialDisposable()
    private var cycleGeneration = 0L
    private var totals: Map<LocalDate, Double>? = null
    private var newest: LocalDate = LocalDate.MIN

    fun start() {
        reloader.reload(stats.value.rangeDays)
        if (cycle.value.requested) loadCycle()
        changes.set(history
                        .publish { it.buffer(it.debounce(HISTORY_SETTLE_SECONDS, TimeUnit.SECONDS, io)) }
                        .filter { it.isNotEmpty() }
                        .map { it.min() }
                        .observeOn(main)
                        .subscribe(::historyChanged, onError))
    }

    fun stop() {
        changes.set(null)
        invalidation.set(null)
        recalculationRequest.set(null)
        if (recalculation.value == Recalculation.RUNNING) recalculation.value = Recalculation.IDLE
        reloader.stop()
        cycleGeneration++
        cycleRequest.set(null)
        if (cycle.value.loading) cycle.value = cycle.value.copy(loading = false)
    }

    fun select(days: Int) = reloader.reload(days)

    fun showCycle() {
        cycle.value = cycle.value.copy(requested = true)
        loadCycle()
    }

    fun setCycleOffset(offset: Int) {
        val shift = offset.coerceIn(0, CYCLE_DAYS - 1)
        cycle.value = cycle.value.copy(offset = shift, firstDay = totals?.let { cycleStart(newest, shift) },
                                       pattern = totals?.let { cyclePattern(it, newest, shift) })
    }

    /** Deletes only the derived daily totals, then rebuilds what is on screen from the stored records. */
    fun recalculate() {
        if (recalculation.value == Recalculation.RUNNING) return
        recalculation.value = Recalculation.RUNNING
        val reloadCycle = cancelWork()
        recalculationRequest.set(Completable.fromAction(loader::recalculateTdd).subscribeOn(io).observeOn(main).subscribe({
            recalculation.value = Recalculation.IDLE
            reloader.reload(stats.value.rangeDays)
            if (reloadCycle) loadCycle()
        }, { error ->
            recalculation.value = Recalculation.FAILED
            reloader.markStale()
            if (reloadCycle) loadCycle()
            onError(error)
        }))
    }

    private fun historyChanged(from: Long) {
        val interrupted = cycle.value.loading
        val reloadCycle = cancelWork() && (interrupted || loader.beforeToday(from))
        invalidation.set(Completable.fromAction { loader.clearCachedTdd(from) }.subscribeOn(io).observeOn(main).subscribe({
            reloader.refresh(stats.value.rangeDays)
            if (reloadCycle) loadCycle()
        }, { error ->
            reloader.markStale()
            if (reloadCycle) loadCycle()
            onError(error)
        }))
    }

    /**
     * Stops this screen's TDD calculations before the cache is cleared, so none of them can go on to cache a
     * total read before the change. Returns whether the cycle pattern was requested and must load again.
     */
    private fun cancelWork(): Boolean {
        reloader.stop()
        cycleGeneration++
        cycleRequest.set(null)
        if (cycle.value.loading) cycle.value = cycle.value.copy(loading = false)
        return cycle.value.requested
    }

    /** The previous pattern stays visible while a newer history loads. */
    private fun loadCycle() {
        val id = ++cycleGeneration
        cycle.value = cycle.value.copy(loading = true, failed = false, loadedDays = 0)
        cycleRequest.set(loader.loadCycle().subscribeOn(io).observeOn(main, true).subscribe({ load ->
            if (id != cycleGeneration) return@subscribe
            val loaded = load.totals
            if (loaded == null) {
                cycle.value = cycle.value.copy(loadedDays = load.loadedDays)
                return@subscribe
            }
            totals = loaded
            newest = load.newest
            cycle.value = cycle.value.copy(loading = false, loadedDays = load.loadedDays, availableDays = loaded.size,
                                           firstDay = cycleStart(load.newest, cycle.value.offset),
                                           pattern = cyclePattern(loaded, load.newest, cycle.value.offset))
        }, { error ->
            if (id == cycleGeneration) {
                cycle.value = cycle.value.copy(loading = false, failed = true)
                onError(error)
            }
        }))
    }
}
