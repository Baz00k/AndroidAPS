package app.aaps.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.dialogs.DaggerBottomSheetFragment
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.InsulinInputs
import app.aaps.ui.dialogs.compose.InsulinReview
import app.aaps.ui.dialogs.compose.InsulinSheet
import app.aaps.ui.dialogs.compose.InsulinSheetState
import app.aaps.ui.dialogs.compose.PumpReadyGate
import javax.inject.Inject

/**
 * The Insulin (manual bolus) sheet. The UI is Compose ([InsulinSheet]) and confirms in its own Review
 * step, like the Calculator; [InsulinCommitter] commits exactly what was reviewed, behind [PumpReadyGate]
 * for a dose that reaches the pump.
 */
class InsulinDialog : DaggerBottomSheetFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var ctx: Context
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var pumpReadyGate: PumpReadyGate
    @Inject lateinit var targetPresets: TargetPresets
    @Inject lateinit var committer: InsulinCommitter

    private var queryingProtection = false

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
            deliveryUnavailable = committer.deliveryUnavailable(),
            targets = targetPresets.options(),
            showNotes = preferences.get(BooleanKey.OverviewShowNotesInDialogs)
        )
        return sheetContent { InsulinSheet(state = state, review = committer::review, onCommit = ::commit, onClose = { dismiss() }) }
    }

    private fun commit(inputs: InsulinInputs, reviewed: InsulinReview, onChanged: (InsulinReview) -> Unit) {
        val activity = activity ?: return
        committer.commit(
            inputs, reviewed, activity,
            gate = { proceed, cancel -> pumpReadyGate.runWhenPumpCanDeliver(activity, onCancel = cancel) { proceed() } }
        ) { result ->
            when (result) {
                InsulinCommitter.Result.Done           -> dismiss()
                is InsulinCommitter.Result.Changed     -> onChanged(result.review)
                // The pump stopped while the screen was open; never send a bolus it was not offered for.
                is InsulinCommitter.Result.Unavailable -> OKDialog.show(activity, rh.gs(app.aaps.core.ui.R.string.bolus), result.reason.label)
                InsulinCommitter.Result.Cancelled      -> Unit
            }
        }
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
