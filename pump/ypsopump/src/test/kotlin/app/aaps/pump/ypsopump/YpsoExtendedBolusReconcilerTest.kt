package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt
import app.aaps.pump.ypsopump.bolus.YpsoBolusBaseline
import app.aaps.pump.ypsopump.bolus.YpsoBolusOutcome
import app.aaps.pump.ypsopump.bolus.YpsoBolusShape
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import app.aaps.pump.ypsopump.bolus.YpsoExtendedBolusReconciler
import app.aaps.pump.ypsopump.bolus.YpsoExtendedBolusReconciliation
import app.aaps.pump.ypsopump.history.YpsoEventIdentity
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoHistoryEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class YpsoExtendedBolusReconcilerTest {
    @Test
    fun `square terminal row requires proven slow sequence amount and duration`() {
        val result = assertInstanceOf(
            YpsoExtendedBolusReconciliation.AttemptCompleted::class.java,
            YpsoExtendedBolusReconciler.reconcile(attempt(), listOf(event(type = 3, value1 = 8, value2 = 15))),
        )
        assertEquals(8, result.amountCentiUnits)
    }

    @Test
    fun `combination terminal row validates immediate part and duration`() {
        val combined = attempt(shape = YpsoBolusShape.COMBINED, immediate = 40)
        assertInstanceOf(
            YpsoExtendedBolusReconciliation.AttemptCompleted::class.java,
            YpsoExtendedBolusReconciler.reconcile(combined, listOf(event(type = 18, value1 = 100, value2 = 40, value3 = 15))),
        )
        val mismatch = assertInstanceOf(
            YpsoExtendedBolusReconciliation.Unresolved::class.java,
            YpsoExtendedBolusReconciler.reconcile(combined, listOf(event(type = 18, value1 = 100, value2 = 30, value3 = 15))),
        )
        assertEquals(YpsoExtendedBolusReconciliation.Reason.HISTORY_SHAPE_MISMATCH, mismatch.reason)
    }

    @Test
    fun `abort and another sequence cannot close the attempt`() {
        val events = listOf(event(type = 30, value1 = 8), event(sequence = 102, type = 3, value1 = 8, value2 = 15))
        val result = assertInstanceOf(
            YpsoExtendedBolusReconciliation.Unresolved::class.java,
            YpsoExtendedBolusReconciler.reconcile(attempt(), events),
        )
        assertEquals(YpsoExtendedBolusReconciliation.Reason.NO_COMPATIBLE_HISTORY, result.reason)
    }

    @Test
    fun `same sequence in an older generation cannot close the attempt`() {
        val result = assertInstanceOf(
            YpsoExtendedBolusReconciliation.Unresolved::class.java,
            YpsoExtendedBolusReconciler.reconcile(
                attempt(sequence = 1, baselinePumpId = (3L shl 32) or 0xfffffff0L),
                listOf(event(generation = 3, sequence = 1, type = 3, value1 = 8, value2 = 15)),
            ),
        )
        assertEquals(YpsoExtendedBolusReconciliation.Reason.NO_COMPATIBLE_HISTORY, result.reason)
    }

    @Test
    fun `terminal kind for another extended shape is an identity mismatch`() {
        val result = assertInstanceOf(
            YpsoExtendedBolusReconciliation.Unresolved::class.java,
            YpsoExtendedBolusReconciler.reconcile(attempt(), listOf(event(type = 18, value1 = 100, value2 = 40, value3 = 15))),
        )
        assertEquals(YpsoExtendedBolusReconciliation.Reason.HISTORY_SHAPE_MISMATCH, result.reason)
    }

    @Test
    fun `terminal amount beyond the programmed total remains unresolved`() {
        val result = assertInstanceOf(
            YpsoExtendedBolusReconciliation.Unresolved::class.java,
            YpsoExtendedBolusReconciler.reconcile(attempt(), listOf(event(type = 3, value1 = 101, value2 = 15))),
        )
        assertEquals(YpsoExtendedBolusReconciliation.Reason.HISTORY_AMOUNT_INVALID, result.reason)
    }

    private fun attempt(
        shape: YpsoBolusShape = YpsoBolusShape.EXTENDED,
        immediate: Int = 0,
        sequence: Long = 101,
        baselinePumpId: Long = 100,
    ) = YpsoBolusAttempt(
        "request", "10000001", "generation", YpsoBolusTreatment.NORMAL, 100, "ab".repeat(32),
        YpsoBolusBaseline(10, 20, baselinePumpId, 1, 2, 3, 1_000), 1_100,
        shape = shape, durationMinutes = 15, immediateCentiUnits = immediate,
        outcome = YpsoBolusOutcome.DELIVERING, dispatchCounter = 1, dispatchedAt = 1_200, pumpSlowSequence = sequence,
    )

    private fun event(generation: Int = 0, sequence: Long = 101, type: Int, value1: Int, value2: Int = 0, value3: Int = 0): YpsoHistoryEvent {
        val entry = YpsoHistoryEntry(1000, type, value1, value2, value3, sequence, 0)
        return YpsoHistoryEvent(YpsoEventIdentity("10000001", generation, sequence), entry)
    }
}
