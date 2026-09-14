package app.aaps.plugins.aps.loop

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.text.toSpanned
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.RM
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAcceptOpenLoopChange
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.ui.UIRunnable
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.utils.HtmlHelper
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.loop.compose.LoopStatusRow
import app.aaps.plugins.aps.loop.compose.LoopStatusScreen
import app.aaps.plugins.aps.loop.compose.LoopStatusState
import app.aaps.plugins.aps.loop.compose.openLoopSuggestionPending
import app.aaps.plugins.aps.extensions.toHtml
import app.aaps.plugins.aps.loop.events.EventLoopSetLastRunGui
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

/**
 * Loop tab. The legacy label/value table (loop_fragment.xml, swipe-to-refresh + a "Run now" overflow
 * item) is replaced by [LoopStatusScreen]; "Run now" still goes through the same
 * `loop.invoke(..., allowNotification = true)` call on the same background handler.
 * While an unaccepted open-loop suggestion exists, "Accept temp basal" is offered and follows the
 * legacy Overview button path: refresh the loop, BOLUS-protected confirmation, then
 * `loop.acceptChangeRequest()`.
 */
class LoopFragment : DaggerFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var loop: Loop
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uel: UserEntryLogger

    private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)
    private var disposable: CompositeDisposable = CompositeDisposable()

    private val state = mutableStateOf(LoopStatusState())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { LoopStatusScreen(state.value, onRunNow = ::runNow, onAccept = ::acceptSuggestion) } }
        }

    private fun runNow() {
        state.value = state.value.copy(lastRun = rh.gs(R.string.executing), running = true)
        handler.post { loop.invoke("Loop menu", true) }
    }

    /** Same two-stage flow as the legacy Overview accept_temp_button: rerun, confirm, then accept. */
    private fun acceptSuggestion() {
        profileFunction.getProfile() ?: return
        if (!(loop as PluginBase).isEnabled()) return
        handler.post {
            loop.invoke("Accept temp button", false)
            val lastRun = loop.lastRun
            if (lastRun?.lastAPSRun != null && lastRun.constraintsProcessed?.isChangeRequested == true) {
                activity?.let { act ->
                    act.runOnUiThread {
                        protectionCheck.queryProtection(act, ProtectionCheck.Protection.BOLUS, UIRunnable {
                            if (isAdded)
                                OKDialog.showConfirmation(
                                    act,
                                    rh.gs(app.aaps.core.ui.R.string.tempbasal_label),
                                    lastRun.constraintsProcessed?.resultAsSpanned() ?: "".toSpanned(),
                                    {
                                        uel.log(Action.ACCEPTS_TEMP_BASAL, Sources.Loop)
                                        (context?.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?)?.cancel(Constants.notificationID)
                                        handler.post { loop.acceptChangeRequest() }
                                    }
                                )
                        })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventLoopUpdateGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGUI() }, fabricPrivacy::logException)

        disposable += rxBus
            .toObservable(EventLoopSetLastRunGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ state.value = LoopStatusState(lastRun = it.text) }, fabricPrivacy::logException)

        disposable += rxBus
            .toObservable(EventAcceptOpenLoopChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGUI() }, fabricPrivacy::logException)

        updateGUI()
        preferences.put(BooleanNonKey.ObjectivesLoopUsed, true)
    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    @Synchronized
    fun updateGUI() {
        val lastRun = loop.lastRun ?: return

        var constraints =
            lastRun.constraintsProcessed?.let { constraintsProcessed ->
                val allConstraints = ConstraintObject(0.0, aapsLogger)
                constraintsProcessed.rateConstraint?.let { rateConstraint -> allConstraints.copyReasons(rateConstraint) }
                constraintsProcessed.smbConstraint?.let { smbConstraint -> allConstraints.copyReasons(smbConstraint) }
                allConstraints.getMostLimitedReasons()
            } ?: ""
        constraints += loop.closedLoopEnabled?.getReasons() ?: ""

        val suggestionPending = openLoopSuggestionPending(
            lastRun = lastRun,
            constraintsProcessed = lastRun.constraintsProcessed,
            pumpInitialized = activePlugin.activePump.isInitialized(),
            openLoop = loop.runningMode == RM.Mode.OPEN_LOOP,
            loopEnabled = (loop as PluginBase).isEnabled()
        )

        state.value = LoopStatusState(
            lastRun = dateUtil.dateAndTimeString(lastRun.lastAPSRun),
            source = lastRun.source ?: "",
            running = false,
            suggestion = if (suggestionPending) lastRun.constraintsProcessed?.resultAsSpanned() ?: "" else "",
            detail = listOf(
                LoopStatusRow(rh.gs(R.string.request_label), lastRun.request?.resultAsSpanned() ?: ""),
                LoopStatusRow(rh.gs(R.string.loop_constraints_processed_label), lastRun.constraintsProcessed?.resultAsSpanned() ?: ""),
                LoopStatusRow(rh.gs(R.string.constraints), constraints)
            ),
            timing = listOf(
                LoopStatusRow(rh.gs(R.string.loop_tbr_request_time_label), dateUtil.dateAndTimeAndSecondsString(lastRun.lastTBRRequest)),
                LoopStatusRow(rh.gs(R.string.loop_tbr_execution_time_label), dateUtil.dateAndTimeAndSecondsString(lastRun.lastTBREnact)),
                LoopStatusRow(
                    rh.gs(R.string.loop_tbr_set_by_pump_label),
                    lastRun.tbrSetByPump?.let { HtmlHelper.fromHtml(it.toHtml(rh, decimalFormatter)) } ?: ""
                ),
                LoopStatusRow(rh.gs(R.string.loop_smb_request_time_label), dateUtil.dateAndTimeAndSecondsString(lastRun.lastSMBRequest)),
                LoopStatusRow(rh.gs(R.string.loop_smb_execution_time_label), dateUtil.dateAndTimeAndSecondsString(lastRun.lastSMBEnact)),
                LoopStatusRow(
                    rh.gs(R.string.loop_smb_set_by_pump_label),
                    lastRun.smbSetByPump?.let { HtmlHelper.fromHtml(it.toHtml(rh, decimalFormatter)) } ?: ""
                )
            )
        )
    }
}
