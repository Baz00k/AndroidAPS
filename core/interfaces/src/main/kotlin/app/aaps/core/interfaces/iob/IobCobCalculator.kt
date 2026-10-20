package app.aaps.core.interfaces.iob

import app.aaps.core.data.aps.BasalData
import app.aaps.core.data.iob.CobInfo
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.profile.Profile

interface IobCobCalculator {

    var ads: AutosensDataStore

    /** Change the live store in place. Serialised with [publishAds], so the change cannot land in a store that is being replaced. */
    fun updateAds(update: (AutosensDataStore) -> Unit)

    /**
     * Replace the live store with a finished calculation, unless the live store changed after
     * [calculated] was cloned from it (for example, a newer glucose reading was loaded).
     *
     * @return true if [calculated] became the live store
     */
    fun publishAds(calculated: AutosensDataStore): Boolean

    /** Timestamp of the newest glucose reading in the live store, or null if none is loaded. */
    fun newestLoadedGlucoseTimestamp(): Long?

    /**
     * True if a request calculated from the reading at [timestamp] must no longer be enacted: a newer
     * valid reading is stored (at most two minutes ahead of now), or the reading is more than ten
     * minutes old, so supersession can no longer be ruled out.
     */
    fun isGlucoseSuperseded(timestamp: Long): Boolean

    /**
     * True while the database holds a glucose reading newer than the newest one in the live store,
     * so dosing must wait rather than act on older glucose. Requests a calculation for that reading,
     * in case none is under way (for example, it was beyond the window of the last load).
     */
    fun isGlucoseCalculationPending(): Boolean
    fun getMealDataWithWaitingForCalculationFinish(): MealData
    fun getLastAutosensDataWithWaitForCalculationFinish(reason: String): AutosensData?

    fun calculateFromTreatmentsAndTemps(toTime: Long, profile: Profile): IobTotal

    fun calculateDetectionStart(from: Long, limitDataToOldestAvailable: Boolean): Long

    fun getBasalData(profile: Profile, fromTime: Long): BasalData

    fun calculateIobArrayInDia(profile: Profile): Array<IobTotal>
    fun calculateIobArrayForSMB(lastAutosensResult: AutosensResult, exerciseMode: Boolean, halfBasalExerciseTarget: Int, isTempTarget: Boolean): Array<IobTotal>
    fun iobArrayToString(array: Array<IobTotal>): String

    fun clearCache()

    /**
     *  Calculate CobInfo to now()
     *
     *  @param reason caller identification
     *  @return CobInfo
     */
    fun getCobInfo(reason: String): CobInfo

    /**
     * Calculate IobTotal from boluses and extended boluses to now().
     * NOTE: Only isValid == true boluses are included
     * NOTE: if faking by TBR by extended boluses is enabled, extended boluses are not included
     *  and are calculated towards temporary basals
     *
     * @return calculated iob
     */
    fun calculateIobFromBolus(): IobTotal

    /**
     *  Calculate IOB of base basal insulin (usually not accounted towards IOB)
     *
     *  @param  toTime
     *  @return IobTotal
     */
    fun calculateAbsoluteIobFromBaseBasals(toTime: Long): IobTotal

    /**
     *  Calculate IOB from Temporary basals and Extended boluses (if emulation is enabled) to the the time specified
     *
     *  @param  toTime    time to calculate to
     *  @return IobTotal
     */
    fun calculateIobToTimeFromTempBasalsIncludingConvertedExtended(toTime: Long): IobTotal

    /**
     *  Calculate IOB from Temporary basals and Extended boluses (if emulation is enabled) to now
     *
     *  @return IobTotal
     */
    fun calculateIobFromTempBasalsIncludingConvertedExtended(): IobTotal
}