package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.Immutable
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TT
import java.util.concurrent.TimeUnit

enum class TtReason(val label: String, val record: TT.Reason) {
    EATING_SOON("Eating soon", TT.Reason.EATING_SOON),
    ACTIVITY("Activity", TT.Reason.ACTIVITY),
    HYPO("Hypo", TT.Reason.HYPOGLYCEMIA),
    CUSTOM("Custom", TT.Reason.CUSTOM)
}

/** A preset from Settings; [target] is in display units. */
@Immutable
data class TtPreset(val reason: TtReason, val target: Double, val durationMin: Int)

/** A temporary target as confirmed: [target] in [units], for [durationMin] minutes. */
data class TempTargetRequest(val target: Double, val durationMin: Int, val reason: TtReason, val units: GlucoseUnit) {

    val isValid: Boolean
        get() = target in TempTargetLimits.targetMin(units)..TempTargetLimits.targetMax(units) &&
            durationMin in TempTargetLimits.MIN_DURATION..TempTargetLimits.MAX_DURATION

    fun toRecord(now: Long): TT {
        val mgdl = if (units == GlucoseUnit.MMOL) target * GlucoseUnit.MMOLL_TO_MGDL else target
        return TT(
            timestamp = now,
            duration = TimeUnit.MINUTES.toMillis(durationMin.toLong()),
            reason = reason.record,
            lowTarget = mgdl,
            highTarget = mgdl
        )
    }
}

/** The range automation and Nightscout import enforce too. */
object TempTargetLimits {

    const val DURATION_STEP = 5
    const val MIN_DURATION = DURATION_STEP
    const val MAX_DURATION = Constants.MAX_PROFILE_SWITCH_DURATION.toInt()

    fun targetMin(units: GlucoseUnit) = if (units == GlucoseUnit.MMOL) Constants.MIN_TT_MMOL else Constants.MIN_TT_MGDL
    fun targetMax(units: GlucoseUnit) = if (units == GlucoseUnit.MMOL) Constants.MAX_TT_MMOL else Constants.MAX_TT_MGDL
}

/** A running target is only offered for cancelling; it never seeds or bounds the draft. */
@Immutable
data class TempTargetSheetState(
    val units: GlucoseUnit,
    val presets: List<TtPreset>,
    val hasActive: Boolean
) {

    private val mmol get() = units == GlucoseUnit.MMOL
    val initial: TtPreset = presets.firstOrNull() ?: TtPreset(TtReason.CUSTOM, TempTargetLimits.targetMin(units), 0)
    val targetMin get() = TempTargetLimits.targetMin(units)
    val targetMax get() = TempTargetLimits.targetMax(units)
    val targetStep get() = if (mmol) 0.1 else 1.0
    val decimals get() = if (mmol) 1 else 0
    val unitLabel get() = if (mmol) "mmol/L" else "mg/dL"
}
