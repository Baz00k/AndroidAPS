package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.Immutable
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TT
import java.util.concurrent.TimeUnit

enum class TtReason { EATING_SOON, ACTIVITY, HYPO, CUSTOM }

val TtReason.ttReason: TT.Reason
    get() = when (this) {
        TtReason.EATING_SOON -> TT.Reason.EATING_SOON
        TtReason.ACTIVITY    -> TT.Reason.ACTIVITY
        TtReason.HYPO        -> TT.Reason.HYPOGLYCEMIA
        TtReason.CUSTOM      -> TT.Reason.CUSTOM
    }

@Immutable
data class TtPreset(
    val reason: TtReason,
    val label: String,
    val target: Double,          // display units
    val durationMin: Int
)

/** The exact temporary target a confirmed sheet asks the persistence layer to store. */
@Immutable
data class TempTargetRequest(
    val target: Double,          // display units
    val durationMin: Int,
    val reason: TtReason
) {
    fun toTempTarget(timestamp: Long, units: GlucoseUnit): TT = TT(
        timestamp = timestamp,
        duration = TimeUnit.MINUTES.toMillis(durationMin.toLong()),
        reason = reason.ttReason,
        lowTarget = targetInMgdl(units),
        highTarget = targetInMgdl(units)
    )

    private fun targetInMgdl(units: GlucoseUnit): Double =
        if (units == GlucoseUnit.MGDL) target else target * GlucoseUnit.MMOLL_TO_MGDL
}

@Immutable
data class TempTargetSheetState(
    val presets: List<TtPreset> = emptyList(),
    val initialTarget: Double = 0.0,     // display units
    val initialDuration: Int = 0,
    val unitStep: Double = 0.1,          // 0.1 mmol / 1 mgdl
    val unitLabel: String = "mmol/L",
    val targetMin: Double = Constants.MIN_TT_MMOL,
    val targetMax: Double = Constants.MAX_TT_MMOL,
    val durationStep: Int = 5,
    val durationMax: Int = Constants.MAX_PROFILE_SWITCH_DURATION.toInt(),
    val decimals: Int = 1,
    val hasActive: Boolean = false
) {
    /** Only an in-range target with a positive duration can become a request. */
    fun request(target: Double, durationMin: Int, reason: TtReason): TempTargetRequest? =
        TempTargetRequest(target, durationMin, reason)
            .takeIf { target in targetMin..targetMax && durationMin in 1..durationMax }
}

/**
 * The sheet's editor state for [units]. [activeTargetMgdl] only reports that a target is running; its
 * value is deliberately never copied into the editor. A target started before the upstream range
 * narrowed can sit above [TempTargetSheetState.targetMax], and opening the sheet must neither rewrite
 * nor clamp it.
 */
fun tempTargetSheetState(
    units: GlucoseUnit,
    presets: List<TtPreset>,
    initialTarget: Double,
    initialDuration: Int,
    activeTargetMgdl: Double?
): TempTargetSheetState {
    val mmol = units == GlucoseUnit.MMOL
    return TempTargetSheetState(
        presets = presets,
        initialTarget = initialTarget,
        initialDuration = initialDuration,
        unitStep = if (mmol) 0.1 else 1.0,
        unitLabel = if (mmol) "mmol/L" else "mg/dL",
        targetMin = if (mmol) Constants.MIN_TT_MMOL else Constants.MIN_TT_MGDL,
        targetMax = if (mmol) Constants.MAX_TT_MMOL else Constants.MAX_TT_MGDL,
        durationStep = 5,
        durationMax = Constants.MAX_PROFILE_SWITCH_DURATION.toInt(),
        decimals = if (mmol) 1 else 0,
        hasActive = activeTargetMgdl != null
    )
}
