package app.aaps.ui.activities.stats

import androidx.collection.LongSparseArray
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TDD
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import io.reactivex.rxjava3.core.Observable
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

/** Read-only screen loader; the shared dosing and TDD algorithms are deliberately unchanged. */
class StatsLoader @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val tddCalculator: TddCalculator
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
            val glucose = summarizeGlucose(raw, window.rollingFrom, now, window.tirFrom, window.tirTo,
                                           profileUtil.convertToMgdl(low, units), profileUtil.convertToMgdl(high, units),
                                           { profileUtil.fromMgdlToUnits(it, units) }, java.util.TimeZone.getTimeZone(zone))
            val state = StatsUiState(
                loading = false, rangeDays = days, insulinLoading = true,
                veryLow = glucose.bands[0], low = glucose.bands[1], inRange = glucose.bands[2], high = glucose.bands[3], veryHigh = glucose.bands[4],
                tirReadings = glucose.tirReadings, glucoseReadings = glucose.readings,
                gmi = if (glucose.average > 0) String.format(Locale.getDefault(), "%.1f%%", 3.31 + .02392 * glucose.average) else "--",
                avgGlucose = if (glucose.average > 0) profileUtil.fromMgdlToStringInUnits(glucose.average, units) else "--",
                avgGlucoseUnit = if (units == GlucoseUnit.MMOL) "mmol/L" else "mg/dL",
                cv = if (glucose.readings > 0) String.format(Locale.getDefault(), "%.0f%%", glucose.cv) else "--",
                cvGood = glucose.readings > 0 && glucose.cv <= 36, hourly = glucose.hourly, lowMark = low, highMark = high,
                decimals = if (units == GlucoseUnit.MGDL) 0 else 1
            )
            emitter.onNext(state)
            try {
                val tdds = LongSparseArray<TDD>()
                // A missing old day must not bypass cached later days. Check disposal between days.
                // Preserve the original request at exceptional midnights (see StatsWindow).
                val requests = if (window.canSplitTdd) days else 1
                for (day in 0 until requests) {
                    if (emitter.isDisposed) return@create
                    val daily = if (window.canSplitTdd) tddCalculator.calculate(window.tddEnd(day), 1, true)
                    else tddCalculator.calculate(now, days.toLong(), true)
                    if (daily != null) for (i in 0 until daily.size()) tdds.put(daily.keyAt(i), daily.valueAt(i))
                }
                if (emitter.isDisposed) return@create
                val average = tddCalculator.averageTDD(tdds)?.data
                val dose = average?.let { if (it.totalAmount > 0) it.totalAmount else it.basalAmount + it.bolusAmount }
                emitter.onNext(state.copy(insulinLoading = false, insulinDays = tdds.size(),
                                          avgTdd = dose?.let { String.format(Locale.getDefault(), "%.1f U", it) } ?: "--",
                                          carbsPerDay = average?.carbs?.takeIf { it > 0 }?.let { String.format(Locale.getDefault(), "%.0f g", it) } ?: "--"))
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
}
