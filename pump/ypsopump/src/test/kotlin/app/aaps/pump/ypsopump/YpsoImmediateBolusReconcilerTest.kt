package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt
import app.aaps.pump.ypsopump.bolus.YpsoBolusBaseline
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import app.aaps.pump.ypsopump.bolus.YpsoImmediateBolusReconciliation
import app.aaps.pump.ypsopump.bolus.YpsoImmediateBolusReconciler
import app.aaps.pump.ypsopump.bolus.YpsoImmediateBolusStatus
import app.aaps.pump.ypsopump.history.YpsoEventIdentity
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoHistoryEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoImmediateBolusReconcilerTest {
    @Test
    fun `pump confirmed manual-compatible insulin remains ingestible without completing attempt`() {
        val result = YpsoImmediateBolusReconciler.reconcile(attempt(), null, listOf(completedEvent(101, 100)))
        val unresolved = result as YpsoImmediateBolusReconciliation.Unresolved
        assertEquals(YpsoImmediateBolusReconciliation.Reason.STATUS_IDENTITY_UNPROVEN, unresolved.reason)
        assertEquals(100, unresolved.confirmedInsulin?.amountCentiUnits)
        assertEquals(101, unresolved.confirmedInsulin?.event?.identity?.sequence)
    }

    @Test
    fun `retained non-cleared status does not close the attempt`() {
        val result = YpsoImmediateBolusReconciler.reconcile(
            attempt(provenFastSequence = 45),
            YpsoImmediateBolusStatus(45, 0, 100, 100),
            listOf(completedEvent(45, 100)),
        )
        assertEquals(
            YpsoImmediateBolusReconciliation.Reason.STATUS_IDENTITY_CHANGED,
            (result as YpsoImmediateBolusReconciliation.Unresolved).reason,
        )
    }

    @Test
    fun `cleared terminal status retains the previously proven sequence to history link`() {
        val result = YpsoImmediateBolusReconciler.reconcile(
            attempt(provenFastSequence = 45),
            YpsoImmediateBolusStatus(0, 0, 0, 0),
            listOf(completedEvent(45, 100)),
        )

        val completed = result as YpsoImmediateBolusReconciliation.AttemptCompleted
        assertEquals(45, completed.event.identity.sequence)
        assertEquals(100, completed.amountCentiUnits)
    }

    @Test
    fun `duplicate rows for the proven sequence remain unresolved`() {
        val result = YpsoImmediateBolusReconciler.reconcile(
            attempt(provenFastSequence = 45),
            YpsoImmediateBolusStatus(0, 0, 0, 0),
            listOf(completedEvent(45, 100), completedEvent(45, 100)),
        )
        assertEquals(
            YpsoImmediateBolusReconciliation.Reason.MULTIPLE_COMPATIBLE_HISTORY,
            (result as YpsoImmediateBolusReconciliation.Unresolved).reason,
        )
    }

    @Test
    fun `different size manual bolus does not close while status is not terminal cleared`() {
        val result = YpsoImmediateBolusReconciler.reconcile(
            attempt(provenFastSequence = 45),
            YpsoImmediateBolusStatus(45, 0, 100, 100),
            listOf(completedEvent(44, 50), completedEvent(45, 100)),
        )

        val unresolved = result as YpsoImmediateBolusReconciliation.Unresolved
        assertEquals(YpsoImmediateBolusReconciliation.Reason.STATUS_IDENTITY_CHANGED, unresolved.reason)
        assertEquals(100, unresolved.confirmedInsulin?.amountCentiUnits)
    }

    @Test
    fun `stale sequence or amount disagreement preserves confirmed insulin but not request success`() {
        val stale = YpsoImmediateBolusReconciler.reconcile(attempt(), YpsoImmediateBolusStatus(44, 0, 100, 100), listOf(completedEvent(101, 100)))
        assertEquals(YpsoImmediateBolusReconciliation.Reason.STATUS_IDENTITY_UNPROVEN, (stale as YpsoImmediateBolusReconciliation.Unresolved).reason)
        assertEquals(100, stale.confirmedInsulin?.amountCentiUnits)

        val mismatch = YpsoImmediateBolusReconciler.reconcile(attempt(provenFastSequence = 45), YpsoImmediateBolusStatus(45, 0, 90, 90), listOf(completedEvent(45, 90)))
        assertEquals(YpsoImmediateBolusReconciliation.Reason.STATUS_IDENTITY_CHANGED, (mismatch as YpsoImmediateBolusReconciliation.Unresolved).reason)
    }

    @Test
    fun `terminal status cannot retroactively prove the attempt identity`() {
        val result = YpsoImmediateBolusReconciler.reconcile(
            attempt(),
            YpsoImmediateBolusStatus(45, 0, 100, 100),
            listOf(completedEvent(101, 100)),
        )

        val unresolved = result as YpsoImmediateBolusReconciliation.Unresolved
        assertEquals(YpsoImmediateBolusReconciliation.Reason.STATUS_IDENTITY_UNPROVEN, unresolved.reason)
        assertEquals(100, unresolved.confirmedInsulin?.amountCentiUnits)
    }

    @Test
    fun `same amount manual row cannot replace the proven sequence identity`() {
        val result = YpsoImmediateBolusReconciler.reconcile(
            attempt(provenFastSequence = 45),
            YpsoImmediateBolusStatus(0, 0, 0, 0),
            listOf(completedEvent(44, 100), completedEvent(45, 100)),
        )

        val completed = result as YpsoImmediateBolusReconciliation.AttemptCompleted
        assertEquals(45, completed.event.identity.sequence)
    }

    private fun attempt(provenFastSequence: Long? = null) = YpsoBolusAttempt(
        "request", "10000001", "generation", YpsoBolusTreatment.NORMAL, 100, "ab".repeat(32),
        baseline = YpsoBolusBaseline(44, 9, 100, 10, 20, 21, 1_000),
        createdAt = 1_500,
        pumpFastSequence = provenFastSequence,
    )

    private fun completedEvent(sequence: Long, centiUnits: Int): YpsoHistoryEvent {
        val entry = YpsoHistoryEntry(1, 2, centiUnits, 0, 0, sequence, 0)
        return YpsoHistoryEvent(YpsoEventIdentity("10000001", 0, sequence), entry)
    }
}
