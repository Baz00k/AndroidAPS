package app.aaps.plugins.main.iob.iobCobCalculator.data

import androidx.collection.LongSparseArray
import androidx.collection.size
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.extensions.fromGv
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

class AutosensDataStoreObject : AutosensDataStore {

    override val dataLock = Any()
    override var lastUsed5minCalculation: Boolean? = null // true if used 5min bucketed data

    companion object {

        const val IRREGULAR_DATA_SEC = 30L
    }

    // Keep bucket timestamps stable so successive calculations can reuse cached autosens data.
    var referenceTime: Long = -1

    override var bgReadings: List<GV> = listOf() // newest at index 0
        @Synchronized set
        @Synchronized get

    override var autosensDataTable = LongSparseArray<AutosensData>() // oldest at index 0
        @Synchronized set
        @Synchronized get

    override var bucketedData: MutableList<InMemoryGlucoseValue>? = null
        @Synchronized set
        @Synchronized get

    override fun clone(): AutosensDataStore =
        AutosensDataStoreObject().also {
            synchronized(dataLock) {
                // IOB/COB workers publish this clone as the live store after every calculation.
                it.referenceTime = this.referenceTime
                // Workers publish the clone; retain mode history for the next cache-invalidation check.
                it.lastUsed5minCalculation = this.lastUsed5minCalculation
                it.bgReadings = this.bgReadings.toMutableList()
                it.autosensDataTable = LongSparseArray<AutosensData>(this.autosensDataTable.size).apply { putAll(this@AutosensDataStoreObject.autosensDataTable) }
                it.bucketedData = this.bucketedData?.toMutableList()
            }
        }

    override fun getBucketedDataTableCopy(): MutableList<InMemoryGlucoseValue>? = synchronized(dataLock) { bucketedData?.toMutableList() }
    override fun getBgReadingsDataTableCopy(): List<GV> = synchronized(dataLock) { bgReadings.toMutableList() }

    override fun reset() {
        synchronized(autosensDataTable) { autosensDataTable = LongSparseArray() }
    }

    override fun newHistoryData(time: Long, aapsLogger: AAPSLogger, dateUtil: DateUtil) {
        synchronized(autosensDataTable) {
            for (index in autosensDataTable.size() - 1 downTo 0) {
                if (autosensDataTable.keyAt(index) > time) {
                    aapsLogger.debug(LTag.AUTOSENS) { "Removing from autosensDataTable: ${dateUtil.dateAndTimeAndSecondsString(autosensDataTable.keyAt(index))}" }
                    autosensDataTable.removeAt(index)
                } else {
                    break
                }
            }
        }
    }

    // roundup to whole minute
    override fun roundUpTime(time: Long): Long {
        return if (time % 60000 == 0L) time else (time / 60000 + 1) * 60000
    }

    /**
     * Return last valid (>39) InMemoryGlucoseValue from bucketed data or null if db is empty
     *
     * @return InMemoryGlucoseValue or null
     */
    override fun lastBg(): InMemoryGlucoseValue? =
        synchronized(dataLock) {
            bucketedData?.let { bucketedData ->
                if (bucketedData.isNotEmpty()) bucketedData[0]
                else null
            }
        }

    /**
     * Provide last bucketed InMemoryGlucoseValue or null if none exists within the last 9 minutes
     *
     * @return InMemoryGlucoseValue or null
     */
    override fun actualBg(): InMemoryGlucoseValue? {
        val lastBg = lastBg() ?: return null
        return if (lastBg.timestamp > System.currentTimeMillis() - T.mins(9).msecs()) lastBg else null
    }

    override fun lastDataTime(dateUtil: DateUtil): String =
        synchronized(dataLock) {
            if (autosensDataTable.size() > 0) dateUtil.dateAndTimeAndSecondsString(autosensDataTable.valueAt(autosensDataTable.size() - 1).time)
            else "autosensDataTable empty"
        }

    fun findPreviousTimeFromBucketedData(time: Long): Long? {
        val bData = bucketedData ?: return null
        for (index in bData.indices) {
            if (bData[index].timestamp <= time) return bData[index].timestamp
        }
        return null
    }

    override fun getAutosensDataAtTime(fromTime: Long): AutosensData? {
        synchronized(dataLock) {
            val now = System.currentTimeMillis()
            if (fromTime > now) return null
            val previous = findPreviousTimeFromBucketedData(fromTime) ?: return null
            return autosensDataTable[roundUpTime(previous)]
        }
    }

    // during recalculation autosensDataTable is cleared and not available
    // for providing COB, which is an serious issue in BolusWizard
    // So let save last value after every calculation and use it
    // if autosensDataTable is not available
    var storedLastAutosensResult: AutosensData? = null
        get() = field?.let { if (it.time < System.currentTimeMillis() - 11 * 60 * 1000) it else null }

    override fun getLastAutosensData(reason: String, aapsLogger: AAPSLogger, dateUtil: DateUtil): AutosensData? {
        synchronized(dataLock) {
            if (autosensDataTable.size() < 1) {
                aapsLogger.debug(LTag.AUTOSENS, "AUTOSENSDATA null: autosensDataTable empty ($reason)")
                return storedLastAutosensResult
            }
            val data: AutosensData = try {
                autosensDataTable.valueAt(autosensDataTable.size() - 1)
            } catch (_: Exception) {
                // data can be processed on the background
                // in this rare case better return null and do not block UI
                // APS plugin should use getLastAutosensDataSynchronized where the blocking is not an issue
                aapsLogger.error("AUTOSENSDATA null: Exception caught ($reason)")
                return storedLastAutosensResult
            }
            return if (data.time < dateUtil.now() - 11 * 60 * 1000) {
                aapsLogger.debug(LTag.AUTOSENS) { "AUTOSENSDATA null: data is old ($reason) size()=${autosensDataTable.size()} lastData=${dateUtil.dateAndTimeAndSecondsString(data.time)}" }
                storedLastAutosensResult
            } else {
                aapsLogger.debug(LTag.AUTOSENS) { "AUTOSENSDATA ($reason) $data" }
                storedLastAutosensResult = data
                data
            }
        }
    }

    private fun adjustToReferenceTime(someTime: Long): Long {
        if (referenceTime == -1L) {
            referenceTime = someTime
            return someTime
        }
        // Preserve direction for readings older than the anchor too (upstream ef87883a).
        val fiveMin = T.mins(5).msecs()
        val diff = ((someTime - referenceTime) % fiveMin + fiveMin) % fiveMin
        return if (diff > T.mins(2).plus(T.secs(30)).msecs()) someTime + (fiveMin - diff) // Adjust to the future
        else someTime - diff // adjust to the past
    }

    fun isAbout5minData(aapsLogger: AAPSLogger): Boolean {
        synchronized(dataLock) {
            // The synchronized getter uses the store monitor, separate from dataLock.
            // Keep one list reference per scan instead of acquiring that monitor per element.
            val readings = bgReadings
            if (readings.size < 3) return true

            var totalDiff: Long = 0
            for (i in 1 until readings.size) {
                val bgTime = readings[i].timestamp
                val lastBgTime = readings[i - 1].timestamp
                var diff = lastBgTime - bgTime
                diff %= T.mins(5).msecs()
                if (diff > T.mins(2).plus(T.secs(30)).msecs()) diff -= T.mins(5).msecs()
                totalDiff += diff
                diff = abs(diff)
                if (diff > T.secs(IRREGULAR_DATA_SEC).msecs()) {
                    aapsLogger.debug(LTag.AUTOSENS, "Interval detection: values: ${readings.size} diff: ${diff / 1000}[s] is5minData: false")
                    return false
                }
            }
            val averageDiff = totalDiff / readings.size / 1000
            val is5minData = averageDiff < 1
            aapsLogger.debug(LTag.AUTOSENS, "Interval detection: values: ${readings.size} averageDiff: $averageDiff[s] is5minData: $is5minData")
            return is5minData
        }
    }

    override fun createBucketedData(aapsLogger: AAPSLogger, dateUtil: DateUtil) {
        val fiveMinData = isAbout5minData(aapsLogger)
        if (lastUsed5minCalculation != null && lastUsed5minCalculation != fiveMinData) {
            // changing mode => clear cache
            aapsLogger.debug("Invalidating cached data because of changed mode.")
            reset()
        }
        lastUsed5minCalculation = fiveMinData
        if (fiveMinData) createBucketedData5min(aapsLogger, dateUtil) else createBucketedDataRecalculated(aapsLogger, dateUtil)
    }

    fun findNewer(time: Long): GV? {
        val readings = bgReadings
        var lastFound = readings[0]
        if (lastFound.timestamp < time) return null
        for (i in 1 until readings.size) {
            if (readings[i].timestamp == time) return readings[i]
            if (readings[i].timestamp > time) continue
            lastFound = readings[i - 1]
            if (readings[i].timestamp < time) break
        }
        return lastFound
    }

    fun findOlder(time: Long): GV? {
        val readings = bgReadings
        var lastFound = readings[readings.size - 1]
        if (lastFound.timestamp > time) return null
        for (i in readings.size - 2 downTo 0) {
            if (readings[i].timestamp == time) return readings[i]
            if (readings[i].timestamp < time) continue
            lastFound = readings[i + 1]
            if (readings[i].timestamp > time) break
        }
        return lastFound
    }

    private fun createBucketedDataRecalculated(aapsLogger: AAPSLogger, dateUtil: DateUtil) {
        val readings = bgReadings
        if (readings.size < 3) {
            bucketedData = null
            return
        }
        val lastBg = readings[0]
        val newBucketedData = ArrayList<InMemoryGlucoseValue>()
        val adjustedTime = adjustToReferenceTime(lastBg.timestamp)
        // A retained grid can sit just after the newest reading because of sensor jitter.
        // Keep that measured sample within the existing 30-second tolerance instead of
        // dropping a whole five-minute bucket (upstream 6300e492). Larger shifts step back.
        // The bucket timestamp can consequently be up to 30 seconds newer than the sample.
        var currentTime = if (adjustedTime - lastBg.timestamp > T.secs(IRREGULAR_DATA_SEC).msecs()) adjustedTime - T.mins(5).msecs() else adjustedTime
        aapsLogger.debug("Adjusted time " + dateUtil.dateAndTimeAndSecondsString(currentTime))
        val firstBucket = if (currentTime > lastBg.timestamp) {
            val bucket = InMemoryGlucoseValue.fromGv(lastBg).copy(timestamp = currentTime)
            currentTime -= T.mins(5).msecs()
            bucket
        } else null
        while (true) {
            // test if current value is older than current time
            val newer = findNewer(currentTime)
            val older = findOlder(currentTime)
            if (newer == null || older == null) break
            if (older.timestamp == newer.timestamp) { // direct hit
                newBucketedData.add(InMemoryGlucoseValue.fromGv(newer))
            } else {
                val bgDelta = newer.value - older.value
                val timeDiffToNew = newer.timestamp - currentTime
                val timeDiffToOlder = currentTime - older.timestamp
                val filledGap = min(timeDiffToOlder, timeDiffToNew) > T.secs(IRREGULAR_DATA_SEC).msecs()
                val currentBg = newer.value - timeDiffToNew.toDouble() / (newer.timestamp - older.timestamp) * bgDelta
                val newBgReading = InMemoryGlucoseValue(currentTime, currentBg.roundToLong().toDouble(), filledGap = filledGap, sourceSensor = lastBg.sourceSensor)
                newBucketedData.add(newBgReading)
            }
            currentTime -= T.mins(5).msecs()
        }
        // Do not invent a lone flat trend when there is no historical bucket to support it.
        if (firstBucket != null && newBucketedData.isNotEmpty()) newBucketedData.add(0, firstBucket)
        bucketedData = newBucketedData
    }

    private fun createBucketedData5min(aapsLogger: AAPSLogger, dateUtil: DateUtil) {
        val readings = bgReadings
        if (readings.size < 3) {
            bucketedData = null
            return
        }
        val lastBg = readings[0]
        val bData: MutableList<InMemoryGlucoseValue> = ArrayList()
        bData.add(InMemoryGlucoseValue.fromGv(readings[0]))
        aapsLogger.debug(LTag.AUTOSENS) { "Adding. bgTime: ${dateUtil.toISOString(readings[0].timestamp)} lastBgTime: none-first-value ${readings[0]}" }
        var j = 0
        for (i in 1 until readings.size) {
            val bgTime = readings[i].timestamp
            var lastBgTime = readings[i - 1].timestamp
            var elapsedMinutes = (bgTime - lastBgTime) / (60 * 1000)
            when {
                abs(elapsedMinutes) > 8 -> {
                    // interpolate missing data points
                    var lastBgValue = readings[i - 1].value
                    elapsedMinutes = abs(elapsedMinutes)
                    var nextBgTime: Long
                    while (elapsedMinutes > 5) {
                        nextBgTime = lastBgTime - 5 * 60 * 1000
                        j++
                        val gapDelta = readings[i].value - lastBgValue
                        val nextBg = lastBgValue + 5.0 / elapsedMinutes * gapDelta
                        val newBgReading = InMemoryGlucoseValue(nextBgTime, nextBg.roundToLong().toDouble(), filledGap = true, sourceSensor = lastBg.sourceSensor)
                        bData.add(newBgReading)
                        aapsLogger.debug(LTag.AUTOSENS) { "Adding. bgTime: ${dateUtil.toISOString(bgTime)} lastBgTime: ${dateUtil.toISOString(lastBgTime)} $newBgReading" }
                        elapsedMinutes -= 5
                        lastBgValue = nextBg
                        lastBgTime = nextBgTime
                    }
                    j++
                    val newBgReading = InMemoryGlucoseValue(bgTime, readings[i].value, sourceSensor = lastBg.sourceSensor)
                    bData.add(newBgReading)
                    aapsLogger.debug(LTag.AUTOSENS) { "Adding. bgTime: ${dateUtil.toISOString(bgTime)} lastBgTime: ${dateUtil.toISOString(lastBgTime)} $newBgReading" }
                }

                abs(elapsedMinutes) > 2 -> {
                    j++
                    val newBgReading = InMemoryGlucoseValue(bgTime, readings[i].value, sourceSensor = lastBg.sourceSensor)
                    bData.add(newBgReading)
                    aapsLogger.debug(LTag.AUTOSENS) { "Adding. bgTime: ${dateUtil.toISOString(bgTime)} lastBgTime: ${dateUtil.toISOString(lastBgTime)} $newBgReading" }
                }

                else                    -> {
                    bData[j].value = (bData[j].value + readings[i].value) / 2
                }
            }
        }

        // Normalize bucketed data
        val oldest = bData[bData.size - 1]
        // A sensor change can put five-minute readings on a different phase. Keeping a
        // process-long anchor that far away would move them off their measured times.
        if (referenceTime != -1L && abs(adjustToReferenceTime(oldest.timestamp) - oldest.timestamp) > T.secs(90).msecs()) {
            aapsLogger.debug(LTag.AUTOSENS, "Reference time out of phase with current data. Re-anchoring.")
            referenceTime = -1
        }
        val rawOldest = oldest.timestamp
        oldest.timestamp = adjustToReferenceTime(oldest.timestamp)
        // The accumulated adjustment below includes the anchor shift. Remove that part
        // so the existing 90-second fallback still measures only sensor jitter.
        val anchorShift = (oldest.timestamp - rawOldest) / 1000
        aapsLogger.debug("Adjusted time " + dateUtil.dateAndTimeAndSecondsString(oldest.timestamp))
        for (i in bData.size - 2 downTo 0) {
            val current = bData[i]
            val previous = bData[i + 1]
            val mSecDiff = current.timestamp - previous.timestamp
            val adjusted = (mSecDiff - T.mins(5).msecs()) / 1000
            aapsLogger.debug(LTag.AUTOSENS) {
                "Adjusting bucketed data time. Current: ${dateUtil.dateAndTimeAndSecondsString(current.timestamp)} to: ${
                    dateUtil.dateAndTimeAndSecondsString(previous.timestamp + T.mins(5).msecs())
                } by $adjusted sec"
            }
            if (abs(adjusted + anchorShift) > 90) {
                // too big adjustment, fallback to non 5 min data
                aapsLogger.debug(LTag.AUTOSENS, "Fallback to non 5 min data")
                createBucketedDataRecalculated(aapsLogger, dateUtil)
                return
            }
            current.timestamp = previous.timestamp + T.mins(5).msecs()
        }
        aapsLogger.debug(LTag.AUTOSENS, "Bucketed data created. Size: " + bData.size)
        bucketedData = bData
    }

    override fun slowAbsorptionPercentage(timeInMinutes: Int): Double {
        var sum = 0.0
        var count = 0
        val valuesToProcess = timeInMinutes / 5
        synchronized(dataLock) {
            var i = autosensDataTable.size() - 1
            while (i >= 0 && count < valuesToProcess) {
                if (autosensDataTable.valueAt(i).failOverToMinAbsorptionRate) sum++
                count++
                i--
            }
        }
        return if (count != 0) sum / count else 0.0
    }
}
