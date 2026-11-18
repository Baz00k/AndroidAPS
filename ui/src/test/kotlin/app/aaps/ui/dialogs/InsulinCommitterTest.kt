package app.aaps.ui.dialogs

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.ui.dialogs.InsulinCommitter.Result
import app.aaps.ui.dialogs.compose.DeliveryUnavailable
import app.aaps.ui.dialogs.compose.InsulinInputs
import app.aaps.ui.dialogs.compose.InsulinIntent
import app.aaps.ui.dialogs.compose.InsulinReview
import app.aaps.ui.dialogs.compose.TargetPreset
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import kotlin.math.min

class InsulinCommitterTest {

    private val now = 1_800_000_000_000L
    private val logger: AAPSLogger = mock()
    private val constraints: ConstraintsChecker = mock()
    private val commandQueue: CommandQueue = mock()
    private val uel: UserEntryLogger = mock()
    private val persistence: PersistenceLayer = mock()
    private val targetPresets: TargetPresets = mock()
    private val dateUtil: DateUtil = mock()
    private val rh: ResourceHelper = mock()
    private val config: Config = mock()
    private val loop: Loop = mock()
    private val activePlugin: ActivePlugin = mock()
    private val pump: Pump = mock()
    private val committer = InsulinCommitter(
        logger, constraints, commandQueue, uel, persistence, targetPresets, dateUtil, mock<UiInteraction>(), rh, config, loop, activePlugin
    )
    private val eatingSoon = TargetPresets.Resolved(TargetPreset.EATING_SOON, TT.Reason.EATING_SOON, 90.0, 45, "90 mg/dL · 45 min")

    /** The max bolus the constraints hold a dose to. */
    private var maxBolus = 10.0
    private var gateRuns = 0
    private val results = mutableListOf<Result>()

    @BeforeEach
    fun setUp() {
        @Suppress("UNCHECKED_CAST")
        whenever(constraints.applyBolusConstraints(any())).thenAnswer { ConstraintObject(min((it.arguments[0] as Constraint<Double>).value(), maxBolus), logger) }
        whenever(dateUtil.now()).thenReturn(now)
        whenever(loop.runningMode).thenReturn(RM.Mode.CLOSED_LOOP)
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(pump.isInitialized()).thenReturn(true)
        whenever(rh.gs(any<Int>())).thenReturn("Record")
        whenever(targetPresets.resolve(TargetPreset.EATING_SOON)).thenReturn(eatingSoon)
        whenever(targetPresets.start(any(), any(), anyOrNull())).thenReturn(Disposable.empty())
        whenever(persistence.insertOrUpdateBolus(any(), any(), any(), anyOrNull())).thenReturn(Single.just(PersistenceLayer.TransactionResult()))
    }

    private fun inputs(amount: Double, intent: InsulinIntent = InsulinIntent.DELIVER, givenAt: Long? = null, target: TargetPreset = TargetPreset.NONE, notes: String = "") =
        InsulinInputs(amount, intent, givenAt, target, notes)

    private fun commit(inputs: InsulinInputs, reviewed: InsulinReview, gate: (() -> Unit, () -> Unit) -> Unit = { proceed, _ -> gateRuns++; proceed() }) =
        committer.commit(inputs, reviewed, null, gate) { results += it }

    @Test
    fun `a reviewed dose is delivered once, after the pump pre-flight, whatever confirms it again`() {
        val entry = inputs(2.5, notes = "lunch")
        val reviewed = committer.review(entry)

        commit(entry, reviewed)
        commit(entry, reviewed)

        assertThat(results).containsExactly(Result.Done)
        assertThat(gateRuns).isEqualTo(1)
        val dose = argumentCaptor<DetailedBolusInfo>().apply { verify(commandQueue, times(1)).bolus(capture(), anyOrNull()) }.firstValue
        assertThat(dose.insulin).isEqualTo(2.5)
        assertThat(dose.timestamp).isEqualTo(now)
        assertThat(dose.notes).isEqualTo("lunch")
        verify(uel, times(1)).log(Action.BOLUS, Sources.InsulinDialog, "lunch", ValueWithUnit.Insulin(2.5))
    }

    @Test
    fun `a confirmation repeated while the pre-flight waits on the user adds nothing`() {
        val entry = inputs(2.5)
        val reviewed = committer.review(entry)
        var proceed: (() -> Unit)? = null
        val waiting: (() -> Unit, () -> Unit) -> Unit = { p, _ -> gateRuns++; proceed = p }

        commit(entry, reviewed, waiting)
        commit(entry, reviewed, waiting)
        proceed!!()

        assertThat(gateRuns).isEqualTo(1)
        verify(commandQueue, times(1)).bolus(any(), anyOrNull())
    }

    @Test
    fun `walking away from the pre-flight sends nothing and lets the review be confirmed again`() {
        val entry = inputs(2.5)
        val reviewed = committer.review(entry)

        commit(entry, reviewed) { _, cancel -> cancel() }
        verifyNoInteractions(commandQueue, uel)
        commit(entry, reviewed)

        assertThat(results).containsExactly(Result.Cancelled, Result.Done).inOrder()
        verify(commandQueue, times(1)).bolus(any(), anyOrNull())
    }

    @Test
    fun `a dose limited after review is not sent and Review gets the new amount`() {
        val entry = inputs(8.0)
        val reviewed = committer.review(entry)
        maxBolus = 6.0

        commit(entry, reviewed)

        val changed = results.single() as Result.Changed
        assertThat(changed.review.insulin).isEqualTo(6.0)
        assertThat(gateRuns).isEqualTo(0)
        verifyNoInteractions(commandQueue, uel)

        commit(entry, changed.review)
        val dose = argumentCaptor<DetailedBolusInfo>().apply { verify(commandQueue, times(1)).bolus(capture(), anyOrNull()) }.firstValue
        assertThat(dose.insulin).isEqualTo(6.0)
    }

    @Test
    fun `a limit that lands while the pre-flight waits still stops the dose`() {
        val entry = inputs(8.0)
        val reviewed = committer.review(entry)

        commit(entry, reviewed) { proceed, _ -> maxBolus = 6.0; proceed() }

        assertThat((results.single() as Result.Changed).review.insulin).isEqualTo(6.0)
        verifyNoInteractions(commandQueue, uel)
    }

    @Test
    fun `a pump that stopped since review is not asked for the dose`() {
        val entry = inputs(2.5)
        val reviewed = committer.review(entry)
        whenever(loop.runningMode).thenReturn(RM.Mode.SUSPENDED_BY_PUMP)

        commit(entry, reviewed)

        assertThat(results).containsExactly(Result.Unavailable(DeliveryUnavailable.PUMP_NOT_READY))
        assertThat(gateRuns).isEqualTo(0)
        verifyNoInteractions(commandQueue, uel)
    }

    @Test
    fun `a logged dose is recorded at its time and never reaches the pump`() {
        val givenAt = now - 2 * 60 * 60_000L
        val entry = inputs(3.0, InsulinIntent.LOG, givenAt = givenAt, notes = "pen")
        val reviewed = committer.review(entry)

        commit(entry, reviewed)
        commit(entry, reviewed)

        assertThat(results).containsExactly(Result.Done)
        assertThat(gateRuns).isEqualTo(0)
        verifyNoInteractions(commandQueue, uel)
        val record = argumentCaptor<BS>().apply {
            verify(persistence, times(1)).insertOrUpdateBolus(capture(), eq(Action.BOLUS), eq(Sources.InsulinDialog), eq("Record: pen"))
        }.firstValue
        assertThat(record.amount).isEqualTo(3.0)
        assertThat(record.timestamp).isEqualTo(givenAt)
    }

    @Test
    fun `a target alone starts without the pump`() {
        val entry = inputs(0.0, target = TargetPreset.EATING_SOON, notes = "walk")
        val reviewed = committer.review(entry)

        commit(entry, reviewed)

        assertThat(reviewed.commit).isEqualTo(InsulinReview.Commit.TARGET)
        assertThat(results).containsExactly(Result.Done)
        assertThat(gateRuns).isEqualTo(0)
        verify(targetPresets, times(1)).start(eatingSoon, Sources.InsulinDialog, "walk")
        verifyNoInteractions(commandQueue, uel, persistence)
    }
}
