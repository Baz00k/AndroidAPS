package app.aaps.ui.activities.stats

import androidx.collection.LongSparseArray
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TDD
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import io.reactivex.rxjava3.core.Observable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

/** Progress of the cycle history. [totals] is set once, on the last emission. */
internal class CycleLoad(val loadedDays: Int, val newest: LocalDate, val totals: Map<LocalDate, Double>? = null)

/**
 * Screen loader. Daily insulin is calculated from the stored records for each local calendar day and is
 * never read from or written to the shared TDD cache, which the loop uses: a cached total can be stale,
 * and the shared calculator caches fixed 24-hour days, which are wrong across a daylight-saving change.
 * The only write is the user-confirmed [recalculateTdd].
 */
class StatsLoader @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val tddCalculator: TddCalculator,
    private val uel: UserEntryLogger
) {
    fun load(days: Int): Observable<StatsUiState> = Observable.create { emitter ->
        try {
            val units = profileFunction.getUnits()
            val low = preferences.get(UnitDoubleKey.OverviewLowMark)
            val high = preferences.get(UnitDoubleKey.OverviewHighMark)
            val now = dateUtil.now()
            val zone = ZoneId.systemDefault()
            val window = StatsWindow(now, days, zone)
            val raw = persistenceLayer.getBgReadingsDataFromTimeToTime(window.from, window.to, true)
            if (emitter.isDisposed) return@create
            val lowMgdl = profileUtil.convertToMgdl(low, units)
            val highMgdl = profileUtil.convertToMgdl(high, units)
            val glucose = summarizeGlucose(raw, window.from, window.to - 1, window.from, window.to - 1, lowMgdl, highMgdl,
                                           { profileUtil.fromMgdlToUnits(it, units) }, java.util.TimeZone.getTimeZone(zone))
            val completed = summarizeCompletedDays(raw, window.from, window.to, lowMgdl, highMgdl, zone)
            val unit = if (units == GlucoseUnit.MMOL) "mmol/L" else "mg/dL"
            val hasGlucose = glucose.average > 0
            val state = StatsUiState(
                loading = false, rangeDays = days, insulinLoading = true,
                veryLow = glucose.bands[0], low = glucose.bands[1], inRange = glucose.bands[2], high = glucose.bands[3], veryHigh = glucose.bands[4],
                tirReadings = glucose.tirReadings, glucoseReadings = glucose.readings,
                gmi = if (hasGlucose) gmiPercent(glucose.average) else "--",
                avgGlucose = if (hasGlucose) profileUtil.fromMgdlToStringInUnits(glucose.average, units) else "--",
                avgGlucoseUnit = unit,
                cv = if (hasGlucose) String.format(Locale.getDefault(), "%.0f%%", glucose.cv) else "--",
                hourly = glucose.hourly, lowMark = low, highMark = high,
                decimals = if (units == GlucoseUnit.MGDL) 0 else 1,
                completed = completed, pentagon = glucosePentagon(completed)
            )
            emitter.onNext(state)
            try {
                val tdds = dailyTdds(now, days, zone) { emitter.isDisposed } ?: return@create
                val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                // From today's first local instant, like the completed days. A part of today without a profile
                // gives no total rather than a partial one that looks complete.
                val today = tddCalculator.calculateInterval(date.atStartOfDay(zone).toInstant().toEpochMilli(), now, allowMissingData = false)
                if (emitter.isDisposed) return@create
                val average = tddCalculator.averageTDD(tdds)?.data
                emitter.onNext(state.copy(insulinLoading = false, insulinDays = tdds.size(),
                                          avgTdd = average?.dose()?.let { String.format(Locale.getDefault(), "%.1f U", it) } ?: "--",
                                          insulinRows = insulinRows(tdds, date, days, zone),
                                          insulinAverage = average?.toDay(date),
                                          insulinToday = today?.toDay(date)))
            } catch (error: Exception) {
                if (!emitter.isDisposed) {
                    emitter.onNext(state.copy(insulinLoading = false, insulinFailed = true))
                    emitter.tryOnError(error)
                }
                return@create
            }
            emitter.onComplete()
        } catch (error: Exception) {
            emitter.tryOnError(error)
        }
    }

    /** Completed-day totals for the cycle pattern, with progress every week. */
    internal fun loadCycle(): Observable<CycleLoad> = Observable.create { emitter ->
        try {
            val now = dateUtil.now()
            val zone = ZoneId.systemDefault()
            val newest = newestCompletedDay(now, zone)
            val tdds = dailyTdds(now, CYCLE_HISTORY_DAYS, zone, { loaded -> if (loaded % 7 == 0) emitter.onNext(CycleLoad(loaded, newest)) }) { emitter.isDisposed }
                ?: return@create
            val totals = HashMap<LocalDate, Double>()
            for (i in 0 until tdds.size()) totals[Instant.ofEpochMilli(tdds.keyAt(i)).atZone(zone).toLocalDate()] = tdds.valueAt(i).dose()
            emitter.onNext(CycleLoad(CYCLE_HISTORY_DAYS, newest, totals))
            emitter.onComplete()
        } catch (error: Exception) {
            emitter.tryOnError(error)
        }
    }

    /** Whether a change at [time] can affect a completed day, which the cycle pattern is made of. */
    internal fun beforeToday(time: Long): Boolean {
        val zone = ZoneId.systemDefault()
        return time < newestCompletedDay(dateUtil.now(), zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Yesterday, the last day the cycle pattern can include. It moves on at midnight. */
    internal fun newestCompletedDay(): LocalDate = newestCompletedDay(dateUtil.now(), ZoneId.systemDefault())

    private fun newestCompletedDay(now: Long, zone: ZoneId) = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1)

    /** Confirmed by the user. Only rows of the derived cache are deleted; boluses, basals and carbs are untouched. */
    internal fun recalculateTdd() {
        uel.log(Action.STAT_RESET, Sources.Stats)
        persistenceLayer.clearCachedTddData(0)
    }

    /**
     * The [days] completed local days before today, keyed by the start of each day. Returns null when
     * cancelled. A day without a total for all of it (no profile at some point) is left out, and so is any
     * day older than the retained treatment history: from a profile alone it would read as basal only.
     */
    private fun dailyTdds(now: Long, days: Int, zone: ZoneId, onDay: (Int) -> Unit = {}, cancelled: () -> Boolean): LongSparseArray<TDD>? {
        val tdds = LongSparseArray<TDD>()
        val retained = firstRetainedDay(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (back in 1..days) {
            if (cancelled()) return null
            val date = today.minusDays(back.toLong())
            if (retained != null && !date.isBefore(retained)) {
                // Explicit local midnights: a day can be 23 or 25 hours long.
                val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                tddCalculator.calculateInterval(start, end, allowMissingData = false)?.let { tdds.put(start, it) }
            }
            onDay(back)
        }
        return if (cancelled()) null else tdds
    }

    /**
     * Database cleanup removes every treatment table before one cut-off, so the oldest remaining record marks
     * it. Its own day may have lost the hours before the cut-off and is excluded too. With no treatments at
     * all there is no evidence of any retained history, so null means no past day can be calculated.
     */
    private fun firstRetainedDay(zone: ZoneId): LocalDate? =
        listOfNotNull(persistenceLayer.getOldestBolus()?.timestamp, persistenceLayer.getOldestCarbs()?.timestamp,
                      persistenceLayer.getOldestTemporaryBasalRecord()?.timestamp, persistenceLayer.getOldestExtendedBolusRecord()?.timestamp)
            .minOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().plusDays(1) }

    private fun insulinRows(tdds: LongSparseArray<TDD>, today: LocalDate, days: Int, zone: ZoneId): List<InsulinDay> {
        val recorded = (0 until tdds.size()).map { tdds.valueAt(it).toDay(Instant.ofEpochMilli(tdds.keyAt(it)).atZone(zone).toLocalDate()) }
        val missing = (1..days).map { today.minusDays(it.toLong()) }.filter { date -> recorded.none { it.date == date } }
            .map { InsulinDay(it, recorded = false) }
        return (recorded + missing).sortedByDescending { it.date }
    }

    private fun TDD.toDay(date: LocalDate) = InsulinDay(date, basalAmount, bolusAmount, dose(), carbs)
}

/** A stored total may be zero when only basal and bolus were recorded; see [TDD.totalAmount]. */
internal fun TDD.dose() = if (totalAmount > 0) totalAmount else basalAmount + bolusAmount

/** Glucose management indicator (Bergenstal 2018) from mean glucose in mg/dL. */
private fun gmi(meanMgdl: Double) = 3.31 + 0.02392 * meanMgdl

internal fun gmiPercent(meanMgdl: Double): String = String.format(Locale.getDefault(), "%.1f%%", gmi(meanMgdl))
