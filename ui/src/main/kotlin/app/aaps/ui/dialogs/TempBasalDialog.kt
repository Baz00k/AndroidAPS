package app.aaps.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.formatColor
import app.aaps.core.ui.dialogs.DaggerBottomSheetFragment
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.TempBasalInputs
import app.aaps.ui.dialogs.compose.TempBasalSheet
import app.aaps.ui.dialogs.compose.TempBasalSheetState
import com.google.common.base.Joiner
import java.util.LinkedList
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/** One confirmation's command: the constrained rate, shown and sent alike. */
internal data class TempBasalRequest(val isPercent: Boolean, val percent: Int, val absolute: Double, val durationMin: Int, val constrained: Boolean)

internal fun tempBasalRequest(inputs: TempBasalInputs, constrainPercent: (Int) -> Int, constrainAbsolute: (Double) -> Double): TempBasalRequest =
    if (inputs.isPercent) {
        val requested = inputs.value.roundToInt()
        val percent = constrainPercent(requested)
        TempBasalRequest(isPercent = true, percent = percent, absolute = 0.0, durationMin = inputs.durationMin, constrained = percent != requested)
    } else {
        val absolute = constrainAbsolute(inputs.value)
        TempBasalRequest(isPercent = false, percent = 0, absolute = absolute, durationMin = inputs.durationMin, constrained = abs(absolute - inputs.value) > 0.01)
    }

class TempBasalDialog : DaggerBottomSheetFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var ctx: Context
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uiInteraction: UiInteraction

    private var queryingProtection = false


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        isCancelable = true

        return sheetContent { TempBasalSheet(state = buildState(), onSubmit = ::submit, onClose = { dismiss() }) }
    }

    private fun buildState(): TempBasalSheetState {
        val pumpDescription = activePlugin.activePump.pumpDescription
        val profile = profileFunction.getProfile()
        val isPercentPump = pumpDescription.tempBasalStyle and PumpDescription.PERCENT == PumpDescription.PERCENT
        val isAbsolutePump = pumpDescription.tempBasalStyle and PumpDescription.ABSOLUTE == PumpDescription.ABSOLUTE
        val tempDurationStep = pumpDescription.tempDurationStep.toDouble()
        return TempBasalSheetState(
            percentAllowed = isPercentPump,
            absoluteAllowed = isAbsolutePump,
            defaultIsPercent = isPercentPump,
            percentDefault = 100.0,
            percentMax = pumpDescription.maxTempPercent.toDouble(),
            percentStep = pumpDescription.tempPercentStep.toDouble(),
            absoluteDefault = profile?.getBasal() ?: 0.0,
            absoluteMax = pumpDescription.maxTempAbsolute,
            absoluteStep = pumpDescription.tempAbsoluteStep,
            absoluteDecimals = 2,
            durationDefault = tempDurationStep,
            durationMax = pumpDescription.tempMaxDuration.toDouble(),
            durationStep = tempDurationStep
        )
    }

    private fun submit(inputs: TempBasalInputs) {
        // The sheet latches on submit; close it rather than leave a dead sheet behind.
        val profile = profileFunction.getProfile() ?: return dismiss()
        val request = tempBasalRequest(
            inputs,
            constrainPercent = { constraintChecker.applyBasalPercentConstraints(ConstraintObject(it, aapsLogger), profile).value() },
            constrainAbsolute = { constraintChecker.applyBasalConstraints(ConstraintObject(it, aapsLogger), profile).value() }
        )
        val percent = request.percent
        val absolute = request.absolute
        val durationInMinutes = request.durationMin
        val isPercentPump = request.isPercent
        val actions: LinkedList<String> = LinkedList()
        actions.add(
            rh.gs(app.aaps.core.ui.R.string.tempbasal_label) + ": " +
                if (isPercentPump) "$percent%" else rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, absolute)
        )
        actions.add(rh.gs(app.aaps.core.ui.R.string.duration) + ": " + rh.gs(app.aaps.core.ui.R.string.format_mins, durationInMinutes))
        if (request.constrained)
            actions.add(rh.gs(app.aaps.core.ui.R.string.constraint_applied).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor))
        activity?.let { activity ->
            OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.tempbasal_label), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                val callback: Callback = object : Callback() {
                    override fun run() {
                        if (!result.success) {
                            uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.temp_basal_delivery_error), app.aaps.core.ui.R.raw.boluserror)
                        }
                    }
                }
                if (isPercentPump) {
                    uel.log(
                        action = Action.TEMP_BASAL, source = Sources.TempBasalDialog,
                        listValues = listOf(
                            ValueWithUnit.Percent(percent),
                            ValueWithUnit.Minute(durationInMinutes)
                        )
                    )
                    commandQueue.tempBasalPercent(percent, durationInMinutes, true, profile, PumpSync.TemporaryBasalType.NORMAL, callback)
                } else {
                    uel.log(
                        action = Action.TEMP_BASAL, source = Sources.TempBasalDialog,
                        listValues = listOf(
                            ValueWithUnit.Insulin(absolute),
                            ValueWithUnit.Minute(durationInMinutes)
                        )
                    )
                    commandQueue.tempBasalAbsolute(absolute, durationInMinutes, true, profile, PumpSync.TemporaryBasalType.NORMAL, callback)
                }
            })
        }
        dismiss()
    }

    override fun onResume() {
        super.onResume()
        if (!queryingProtection) {
            queryingProtection = true
            activity?.let { activity ->
                val cancelFail = {
                    queryingProtection = false
                    aapsLogger.debug(LTag.APS, "Dialog canceled on resume protection: ${this.javaClass.simpleName}")
                    ToastUtils.warnToast(ctx, R.string.dialog_canceled)
                    dismiss()
                }
                protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, { queryingProtection = false }, cancelFail, cancelFail)
            }
        }
    }
}
