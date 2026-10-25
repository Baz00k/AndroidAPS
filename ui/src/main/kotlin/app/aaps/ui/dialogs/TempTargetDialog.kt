package app.aaps.ui.dialogs

import io.reactivex.rxjava3.kotlin.plusAssign
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.dialogs.DaggerBottomSheetFragment
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.TempTargetRequest
import app.aaps.ui.dialogs.compose.TempTargetSheet
import app.aaps.ui.dialogs.compose.TempTargetSheetState
import app.aaps.ui.dialogs.compose.TtPreset
import app.aaps.ui.dialogs.compose.TtReason
import app.aaps.ui.dialogs.compose.ttReason
import app.aaps.ui.dialogs.compose.tempTargetSheetState
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject

/**
 * Redesigned Temp target sheet. UI is Compose ([TempTargetSheet]); starting/cancelling a temp target
 * reuses the SAME `persistenceLayer.insertAndCancelCurrentTemporaryTarget` /
 * `cancelCurrentTemporaryTargetIfAny` path behind an `OKDialog` confirmation. DI + `runTempTargetDialog`
 * routing unchanged.
 */
class TempTargetDialog : DaggerBottomSheetFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var ctx: Context
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var protectionCheck: ProtectionCheck

    private val disposable = CompositeDisposable()
    private var queryingProtection = false


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        isCancelable = true

        return sheetContent {
            TempTargetSheet(
                state = buildState(),
                onStart = ::start,
                onCancelActive = ::cancelActive,
                onClose = { dismiss() }
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
    }

    private val mmol get() = profileFunction.getUnits() == GlucoseUnit.MMOL

    private fun buildState(): TempTargetSheetState {
        fun preset(reason: TtReason, label: String, target: Double, dur: Int) =
            TtPreset(reason, label, target, dur)

        val active = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        return tempTargetSheetState(
            units = profileFunction.getUnits(),
            presets = listOf(
                preset(TtReason.EATING_SOON, rh.gs(app.aaps.core.ui.R.string.eatingsoon), preferences.get(UnitDoubleKey.OverviewEatingSoonTarget), preferences.get(IntKey.OverviewEatingSoonDuration)),
                preset(TtReason.ACTIVITY, rh.gs(app.aaps.core.ui.R.string.activity), preferences.get(UnitDoubleKey.OverviewActivityTarget), preferences.get(IntKey.OverviewActivityDuration)),
                preset(TtReason.HYPO, rh.gs(app.aaps.core.ui.R.string.hypo), preferences.get(UnitDoubleKey.OverviewHypoTarget), preferences.get(IntKey.OverviewHypoDuration))
            ),
            initialTarget = preferences.get(UnitDoubleKey.OverviewEatingSoonTarget),
            initialDuration = preferences.get(IntKey.OverviewEatingSoonDuration),
            // Presence only: a running target may be outside the upstream range and is never rewritten.
            activeTargetMgdl = active?.lowTarget
        )
    }

    private fun start(request: TempTargetRequest) {
        val activity = activity ?: return
        val units = profileFunction.getUnits()
        val unitLabel = if (mmol) "mmol/L" else "mg/dL"
        val summary = rh.gs(app.aaps.core.ui.R.string.target_label) + ": " + profileUtil.stringInCurrentUnitsDetect(request.target) + " " + unitLabel +
            "\n" + rh.gs(app.aaps.core.ui.R.string.duration) + ": " + rh.gs(app.aaps.core.ui.R.string.format_mins, request.durationMin)
        OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.temporary_target), summary, {
            disposable += persistenceLayer.insertAndCancelCurrentTemporaryTarget(
                request.toTempTarget(dateUtil.now(), units),
                action = Action.TT,
                source = Sources.TTDialog,
                note = null,
                listValues = listOf(
                    ValueWithUnit.TETTReason(request.reason.ttReason),
                    ValueWithUnit.fromGlucoseUnit(request.target, units),
                    ValueWithUnit.Minute(request.durationMin)
                )
            ).subscribe()
            if (request.durationMin == 10) preferences.put(BooleanNonKey.ObjectivesTempTargetUsed, true)
        })
        dismiss()
    }

    private fun cancelActive() {
        val activity = activity ?: return
        OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.temporary_target), rh.gs(app.aaps.core.ui.R.string.stoptemptarget), {
            disposable += persistenceLayer.cancelCurrentTemporaryTargetIfAny(
                timestamp = dateUtil.now(),
                action = Action.TT,
                source = Sources.TTDialog,
                note = null,
                listValues = listOf()
            ).subscribe()
        })
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
