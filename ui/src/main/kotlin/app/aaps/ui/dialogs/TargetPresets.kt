package app.aaps.ui.dialogs

import android.content.Context
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TT
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.formatColor
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.TargetPreset
import app.aaps.ui.dialogs.compose.TargetPresetOption
import io.reactivex.rxjava3.disposables.Disposable
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * The quick temporary targets from Settings (eating soon, activity, hypo), offered the same way on
 * every entry screen that can start one.
 */
class TargetPresets @Inject constructor(
    private val preferences: Preferences,
    private val profileUtil: ProfileUtil,
    private val decimalFormatter: DecimalFormatter,
    private val rh: ResourceHelper,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil
) {

    private fun target(preset: TargetPreset): Double = when (preset) {
        TargetPreset.EATING_SOON -> preferences.get(UnitDoubleKey.OverviewEatingSoonTarget)
        TargetPreset.ACTIVITY    -> preferences.get(UnitDoubleKey.OverviewActivityTarget)
        TargetPreset.HYPO        -> preferences.get(UnitDoubleKey.OverviewHypoTarget)
        TargetPreset.NONE        -> 0.0
    }

    private fun durationMin(preset: TargetPreset): Int = when (preset) {
        TargetPreset.EATING_SOON -> preferences.get(IntKey.OverviewEatingSoonDuration)
        TargetPreset.ACTIVITY    -> preferences.get(IntKey.OverviewActivityDuration)
        TargetPreset.HYPO        -> preferences.get(IntKey.OverviewHypoDuration)
        TargetPreset.NONE        -> 0
    }

    private fun reason(preset: TargetPreset): TT.Reason = when (preset) {
        TargetPreset.EATING_SOON -> TT.Reason.EATING_SOON
        TargetPreset.ACTIVITY    -> TT.Reason.ACTIVITY
        TargetPreset.HYPO        -> TT.Reason.HYPOGLYCEMIA
        TargetPreset.NONE        -> TT.Reason.CUSTOM
    }

    /** A preset with its values fixed: what the confirmation shows is exactly what is stored. */
    data class Resolved(val reason: TT.Reason, val targetMgdl: Double, val durationMin: Int, val summary: String)

    /** "90 mg/dL · 45 min" */
    private fun summary(preset: TargetPreset): String {
        val units = profileUtil.units
        val value = target(preset).let { if (units == GlucoseUnit.MMOL) decimalFormatter.to1Decimal(it) else decimalFormatter.to0Decimal(it) }
        val unit = if (units == GlucoseUnit.MMOL) rh.gs(app.aaps.core.ui.R.string.mmol) else rh.gs(app.aaps.core.ui.R.string.mgdl)
        return "$value $unit · ${rh.gs(app.aaps.core.ui.R.string.format_mins, durationMin(preset))}"
    }

    fun options(): List<TargetPresetOption> =
        listOf(TargetPreset.EATING_SOON, TargetPreset.ACTIVITY, TargetPreset.HYPO).map { TargetPresetOption(it, summary(it)) }

    fun resolve(preset: TargetPreset): Resolved? =
        if (preset == TargetPreset.NONE) null
        else Resolved(reason(preset), profileUtil.convertToMgdl(target(preset), profileUtil.units), durationMin(preset), summary(preset))

    /** The line this target adds to a confirmation. */
    fun confirmationLine(context: Context?, target: Resolved): String =
        rh.gs(R.string.temp_target_short) + ": " + target.summary.formatColor(context, rh, app.aaps.core.ui.R.attr.tempTargetConfirmation)

    /** Start [target] now, replacing any running target. */
    fun start(target: Resolved, source: Sources, note: String?): Disposable =
        persistenceLayer.insertAndCancelCurrentTemporaryTarget(
            temporaryTarget = TT(
                timestamp = dateUtil.now(),
                duration = TimeUnit.MINUTES.toMillis(target.durationMin.toLong()),
                reason = target.reason,
                lowTarget = target.targetMgdl,
                highTarget = target.targetMgdl
            ),
            action = Action.TT,
            source = source,
            note = note,
            listValues = listOf(
                ValueWithUnit.TETTReason(target.reason),
                ValueWithUnit.Mgdl(target.targetMgdl),
                ValueWithUnit.Minute(target.durationMin)
            )
        ).subscribe()
}
