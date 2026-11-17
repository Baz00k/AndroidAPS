package app.aaps.plugins.main.general.actions

import androidx.annotation.VisibleForTesting
import androidx.fragment.app.FragmentActivity
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.extensions.toStringShort
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.plugins.main.R
import javax.inject.Inject

/** Cancelling the active temp basal from a user tap. */
class TempBasalActions @Inject constructor(
    private val rh: ResourceHelper,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val commandQueue: CommandQueue,
    private val dateUtil: DateUtil,
    private val uel: UserEntryLogger,
    private val uiInteraction: UiInteraction
) {

    /** Owned by the caller so a confirmation left open by a destroyed screen cannot block the next one. */
    class CancelGuard {

        var open = false
    }

    @VisibleForTesting
    internal var confirm: (FragmentActivity, String, String, Runnable, Runnable) -> Unit =
        { activity, title, message, ok, cancel -> OKDialog.showConfirmation(activity, title, message, ok, cancel) }

    fun confirmCancel(activity: FragmentActivity, source: Sources, guard: CancelGuard) {
        if (guard.open) return
        val active = processedTbrEbData.getTempBasalIncludingConvertedExtended(dateUtil.now()) ?: return
        guard.open = true
        confirm(
            activity,
            rh.gs(app.aaps.core.ui.R.string.cancel) + " " + rh.gs(app.aaps.core.ui.R.string.tempbasal_label),
            rh.gs(R.string.confirm_cancel_temp_basal, active.toStringShort(rh)),
            Runnable {
                guard.open = false
                // It may have ended or been cancelled while the dialog was open.
                if (processedTbrEbData.getTempBasalIncludingConvertedExtended(dateUtil.now()) == null) return@Runnable
                uel.log(Action.CANCEL_TEMP_BASAL, source)
                commandQueue.cancelTempBasal(enforceNew = true, callback = object : Callback() {
                    override fun run() {
                        if (!result.success)
                            uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.temp_basal_delivery_error), app.aaps.core.ui.R.raw.boluserror)
                    }
                })
            },
            Runnable { guard.open = false }
        )
    }
}
