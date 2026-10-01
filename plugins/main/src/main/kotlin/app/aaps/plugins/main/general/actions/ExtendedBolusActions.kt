package app.aaps.plugins.main.general.actions

import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.UIRunnable
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.plugins.main.R
import javax.inject.Inject

/** Starting and cancelling an extended bolus, shared by the Actions tab and the Home "+" menu. */
class ExtendedBolusActions @Inject constructor(
    private val rh: ResourceHelper,
    private val protectionCheck: ProtectionCheck,
    private val uiInteraction: UiInteraction,
    private val persistenceLayer: PersistenceLayer,
    private val commandQueue: CommandQueue,
    private val dateUtil: DateUtil,
    private val uel: UserEntryLogger
) {

    /** Owned by the caller so a confirmation left open by a destroyed screen cannot block the next one. */
    class CancelGuard {

        var open = false
    }

    fun start(activity: FragmentActivity, fragmentManager: FragmentManager) =
        protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
            OKDialog.showConfirmation(
                activity, rh.gs(app.aaps.core.ui.R.string.extended_bolus), rh.gs(R.string.ebstopsloop),
                { uiInteraction.runExtendedBolusDialog(fragmentManager) }, null
            )
        })

    fun confirmCancel(activity: FragmentActivity, source: Sources, guard: CancelGuard, onChanged: () -> Unit) {
        if (persistenceLayer.getExtendedBolusActiveAt(dateUtil.now()) == null || commandQueue.extendedBolusInQueue() || guard.open) return
        guard.open = true
        OKDialog.showConfirmation(
            activity,
            rh.gs(app.aaps.core.ui.R.string.cancel) + " " + rh.gs(app.aaps.core.ui.R.string.extended_bolus),
            rh.gs(R.string.confirm_cancel_extended_bolus),
            Runnable {
                guard.open = false
                if (commandQueue.extendedBolusInQueue()) return@Runnable
                uel.log(Action.CANCEL_EXTENDED_BOLUS, source)
                if (commandQueue.cancelExtended(object : Callback() {
                        override fun run() {
                            if (!result.success)
                                uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.extendedbolusdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                            activity.runOnUiThread(onChanged)
                        }
                    })) activity.runOnUiThread(onChanged)
            },
            Runnable { guard.open = false },
        )
    }
}
