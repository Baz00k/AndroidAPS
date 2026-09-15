package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.history.YpsoEventIdentity
import app.aaps.pump.ypsopump.history.YpsoHistoryAttribution
import app.aaps.pump.ypsopump.history.YpsoHistoryAttributor
import app.aaps.pump.ypsopump.history.YpsoHistoryAttemptCursor
import app.aaps.pump.ypsopump.history.YpsoHistoryClassifier
import app.aaps.pump.ypsopump.history.YpsoHistoryCursor
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoHistoryKind
import app.aaps.pump.ypsopump.history.YpsoHistoryReconciler
import app.aaps.pump.ypsopump.history.YpsoHistoryReconciliation
import app.aaps.pump.ypsopump.history.YpsoHistorySnapshot
import app.aaps.pump.ypsopump.history.YpsoPumpModeChange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class YpsoHistoryContractTest {

    @Test
    fun `target event fixtures classify without claiming command origin`() {
        val immediate = entry(sequence = 47873, type = 2, v1 = 120)
        val tbr = entry(sequence = 47881, type = 9, v1 = 150, v2 = 15)
        val stop = entry(sequence = 47877, type = 14, v1 = 3)
        val resume = entry(sequence = 47879, type = 14, v1 = 10)

        assertEquals(1.2, YpsoHistoryClassifier.classify(immediate).amountUnits)
        assertFalse(YpsoHistoryClassifier.classify(immediate).commandOriginAttributable)
        assertEquals(YpsoHistoryKind.TEMP_BASAL_STARTED, YpsoHistoryClassifier.classify(tbr).kind)
        assertEquals(YpsoPumpModeChange.STOPPED, YpsoHistoryClassifier.classify(stop).modeChange)
        assertEquals(YpsoPumpModeChange.RESUMED, YpsoHistoryClassifier.classify(resume).modeChange)
        assertEquals(YpsoHistoryKind.UNKNOWN, YpsoHistoryClassifier.classify(entry(sequence = 47880, type = 14, v1 = 4)).kind)
    }

    @Test
    fun `duplicate scans deduplicate and moving head produces oldest first events`() {
        val cursorEntry = entry(sequence = 100, index = 0)
        val cursor = YpsoHistoryCursor(YpsoEventIdentity("serial", 0, 100), cursorEntry.fingerprint(), 21)
        val snapshot = YpsoHistorySnapshot(
            countBefore = 3000,
            countAfter = 3000,
            pumpRebootBefore = 21,
            pumpRebootAfter = 21,
            headBefore = entry(sequence = 102, index = 0),
            headAfter = entry(sequence = 102, index = 0),
            rowsNewestFirst = listOf(entry(sequence = 102, index = 0), entry(sequence = 101, index = 1), cursorEntry.copy(index = 2)),
            fullCoverage = false,
        )
        val result = assertInstanceOf(YpsoHistoryReconciliation.Stable::class.java, YpsoHistoryReconciler.reconcile(cursor, snapshot))
        assertEquals(listOf(101L, 102L), result.newEventsOldestFirst.map { it.identity.sequence })
        assertEquals(102L, result.cursor.identity.sequence)

        val duplicate = assertInstanceOf(
            YpsoHistoryReconciliation.Stable::class.java,
            YpsoHistoryReconciler.reconcile(result.cursor, snapshot.copy(rowsNewestFirst = listOf(entry(sequence = 102, index = 0)))),
        )
        assertEquals(emptyList<Any>(), duplicate.newEventsOldestFirst)
    }

    @Test
    fun `moving count overwritten cursor empty history and conflicting payloads are deterministic gaps`() {
        val old = entry(sequence = 100)
        val cursor = YpsoHistoryCursor(YpsoEventIdentity("serial", 0, 100), old.fingerprint(), 21)
        assertInstanceOf(
            YpsoHistoryReconciliation.Moving::class.java,
            YpsoHistoryReconciler.reconcile(cursor, snapshot(3, 4, listOf(old))),
        )
        assertInstanceOf(
            YpsoHistoryReconciliation.Moving::class.java,
            YpsoHistoryReconciler.reconcile(cursor, snapshot(0, 1, listOf(entry(sequence = 101)))),
        )
        assertInstanceOf(
            YpsoHistoryReconciliation.Moving::class.java,
            YpsoHistoryReconciler.reconcile(
                cursor,
                snapshot(3000, 3000, listOf(old)).copy(headAfter = entry(sequence = 101)),
            ),
        )
        assertEquals(
            YpsoHistoryReconciliation.Reason.EMPTY_AFTER_CURSOR,
            assertInstanceOf(
                YpsoHistoryReconciliation.Gap::class.java,
                YpsoHistoryReconciler.reconcile(cursor, snapshot(0, 0, emptyList(), fullCoverage = true)),
            ).reason,
        )
        assertEquals(
            YpsoHistoryReconciliation.Reason.CURSOR_OVERWRITTEN_OR_SEQUENCE_RESET,
            assertInstanceOf(
                YpsoHistoryReconciliation.Gap::class.java,
                YpsoHistoryReconciler.reconcile(cursor, snapshot(1, 1, listOf(entry(sequence = 200)), fullCoverage = true)),
            ).reason,
        )
        assertEquals(
            YpsoHistoryReconciliation.Reason.CONFLICTING_SEQUENCE_PAYLOAD,
            assertInstanceOf(
                YpsoHistoryReconciliation.Gap::class.java,
                YpsoHistoryReconciler.reconcile(
                    cursor,
                    snapshot(3, 3, listOf(entry(sequence = 101), entry(sequence = 101, v1 = 1), old), fullCoverage = true),
                ),
            ).reason,
        )
    }

    @Test
    fun `unsigned sequence wrap advances identity generation and AAPS pump id`() {
        val old = entry(sequence = 0xffff_ffffL)
        val cursor = YpsoHistoryCursor(YpsoEventIdentity("serial", 7, old.sequence), old.fingerprint(), 21)
        val result = assertInstanceOf(
            YpsoHistoryReconciliation.Stable::class.java,
            YpsoHistoryReconciler.reconcile(cursor, snapshot(2, 2, listOf(entry(sequence = 0), old), fullCoverage = true)),
        )
        assertEquals(8, result.cursor.identity.sequenceGeneration)
        assertEquals(8L shl 32, result.cursor.identity.aapsPumpId)
    }

    @Test
    fun `reboot with continuing sequence preserves identity while lower sequence is a deterministic reset gap`() {
        val old = entry(sequence = 100)
        val cursor = YpsoHistoryCursor(YpsoEventIdentity("serial", 0, 100), old.fingerprint(), 21)
        val continued = assertInstanceOf(
            YpsoHistoryReconciliation.Stable::class.java,
            YpsoHistoryReconciler.reconcile(
                cursor,
                snapshot(2, 2, listOf(entry(sequence = 101), old.copy(index = 1)), fullCoverage = true, reboot = 22),
            ),
        )
        assertEquals(22, continued.cursor.pumpReboot)
        assertEquals(0, continued.cursor.identity.sequenceGeneration)

        val high = entry(sequence = 0xffff_ffffL)
        val highCursor = YpsoHistoryCursor(YpsoEventIdentity("serial", 0, high.sequence), high.fingerprint(), 21)
        assertEquals(
            YpsoHistoryReconciliation.Reason.SEQUENCE_RESET_AFTER_REBOOT,
            assertInstanceOf(
                YpsoHistoryReconciliation.Gap::class.java,
                YpsoHistoryReconciler.reconcile(
                    highCursor,
                    snapshot(2, 2, listOf(entry(sequence = 0), high.copy(index = 1)), fullCoverage = true, reboot = 22),
                ),
            ).reason,
        )

        val highUnchangedAfterReboot = assertInstanceOf(
            YpsoHistoryReconciliation.Stable::class.java,
            YpsoHistoryReconciler.reconcile(highCursor, snapshot(1, 1, listOf(high), fullCoverage = true, reboot = 22)),
        )
        assertEquals(21, highUnchangedAfterReboot.cursor.pumpReboot)
        assertEquals(
            YpsoHistoryReconciliation.Reason.SEQUENCE_RESET_AFTER_REBOOT,
            assertInstanceOf(
                YpsoHistoryReconciliation.Gap::class.java,
                YpsoHistoryReconciler.reconcile(
                    highUnchangedAfterReboot.cursor,
                    snapshot(2, 2, listOf(entry(sequence = 0), high.copy(index = 1)), fullCoverage = true, reboot = 22),
                ),
            ).reason,
        )
    }

    @Test
    fun `attribution requires pre-attempt cursor unique compatible event and encoded origin`() {
        val old = entry(sequence = 100)
        val cursor = YpsoHistoryCursor(YpsoEventIdentity("serial", 0, 100), old.fingerprint(), 21)
        val stable = YpsoHistoryReconciler.reconcile(
            cursor,
            snapshot(2, 2, listOf(entry(sequence = 101, v1 = 120), old.copy(index = 1)), fullCoverage = true),
        )
        val blocked = YpsoHistoryAttributor.attribute(
            YpsoHistoryAttemptCursor(cursor, capturedBeforeDispatch = true),
            stable,
            compatible = { it.kind == YpsoHistoryKind.IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED },
        )
        assertEquals(
            YpsoHistoryAttribution.Blocked(YpsoHistoryAttribution.Reason.ORIGIN_NOT_ENCODED),
            blocked,
        )
        assertEquals(
            YpsoHistoryAttribution.Blocked(YpsoHistoryAttribution.Reason.HISTORY_NOT_STABLE),
            YpsoHistoryAttributor.attribute(
                YpsoHistoryAttemptCursor(cursor.copy(pumpReboot = 20), capturedBeforeDispatch = true),
                stable,
                compatible = { true },
            ),
        )
    }

    @Test
    fun `embedded logical indexes must match scan order`() {
        val old = entry(sequence = 100)
        val cursor = YpsoHistoryCursor(YpsoEventIdentity("serial", 0, 100), old.fingerprint(), 21)
        val invalid = snapshot(2, 2, listOf(entry(sequence = 101), old), fullCoverage = true).copy(
            rowsNewestFirst = listOf(entry(sequence = 101, index = 0), old.copy(index = 9)),
        )
        assertEquals(
            YpsoHistoryReconciliation.Reason.INVALID_SNAPSHOT,
            assertInstanceOf(YpsoHistoryReconciliation.Gap::class.java, YpsoHistoryReconciler.reconcile(cursor, invalid)).reason,
        )
        assertEquals(
            YpsoHistoryReconciliation.Reason.INVALID_SNAPSHOT,
            assertInstanceOf(
                YpsoHistoryReconciliation.Gap::class.java,
                YpsoHistoryReconciler.reconcile(cursor, snapshot(3, 3, listOf(old), fullCoverage = true)),
            ).reason,
        )
        assertEquals(
            YpsoHistoryReconciliation.Reason.INVALID_SNAPSHOT,
            assertInstanceOf(
                YpsoHistoryReconciliation.Gap::class.java,
                YpsoHistoryReconciler.reconcile(cursor, snapshot(2, 2, listOf(old, old))),
            ).reason,
        )
    }

    private fun entry(
        sequence: Long,
        index: Int = 0,
        type: Int = 2,
        v1: Int = 0,
        v2: Int = 0,
    ) = YpsoHistoryEntry(842_796_136, type, v1, v2, 0, sequence, index)

    private fun snapshot(
        countBefore: Int,
        countAfter: Int,
        rows: List<YpsoHistoryEntry>,
        fullCoverage: Boolean = false,
        reboot: Long = 21,
    ): YpsoHistorySnapshot {
        val indexedRows = rows.mapIndexed { index, row -> row.copy(index = index) }
        val head = indexedRows.firstOrNull()
        return YpsoHistorySnapshot(countBefore, countAfter, reboot, reboot, head, head, indexedRows, fullCoverage)
    }
}
