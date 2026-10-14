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
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import io.reactivex.rxjava3.core.Observable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

/** Progress of the cycle history. [totals] is set once, on the last emission. */
internal class CycleLoad(val loadedDays: Int, val newest: LocalDate, val totals: Map<LocalDate, Double>? = null)

/** Screen loader. Reads are read-only; the only write is the derived TDD cache, never the records it is derived from. */
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
            val raw = persistenceLayer.getBgReadingsDataFromTimeToTime(minOf(window.rollingFrom, window.tirFrom), now, true)
            if (emitter.isDisposed) return@create
            val lowMgdl = profileUtil.convertToMgdl(low, units)
            val highMgdl = profileUtil.convertToMgdl(high, units)
            val glucose = summarizeGlucose(raw, window.rollingFrom, now, window.tirFrom, window.tirTo, lowMgdl, highMgdl,
                                           { profileUtil.fromMgdlToUnits(it, units) }, java.util.TimeZone.getTimeZone(zone))
            val completed = summarizeCompletedDays(raw, window.tirFrom, window.tirTo, lowMgdl, highMgdl, zone)
            val unit = if (units == GlucoseUnit.MMOL) "mmol/L" else "mg/dL"
            val state = StatsUiState(
                loading = false, rangeDays = days, insulinLoading = true,
                veryLow = glucose.bands[0], low = glucose.bands[1], inRange = glucose.bands[2], high = glucose.bands[3], veryHigh = glucose.bands[4],
                tirReadings = glucose.tirReadings, glucoseReadings = glucose.readings,
                gmi = if (glucose.average > 0) String.format(Locale.getDefault(), "%.1f%%", 3.31 + .02392 * glucose.average) else "--",
                avgGlucose = if (glucose.average > 0) profileUtil.fromMgdlToStringInUnits(glucose.average, units) else "--",
                avgGlucoseUnit = unit,
                cv = if (glucose.readings > 0) String.format(Locale.getDefault(), "%.0f%%", glucose.cv) else "--",
                cvGood = glucose.readings > 0 && glucose.cv <= 36, hourly = glucose.hourly, lowMark = low, highMark = high,
                decimals = if (units == GlucoseUnit.MGDL) 0 else 1,
                completed = completed, pentagon = glucosePentagon(completed),
                completedMean = if (completed.mean > 0) "${profileUtil.fromMgdlToStringInUnits(completed.mean, units)} $unit" else "--",
                sd = if (completed.mean > 0) "${profileUtil.fromMgdlToStringInUnits(completed.sd, units)} $unit" else "--",
                ea1c = if (completed.mean > 0) estimatedA1c(completed.mean) else "--"
            )
            emitter.onNext(state)
            try {
                val tdds = dailyTdds(window, days, now) { emitter.isDisposed } ?: return@create
                val today = tddCalculator.calculateToday()
                if (emitter.isDisposed) return@create
                val average = tddCalculator.averageTDD(tdds)?.data
                val dose = average?.dose()
                val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                emitter.onNext(state.copy(insulinLoading = false, insulinDays = tdds.size(),
                                          avgTdd = dose?.let { String.format(Locale.getDefault(), "%.1f U", it) } ?: "--",
                                          carbsPerDay = average?.carbs?.takeIf { it > 0 }?.let { String.format(Locale.getDefault(), "%.0f g", it) } ?: "--",
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
            val newest = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1)
            val window = StatsWindow(now, CYCLE_HISTORY_DAYS, zone)
            val tdds = dailyTdds(window, CYCLE_HISTORY_DAYS, now, { loaded -> if (loaded % 7 == 0) emitter.onNext(CycleLoad(loaded, newest)) }) { emitter.isDisposed }
                ?: return@create
            val totals = HashMap<LocalDate, Double>()
            for (i in 0 until tdds.size()) totals[Instant.ofEpochMilli(tdds.keyAt(i)).atZone(zone).toLocalDate()] = tdds.valueAt(i).dose()
            emitter.onNext(CycleLoad(CYCLE_HISTORY_DAYS, newest, totals))
            emitter.onComplete()
        } catch (error: Exception) {
            emitter.tryOnError(error)
        }
    }

    /**
     * Drops cached totals from the local midnight before [changedFrom], so a refresh after a history change
     * recalculates them instead of reading the old value. The loop's history handler does the same a few
     * seconds later; doing it here as well removes the race between the two.
     */
    internal fun clearCachedTdd(changedFrom: Long) = persistenceLayer.clearCachedTddData(MidnightTime.calc(changedFrom))

    /** Whether a change at [time] can affect a completed day, which the cycle pattern is made of. */
    internal fun beforeToday(time: Long) = time < MidnightTime.calc(dateUtil.now())

    /** Confirmed by the user. Only rows of the derived cache are deleted; boluses, basals and carbs are untouched. */
    internal fun recalculateTdd() {
        uel.log(Action.STAT_RESET, Sources.Stats)
        persistenceLayer.clearCachedTddData(0)
    }

    /** Returns null when cancelled. */
    private fun dailyTdds(window: StatsWindow, days: Int, now: Long, onDay: (Int) -> Unit = {}, cancelled: () -> Boolean): LongSparseArray<TDD>? {
        val tdds = LongSparseArray<TDD>()
        // A missing old day must not bypass cached later days. Check cancellation between days.
        // Preserve the original request at exceptional midnights (see StatsWindow).
        val requests = if (window.canSplitTdd) days else 1
        for (day in 0 until requests) {
            if (cancelled()) return null
            val daily = if (window.canSplitTdd) tddCalculator.calculate(window.tddEnd(day), 1, true)
            else tddCalculator.calculate(now, days.toLong(), true)
            if (daily != null) for (i in 0 until daily.size()) tdds.put(daily.keyAt(i), daily.valueAt(i))
            onDay(if (window.canSplitTdd) day + 1 else days)
        }
        return if (cancelled()) null else tdds
    }

    private fun insulinRows(tdds: LongSparseArray<TDD>, today: LocalDate, days: Int, zone: ZoneId): List<InsulinDay> {
        val recorded = (0 until tdds.size()).map { tdds.valueAt(it).toDay(Instant.ofEpochMilli(tdds.valueAt(it).timestamp).atZone(zone).toLocalDate()) }
        val missing = (1..days).map { today.minusDays(it.toLong()) }.filter { date -> recorded.none { it.date == date } }
            .map { InsulinDay(it, recorded = false) }
        return (recorded + missing).sortedByDescending { it.date }
    }

    private fun TDD.toDay(date: LocalDate) = InsulinDay(date, basalAmount, bolusAmount, dose(), carbs)
}

/** A stored total may be zero when only basal and bolus were recorded; see [TDD.totalAmount]. */
internal fun TDD.dose() = if (totalAmount > 0) totalAmount else basalAmount + bolusAmount

/** ADAG estimate from mean glucose in mg/dL, as Dexcom reports it: percent and IFCC mmol/mol. */
internal fun estimatedA1c(meanMgdl: Double): String {
    val percent = (meanMgdl + 46.7) / 28.7
    return String.format(Locale.getDefault(), "%.1f%% · %d mmol/mol", percent, ((percent - 2.15) * 10.929).roundToInt())
}
