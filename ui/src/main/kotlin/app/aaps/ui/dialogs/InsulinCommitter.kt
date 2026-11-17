package app.aaps.ui.dialogs

import android.content.Context
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.ui.dialogs.compose.DeliveryUnavailable
import app.aaps.ui.dialogs.compose.InsulinEntryPolicy
import app.aaps.ui.dialogs.compose.InsulinInputs
import app.aaps.ui.dialogs.compose.InsulinReview
import javax.inject.Inject

/**
 * Builds and commits the Insulin sheet's [InsulinReview]. One instance per sheet: it commits at most
 * once, and only what was reviewed.
 */
class InsulinCommitter @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val constraintChecker: ConstraintsChecker,
    private val commandQueue: CommandQueue,
    private val uel: UserEntryLogger,
    private val persistenceLayer: PersistenceLayer,
    private val targetPresets: TargetPresets,
    private val dateUtil: DateUtil,
    private val uiInteraction: UiInteraction,
    private val rh: ResourceHelper,
    private val config: Config,
    private val loop: Loop,
    private val activePlugin: ActivePlugin
) {

    sealed interface Result {
        data object Done : Result
        /** Nothing was sent: the entry no longer matches what was reviewed. */
        data class Changed(val review: InsulinReview) : Result
        /** Nothing was sent: the pump cannot be asked for a bolus now. */
        data class Unavailable(val reason: DeliveryUnavailable) : Result
        /** The user walked away from the pump pre-flight. */
        data object Cancelled : Result
    }

    /** Set on the first commit and released only when nothing was done, so a repeated confirmation is ignored. */
    private var committing = false

    fun deliveryUnavailable(): DeliveryUnavailable? =
        InsulinEntryPolicy.deliveryUnavailable(config.AAPSCLIENT, loop.runningMode.isPumpSuspended(), activePlugin.activePump.isInitialized())

    fun review(inputs: InsulinInputs): InsulinReview {
        val valid = inputs.amount.isFinite() && inputs.amount >= 0.0
        val insulin = if (valid && inputs.amount > 0.0) constraintChecker.applyBolusConstraints(ConstraintObject(inputs.amount, aapsLogger)).value() else 0.0
        return InsulinReview(
            requested = if (valid) inputs.amount else 0.0,
            insulin = insulin,
            intent = inputs.intent,
            target = if (valid) targetPresets.resolve(inputs.target) else null
        )
    }

    /**
     * Commit [reviewed], once. The review is rebuilt from current data before and again after the pump
     * pre-flight [gate], which can wait on the user; if it moved, or the pump can no longer be asked
     * for a bolus, nothing is sent and [onResult] says why. Logging and a target alone need no pump.
     */
    fun commit(
        inputs: InsulinInputs,
        reviewed: InsulinReview,
        context: Context?,
        gate: (proceed: () -> Unit, cancel: () -> Unit) -> Unit,
        onResult: (Result) -> Unit
    ) {
        if (committing) return
        committing = true
        fun rejection(): Result? {
            val fresh = review(inputs)
            return when {
                fresh != reviewed || fresh.commit == InsulinReview.Commit.NONE -> Result.Changed(fresh)
                fresh.commit == InsulinReview.Commit.DELIVER                  -> deliveryUnavailable()?.let { Result.Unavailable(it) }
                else                                                          -> null
            }
        }

        fun finish(result: Result) {
            if (result != Result.Done) committing = false
            onResult(result)
        }

        fun execute() {
            rejection()?.let { return finish(it) }
            execute(inputs, reviewed, context)
            finish(Result.Done)
        }
        rejection()?.let { return finish(it) }
        if (reviewed.commit == InsulinReview.Commit.DELIVER) gate(::execute) { finish(Result.Cancelled) }
        else execute()
    }

    private fun execute(inputs: InsulinInputs, review: InsulinReview, context: Context?) {
        val notes = inputs.notes
        // The writes run on the IO scheduler; they are not tied to the sheet, which closes right after.
        review.target?.let { targetPresets.start(it, Sources.InsulinDialog, note = notes) }
        if (review.insulin <= 0.0) return
        val detailedBolusInfo = DetailedBolusInfo().apply {
            eventType = TE.Type.CORRECTION_BOLUS
            insulin = review.insulin
            this.context = context
            this.notes = notes
            timestamp = InsulinEntryPolicy.eventTime(review.intent, dateUtil.now(), inputs.givenAt)
        }
        if (review.commit == InsulinReview.Commit.LOG)
            persistenceLayer.insertOrUpdateBolus(
                bolus = detailedBolusInfo.createBolus(),
                action = Action.BOLUS,
                source = Sources.InsulinDialog,
                note = rh.gs(app.aaps.core.ui.R.string.record) + if (notes.isNotEmpty()) ": $notes" else ""
            ).subscribe()
        else {
            uel.log(Action.BOLUS, Sources.InsulinDialog, notes, ValueWithUnit.Insulin(review.insulin))
            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                override fun run() {
                    if (!result.success)
                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                }
            })
        }
    }
}
