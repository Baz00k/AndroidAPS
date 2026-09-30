package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.Immutable
import app.aaps.core.data.time.T
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The glucose the Calculator works from, and whether it may drive a correction. Only a fresh sensor
 * reading or a value the user entered is used; an old reading is shown for context but never corrects.
 */
@Immutable
sealed interface CalculatorGlucose {

    /** mg/dL behind the correction and trend, or null when there is nothing trustworthy to correct from. */
    val usedMgdl: Double?

    data class Sensor(val mgdl: Double, val timestamp: Long) : CalculatorGlucose {

        override val usedMgdl: Double get() = mgdl
    }

    data class Stale(val mgdl: Double, val timestamp: Long) : CalculatorGlucose {

        override val usedMgdl: Double? get() = null
    }

    data class Manual(val mgdl: Double) : CalculatorGlucose {

        override val usedMgdl: Double get() = mgdl
    }

    data object None : CalculatorGlucose {

        override val usedMgdl: Double? get() = null
    }

    companion object {

        /** The same freshness rule as `AutosensDataStore.actualBg()`, which the loop itself uses. */
        val MAX_SENSOR_AGE_MS = T.mins(9).msecs()

        /** Clock skew tolerated between sensor and phone. Anything further ahead has no trustworthy time. */
        val MAX_SENSOR_AHEAD_MS = T.mins(1).msecs()

        fun resolve(manualMgdl: Double?, sensorMgdl: Double?, sensorTimestamp: Long?, now: Long): CalculatorGlucose = when {
            manualMgdl != null                                                   -> if (usable(manualMgdl)) Manual(manualMgdl) else None
            sensorMgdl == null || sensorTimestamp == null || !usable(sensorMgdl) -> None
            sensorTimestamp > now + MAX_SENSOR_AHEAD_MS                          -> Stale(sensorMgdl, sensorTimestamp)
            sensorTimestamp > now - MAX_SENSOR_AGE_MS                            -> Sensor(sensorMgdl, sensorTimestamp)
            else                                                                 -> Stale(sensorMgdl, sensorTimestamp)
        }

        private fun usable(mgdl: Double) = mgdl.isFinite() && mgdl > 0.0
    }
}

/**
 * What confirming the Calculator will actually do. [insulin] is the constrained, pump-rounded dose;
 * [uncappedInsulin] is set only when a limit reduced it. [carbEquivalent] (grams) is set when active
 * insulin already exceeds what the inputs need — the Calculator's own result, not the loop's
 * carbs-required alarm.
 */
@Immutable
data class CalculatorOutcome(
    val insulin: Double = 0.0,
    val uncappedInsulin: Double? = null,
    val carbs: Int = 0,
    val carbEquivalent: Int? = null
) {

    enum class Commit { DELIVER, LOG_CARBS, NONE }

    val commit: Commit
        get() = when {
            insulin > 0.0 -> Commit.DELIVER
            carbs > 0     -> Commit.LOG_CARBS
            else          -> Commit.NONE
        }

    companion object {

        fun of(calculatedInsulin: Double, insulinAfterConstraints: Double, carbsEquivalent: Double, carbs: Int, bolusStep: Double) =
            CalculatorOutcome(
                insulin = insulinAfterConstraints.coerceAtLeast(0.0),
                uncappedInsulin = calculatedInsulin.takeIf { it - insulinAfterConstraints > bolusStep / 2 },
                carbs = carbs,
                carbEquivalent = carbsEquivalent.roundToInt().takeIf { it > 0 }
            )
    }
}

/**
 * Whether the dose recomputed at the moment of confirmation differs from the one the user reviewed.
 * Both are rounded to the pump step, so any real change is at least one step; half a step absorbs
 * floating-point noise. A change means nothing is sent: the user reviews the new amount first.
 */
object DoseDrift {

    fun changed(reviewed: CalculatorOutcome, recomputed: CalculatorOutcome, bolusStep: Double): Boolean =
        abs(reviewed.insulin - recomputed.insulin) > bolusStep / 2 || reviewed.carbs != recomputed.carbs
}
