package app.aaps.ui.dialogs.compose

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import app.aaps.core.compose.components.AlertAction
import app.aaps.core.compose.components.AlertContent
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.ui.dialogs.ComposeDialogHost
import app.aaps.core.data.model.RM
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import javax.inject.Inject

/** What is standing between this dose and the pump, if anything. */
internal sealed interface DeliveryBlocker {

    /** The pump reports it is not delivering. Only the user, at the pump, can clear this. */
    data class PumpStopped(val reservoirEmpty: Boolean, val detail: String, val wording: PumpWording) : DeliveryBlocker

    /**
     * The user has declared the pump physically disconnected. Delivering now would be a dose into a
     * pump that is not on the body, so there is no "anyway"; only reconnecting, from right here, clears it.
     */
    data object PumpDisconnected : DeliveryBlocker

    /** AAPS is suspended (not disconnected). Reversible right here. */
    data class LoopSuspended(val mode: RM.Mode) : DeliveryBlocker
}

/**
 * Pure classification behind [PumpReadyGate]. The pump's own suspension wins over any running mode: it is
 * the one blocker this app cannot clear. [pumpStopped] is only evaluated then, so the pump is not
 * polled for status text it will not show.
 */
internal fun classifyBlocker(
    pumpSuspended: Boolean,
    mode: RM.Mode,
    pumpStopped: () -> DeliveryBlocker.PumpStopped
): DeliveryBlocker? = when {
    // The pump said it is not delivering, whether or not its latest status read agrees yet.
    pumpSuspended || mode == RM.Mode.SUSPENDED_BY_PUMP -> pumpStopped()
    mode == RM.Mode.DISCONNECTED_PUMP                  -> DeliveryBlocker.PumpDisconnected
    mode.isSuspended()                                 -> DeliveryBlocker.LoopSuspended(mode)
    else                                               -> null
}

/**
 * What a stopped pump is called, and what the user has to do about it, for the pump actually attached.
 *
 * A cartridge is changed and primed at the pump; a patch is resumed or replaced from its own tab. Telling
 * a patch user to prime a cartridge is not a cosmetic slip — it describes a device they do not have, in
 * the one dialog standing between them and a dose. [PumpDescription.isPatchPump] is the discriminator
 * every driver already fills in, so no driver has to know this screen exists.
 */
internal data class PumpWording(
    val emptyTitle: String,
    val emptyMessage: String,
    val stoppedTitle: String,
    val stoppedMessage: String
) {

    companion object {

        fun of(description: PumpDescription): PumpWording =
            if (description.isPatchPump)
                PumpWording(
                    emptyTitle = "Patch is empty",
                    emptyMessage = "The patch has no insulin left, so it will not accept this dose. Change the patch, then check again.",
                    stoppedTitle = "Patch is not delivering",
                    stoppedMessage = "The patch is not delivering, so it will refuse this dose. Resume or replace it from the pump tab, then check again."
                )
            else
                PumpWording(
                    emptyTitle = "Reservoir is empty",
                    emptyMessage = "The pump has no insulin left, so it will not accept this dose. Change the cartridge and prime, then check again.",
                    stoppedTitle = "Pump is stopped",
                    stoppedMessage = "The pump is not delivering, so it will refuse this dose. " +
                        "Start delivery on the pump itself${restartHint(description.pumpType)}, then check again."
                )

        /**
         * The menu path to restart delivery, for the pumps whose menus this fork has actually been run
         * against. Everything else gets the sentence without a path: a wrong menu path is worse than none.
         */
        private fun restartHint(pumpType: PumpType): String = when (pumpType) {
            PumpType.YPSOPUMP -> " (Menu ▸ Run)"
            else              -> ""
        }
    }
}

/**
 * Pre-flight for anything that is about to put insulin into the pump.
 *
 * A bolus into a stopped pump used to look like it was working — the wizard did its maths, the
 * progress dialog opened, and then it sat at 0% until the driver's confirm window expired. The pump
 * had refused the very first write. This gate turns that dead end into a decision the user can act
 * on, *before* anything is queued:
 *
 *  - **Pump stopped / reservoir empty** — the pump itself refuses to deliver. A cartridge pump cannot be
 *    restarted over BLE at all (the protocol has no such command, deliberately: starting a pump is a
 *    physical act); a patch is resumed or replaced from its own tab. Either way the fix is somewhere this
 *    dialog cannot reach, so the honest options are "fix it there, then Check again" or "Cancel" — see
 *    [PumpWording] for how each pump is addressed. Check again
 *    reconnects and re-reads status, which is also what clears a merely stale reading — and when the
 *    pump comes back healthy the dose goes ahead without the user re-entering it.
 *  - **Pump disconnected in the app** — the user said the pump is off the body. Reversible from here,
 *    so offer "Reconnect and bolus"; there is deliberately no "Bolus anyway".
 *  - **Loop suspended** — also reversible from here, so offer to resume. Not a hard block: suspending
 *    the loop is no reason to refuse a meal bolus, so "Bolus anyway" stays available.
 *  - **Anything else** — [runWhenPumpCanDeliver] just runs the action, with no extra tap.
 */
class PumpReadyGate @Inject constructor(
    private val activePlugin: ActivePlugin,
    private val commandQueue: CommandQueue,
    private val loop: Loop,
    private val profileFunction: ProfileFunction
) {

    /** Runs [proceed] once nothing blocks delivery; [onCancel] when the user walks away instead. */
    fun runWhenPumpCanDeliver(activity: FragmentActivity, onCancel: () -> Unit = {}, proceed: Runnable) {
        val blocker = detect()
        if (blocker == null) proceed.run() else showSheet(activity, blocker, proceed, onCancel)
    }

    private fun detect(): DeliveryBlocker? {
        val pump = activePlugin.activePump
        // isSuspended() is the pump's own answer, so this covers a user Stop, an occlusion stop and the
        // empty-cartridge auto-stop alike. The reservoir is read separately only to word the message.
        return classifyBlocker(pump.isSuspended(), loop.runningMode) {
            DeliveryBlocker.PumpStopped(
                reservoirEmpty = pump.reservoirLevel <= 0.0,
                detail = pump.pumpSpecificShortStatus(true),
                wording = PumpWording.of(pump.pumpDescription)
            )
        }
    }

    /** Same call the Loop sheet's Resume makes — mode change, audit log and all. */
    /**
     * Resume, then look again: the dose follows only if the mode change happened and nothing else now
     * blocks delivery. Restoring basal on the pump is queued ahead of the bolus; its own failure alarms
     * and cannot add insulin, so the bolus does not wait for it.
     */
    private fun resumeLoop(): Boolean {
        val profile = profileFunction.getProfile() ?: return false
        return loop.handleRunningModeChange(newRM = RM.Mode.RESUME, action = Action.RESUME, source = Sources.LoopDialog, profile = profile) &&
            detect() == null
    }

    private fun showSheet(activity: FragmentActivity, blocker: DeliveryBlocker, proceed: Runnable, onCancel: () -> Unit) {
        var closed = false
        // Only ever act once, and never on an activity that has gone away underneath a slow re-check.
        fun finish(dismiss: () -> Unit, runIt: Boolean, before: () -> Boolean = { true }) {
            if (closed) return
            closed = true
            dismiss()
            if (runIt && !activity.isFinishing && !activity.isDestroyed && before()) proceed.run() else onCancel()
        }

        // Every exit from this sheet has to be a deliberate choice. A "Check again" is in flight for as
        // long as it takes to connect; if a back press could close the sheet meanwhile, the callback would
        // still land and deliver a dose the user had walked away from.
        val shown = ComposeDialogHost.show(activity, cancelable = false) { dismiss ->
            PumpReadyContent(
                initial = blocker,
                recheck = { onResult ->
                    // The queue callback lands on the queue worker thread; Compose state has to be
                    // written from the main thread. A refused enqueue never calls back at all, which
                    // would leave the button stuck on "Checking…", so answer that case ourselves.
                    val main = Handler(Looper.getMainLooper())
                    val queued = commandQueue.readStatus("bolus pre-check", object : Callback() {
                        override fun run() {
                            main.post { if (!closed) onResult(detect()) }
                        }
                    })
                    if (!queued) main.post { if (!closed) onResult(detect()) }
                },
                onDismiss = { finish(dismiss, runIt = false) },
                onProceed = { finish(dismiss, runIt = true) },
                onResumeLoop = { finish(dismiss, runIt = true) { resumeLoop() } }
            )
        }
        // Nothing to show the question in, so nothing is delivered.
        if (!shown) finish({}, runIt = false)
    }
}

@Composable
private fun PumpReadyContent(
    initial: DeliveryBlocker,
    recheck: ((DeliveryBlocker?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onProceed: () -> Unit,
    onResumeLoop: () -> Unit
) {
    var checking by remember { mutableStateOf(false) }
    var blocker by remember { mutableStateOf(initial) }

    when (val b = blocker) {
        is DeliveryBlocker.PumpStopped   -> AlertContent(
            title = if (b.reservoirEmpty) b.wording.emptyTitle else b.wording.stoppedTitle,
            message = buildString {
                append(if (b.reservoirEmpty) b.wording.emptyMessage else b.wording.stoppedMessage)
                if (b.detail.isNotBlank()) append("\n\n").append(b.detail)
            },
            tint = AapsTheme.colors.low,
            actions = listOf(
                AlertAction(
                    if (checking) "Checking the pump…" else "Check again",
                    primary = true,
                    onClick = {
                        if (!checking) {
                            checking = true
                            recheck { still ->
                                checking = false
                                // Cleared while the sheet was open: deliver without making the user
                                // rebuild the dose. Still blocked: re-render with the current reason.
                                if (still == null) onProceed() else blocker = still
                            }
                        }
                    }
                ),
                AlertAction("Cancel", onClick = onDismiss)
            )
        )

        DeliveryBlocker.PumpDisconnected -> AlertContent(
            title = "Pump is disconnected",
            message = "Reconnect it in AAPS to deliver.",
            tint = AapsTheme.colors.low,
            actions = listOf(
                AlertAction("Reconnect and bolus", primary = true, onClick = onResumeLoop),
                AlertAction("Cancel", onClick = onDismiss)
            )
        )

        is DeliveryBlocker.LoopSuspended -> AlertContent(
            title = "Loop is suspended",
            message = "AAPS is ${suspensionLabel(b.mode)}, so it is not adjusting basal. The pump can still take this bolus.",
            tint = AapsTheme.colors.high,
            actions = listOf(
                AlertAction("Resume loop and bolus", primary = true, onClick = onResumeLoop),
                AlertAction("Bolus anyway", onClick = onProceed),
                AlertAction("Cancel", onClick = onDismiss)
            )
        )
    }
}

private fun suspensionLabel(mode: RM.Mode): String = when (mode) {
    RM.Mode.SUSPENDED_BY_USER -> "suspended by you"
    RM.Mode.SUSPENDED_BY_DST  -> "suspended for a clock change"
    RM.Mode.SUPER_BOLUS       -> "running a super bolus"
    else                      -> "suspended"
}
