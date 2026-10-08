package app.aaps.ui.dialogs

import io.reactivex.rxjava3.kotlin.plusAssign
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.formatColor
import app.aaps.core.ui.dialogs.DaggerBottomSheetFragment
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.ui.dialogs.compose.HoldConfirmDialog
import app.aaps.ui.dialogs.compose.PumpReadyGate
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.DeliveryUnavailable
import app.aaps.ui.dialogs.compose.InsulinEntryPolicy
import app.aaps.ui.dialogs.compose.InsulinInputs
import app.aaps.ui.dialogs.compose.InsulinIntent
import app.aaps.ui.dialogs.compose.TargetPreset
import app.aaps.ui.dialogs.compose.InsulinSheet
import app.aaps.ui.dialogs.compose.InsulinSheetState
import app.aaps.core.compose.components.formatNumeric
import com.google.common.base.Joiner
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Redesigned Insulin (careportal bolus) dialog. UI is Compose ([InsulinSheet]); [submit] runs the
 * SAME constraint + `OKDialog` confirmation + eating-soon TT + record / `commandQueue.bolus` path.
 */
class InsulinDialog : DaggerBottomSheetFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var ctx: Context
    @Inject lateinit var config: Config
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var loop: Loop
    @Inject lateinit var pumpReadyGate: PumpReadyGate
    @Inject lateinit var targetPresets: TargetPresets

    private var queryingProtection = false
    private var submitted = false
    private val disposable = CompositeDisposable()


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        isCancelable = true

        val bolusStep = activePlugin.activePump.pumpDescription.bolusStep
        val state = InsulinSheetState(
            maxInsulin = constraintChecker.getMaxBolusAllowed().value(),
            bolusStep = bolusStep,
            decimals = if (bolusStep < 0.1) 2 else 1,
            quickIncrements = listOf(
                preferences.get(DoubleKey.OverviewInsulinButtonIncrement1),
                preferences.get(DoubleKey.OverviewInsulinButtonIncrement2),
                preferences.get(DoubleKey.OverviewInsulinButtonIncrement3)
            ),
            deliveryUnavailable = deliveryUnavailable(),
            targets = targetPresets.options(),
            showNotes = preferences.get(BooleanKey.OverviewShowNotesInDialogs)
        )
        return sheetContent { InsulinSheet(state = state, onSubmit = ::submit, onClose = { dismiss() }) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
    }

    private fun deliveryUnavailable(): DeliveryUnavailable? {
        val pump = activePlugin.activePump
        return InsulinEntryPolicy.deliveryUnavailable(config.AAPSCLIENT, loop.runningMode.isPumpSuspended(), pump.isInitialized())
    }

    private fun submit(inputs: InsulinInputs): Boolean {
        if (submitted) return true
        if (!inputs.amount.isFinite() || inputs.amount < 0.0) return false
        // The pump may have stopped while the screen was open; never send a bolus it was not offered for.
        if (inputs.intent == InsulinIntent.DELIVER) deliveryUnavailable()?.let { reason ->
            activity?.let { OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.bolus), reason.label) }
            return false
        }
        submitted = true
        val insulin = inputs.amount
        val insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(insulin, aapsLogger)).value()
        val actions: LinkedList<String?> = LinkedList()
        val recordOnlyChecked = inputs.intent == InsulinIntent.LOG
        val target = targetPresets.resolve(inputs.target)

        if (insulinAfterConstraints > 0) {
            actions.add(
                (rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + formatNumeric(insulinAfterConstraints, 2) + " U")
                    .formatColor(context, rh, app.aaps.core.ui.R.attr.bolusColor)
            )
            if (recordOnlyChecked)
                actions.add(rh.gs(app.aaps.core.ui.R.string.bolus_recorded_only).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor))
        }
        if (insulinAfterConstraints != insulin)
            actions.add(("Requested ${formatNumeric(insulin, 2)} U; constrained to ${formatNumeric(insulinAfterConstraints, 2)} U").formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor))
        if (target != null) actions.add(targetPresets.confirmationLine(context, target))

        val time = InsulinEntryPolicy.eventTime(inputs.intent, dateUtil.now(), inputs.givenAt)
        if (recordOnlyChecked && insulinAfterConstraints > 0)
            actions.add(rh.gs(app.aaps.core.ui.R.string.time) + ": " + dateUtil.dateAndTimeString(time))
        val notes = inputs.notes
        if (notes.isNotEmpty())
            actions.add(rh.gs(app.aaps.core.ui.R.string.notes_label) + ": " + notes)

        if (insulinAfterConstraints > 0 || target != null) {
            activity?.let { activity ->
                val delivers = insulinAfterConstraints > 0 && !recordOnlyChecked
                // A dose that reaches the pump is pre-flighted BEFORE the hold-to-confirm. A record-only
                // entry (reconciling a dose already given by hand) never touches the pump.
                val confirm: (String, android.text.Spanned, Runnable) -> Unit =
                    if (delivers) { t2, m, r ->
                        val action = "Deliver " + formatNumeric(insulinAfterConstraints, 2) + " U"
                        pumpReadyGate.runWhenPumpCanDeliver(activity) { HoldConfirmDialog.show(activity, t2, m, r, action = action) }
                    }
                    else { t2, m, r -> OKDialog.showConfirmation(activity, t2, m, r) }
                val title = when {
                    insulinAfterConstraints <= 0 -> rh.gs(app.aaps.core.ui.R.string.temporary_target)
                    recordOnlyChecked            -> "Log insulin"
                    else                         -> rh.gs(app.aaps.core.ui.R.string.bolus)
                }
                var confirmed = false
                confirm(title, HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), Runnable {
                    if (confirmed) return@Runnable
                    confirmed = true
                    target?.let { disposable += targetPresets.start(it, Sources.InsulinDialog, note = notes) }
                    if (insulinAfterConstraints > 0) {
                        val detailedBolusInfo = DetailedBolusInfo()
                        detailedBolusInfo.eventType = TE.Type.CORRECTION_BOLUS
                        detailedBolusInfo.insulin = insulinAfterConstraints
                        detailedBolusInfo.context = context
                        detailedBolusInfo.notes = notes
                        detailedBolusInfo.timestamp = time
                        if (recordOnlyChecked) {
                            disposable += persistenceLayer.insertOrUpdateBolus(
                                bolus = detailedBolusInfo.createBolus(),
                                action = Action.BOLUS,
                                source = Sources.InsulinDialog,
                                note = rh.gs(app.aaps.core.ui.R.string.record) + if (notes.isNotEmpty()) ": $notes" else ""
                            ).subscribe()
                        } else {
                            uel.log(Action.BOLUS, Sources.InsulinDialog, notes, ValueWithUnit.Insulin(insulinAfterConstraints))
                            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                                override fun run() {
                                    if (!result.success)
                                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                                }
                            })
                        }
                    }
                })
            }
        } else
            activity?.let { activity ->
                OKDialog.show(activity, rh.gs(app.aaps.core.ui.R.string.bolus), rh.gs(app.aaps.core.ui.R.string.no_action_selected))
            }
        dismiss()
        return true
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
