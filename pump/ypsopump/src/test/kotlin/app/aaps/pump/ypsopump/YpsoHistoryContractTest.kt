package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.history.YpsoAlarm
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoHistoryContractTest {

    @Test
    fun `target event fixtures classify without claiming command origin`() {
        val immediate = entry(sequence = 47873, type = 2, v1 = 120)
        val tbr = entry(sequence = 47881, type = 9, v1 = 150, v2 = 15)
        val stop = entry(sequence = 47877, type = 14, v1 = 3)
        val resume = entry(sequence = 47879, type = 14, v1 = 10)
        val profileChanged = entry(sequence = 47890, type = 6)

        assertEquals(1.2, YpsoHistoryClassifier.classify(immediate).amountUnits)
        assertFalse(YpsoHistoryClassifier.classify(immediate).commandOriginAttributable)
        assertEquals(YpsoHistoryKind.TEMP_BASAL_STARTED, YpsoHistoryClassifier.classify(tbr).kind)
        assertEquals(
            YpsoHistoryKind.TEMP_BASAL_TERMINAL_UNRESOLVED,
            YpsoHistoryClassifier.classify(entry(sequence = 47881, type = 10, v1 = 150, v2 = 1)).kind,
        )
        assertEquals(YpsoPumpModeChange.STOPPED, YpsoHistoryClassifier.classify(stop).modeChange)
        assertEquals(YpsoPumpModeChange.RESUMED, YpsoHistoryClassifier.classify(resume).modeChange)
        assertEquals(YpsoHistoryKind.BASAL_PROFILE_CHANGED, YpsoHistoryClassifier.classify(profileChanged).kind)
        assertEquals(YpsoHistoryKind.UNKNOWN, YpsoHistoryClassifier.classify(entry(sequence = 47880, type = 14, v1 = 4)).kind)
    }

    @Test
    fun `successive active profile switches reconcile as ordered profile events`() {
        val old = entry(sequence = 100)
        val cursor = YpsoHistoryCursor(YpsoEventIdentity("serial", 0, 100), old.fingerprint(), 21)
        val firstSwitch = entry(sequence = 101, type = 6)
        val secondSwitch = entry(sequence = 102, type = 6)
        val stable = assertInstanceOf(
            YpsoHistoryReconciliation.Stable::class.java,
            YpsoHistoryReconciler.reconcile(
                cursor,
                snapshot(3, 3, listOf(secondSwitch, firstSwitch, old), fullCoverage = true),
            ),
        )
        assertEquals(listOf(101L, 102L), stable.newEventsOldestFirst.map { it.identity.sequence })
        assertTrue(stable.newEventsOldestFirst.all { it.semantics.kind == YpsoHistoryKind.BASAL_PROFILE_CHANGED })
        assertTrue(stable.newEventsOldestFirst.none { it.semantics.commandOriginAttributable })
    }

    @Test
    fun `reference-corroborated event types classify to distinct kinds`() {
        val expected = linkedMapOf(
            1 to YpsoHistoryKind.DELAYED_BOLUS_RUNNING,
            5 to YpsoHistoryKind.BOLUS_STEP_CHANGED,
            7 to YpsoHistoryKind.BASAL_PROFILE_A_CHANGED,
            8 to YpsoHistoryKind.BASAL_PROFILE_B_CHANGED,
            12 to YpsoHistoryKind.DATE_CHANGED,
            13 to YpsoHistoryKind.TIME_CHANGED,
            17 to YpsoHistoryKind.COMBINED_BOLUS_RUNNING,
            18 to YpsoHistoryKind.COMBINED_BOLUS_COMPLETED,
            19 to YpsoHistoryKind.IMMEDIATE_BOLUS_RUNNING,
            20 to YpsoHistoryKind.DELAYED_BOLUS_BACKUP,
            21 to YpsoHistoryKind.COMBINED_BOLUS_BACKUP,
            22 to YpsoHistoryKind.TEMP_BASAL_BACKUP,
            23 to YpsoHistoryKind.DAILY_TOTAL_INSULIN,
            24 to YpsoHistoryKind.BATTERY_REMOVED,
            25 to YpsoHistoryKind.CANNULA_PRIMING_FINISHED,
            26 to YpsoHistoryKind.BLIND_BOLUS_COMPLETED,
            27 to YpsoHistoryKind.BLIND_BOLUS_RUNNING,
            28 to YpsoHistoryKind.BLIND_BOLUS_ABORTED,
            29 to YpsoHistoryKind.IMMEDIATE_BOLUS_ABORTED,
            30 to YpsoHistoryKind.DELAYED_BOLUS_ABORTED,
            31 to YpsoHistoryKind.COMBINED_BOLUS_ABORTED,
            32 to YpsoHistoryKind.TEMP_BASAL_ABORTED,
            33 to YpsoHistoryKind.BOLUS_AMOUNT_CAP_CHANGED,
            34 to YpsoHistoryKind.BASAL_RATE_CAP_CHANGED,
            150 to YpsoHistoryKind.DELIVERY_STATUS_CHANGED,
        )
        for ((type, kind) in expected) {
            assertEquals(kind, YpsoHistoryClassifier.classify(entry(sequence = 100, type = type)).kind, "type $type")
        }
    }

    @Test
    fun `reference-mapped bolus rows claim only the paired centi-unit amount convention`() {
        for (type in listOf(1, 17, 18, 19, 26, 27, 28, 29, 30, 31)) {
            val semantics = YpsoHistoryClassifier.classify(entry(sequence = 100, type = type, v1 = 250, v2 = 99))
            assertEquals(2.5, semantics.amountUnits, "type $type")
            assertNull(semantics.durationMinutes, "type $type")
            assertNull(semantics.requestedDurationMinutes, "type $type")
            assertNull(semantics.elapsedDurationMinutes, "type $type")
            assertNull(semantics.percent, "type $type")
        }
        // Target evidence overrides the reference claim that type 3 value2 is a second amount.
        val delayed = YpsoHistoryClassifier.classify(entry(sequence = 100, type = 3, v1 = 300, v2 = 15))
        assertEquals(3.0, delayed.amountUnits)
        assertEquals(15, delayed.durationMinutes)
        assertNull(delayed.elapsedDurationMinutes)
        // Backup rows have no corroborated value layout at all.
        for (type in listOf(20, 21, 22)) {
            val backup = YpsoHistoryClassifier.classify(entry(sequence = 100, type = type, v1 = 250, v2 = 15))
            assertNull(backup.amountUnits, "type $type")
            assertNull(backup.durationMinutes, "type $type")
            assertNull(backup.percent, "type $type")
        }
        // The TBR abort row claims the percent convention only; elapsed time stays unclaimed.
        val abort = YpsoHistoryClassifier.classify(entry(sequence = 100, type = 32, v1 = 150, v2 = 7))
        assertEquals(150, abort.percent)
        assertNull(abort.elapsedDurationMinutes)
        assertNull(abort.requestedDurationMinutes)
    }

    @Test
    fun `alarm rows decode to an explicit alarm code`() {
        val expected = linkedMapOf(
            100 to YpsoAlarm.BATTERY_REMOVED,
            101 to YpsoAlarm.BATTERY_EMPTY,
            102 to YpsoAlarm.REUSABLE_ERROR,
            103 to YpsoAlarm.NO_CARTRIDGE,
            104 to YpsoAlarm.CARTRIDGE_EMPTY,
            105 to YpsoAlarm.OCCLUSION,
            106 to YpsoAlarm.AUTO_STOP,
            107 to YpsoAlarm.LIPO_DISCHARGED,
            108 to YpsoAlarm.BATTERY_REJECTED,
        )
        for ((type, alarm) in expected) {
            val semantics = YpsoHistoryClassifier.classify(entry(sequence = 100, type = type))
            assertEquals(YpsoHistoryKind.ALARM, semantics.kind, "type $type")
            assertEquals(alarm, semantics.alarm, "type $type")
            assertNull(semantics.amountUnits, "type $type")
            assertEquals(false, semantics.commandOriginAttributable, "type $type")
        }
    }

    @Test
    fun `unmapped event numbers stay fail-closed`() {
        for (type in listOf(0, 11, 15, 35, 99, 109, 149, 151, 255)) {
            val semantics = YpsoHistoryClassifier.classify(entry(sequence = 100, type = type))
            assertEquals(YpsoHistoryKind.UNKNOWN, semantics.kind, "type $type")
            assertNull(semantics.alarm, "type $type")
        }
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
    fun `TBR terminal rewrite is emitted once under the original stable identity`() {
        val active = entry(sequence = 100, type = 9, v1 = 150, v2 = 15)
        val terminal = entry(sequence = 100, type = 10, v1 = 150, v2 = 1)
        val cursor = YpsoHistoryCursor(
            YpsoEventIdentity("serial", 0, 100),
            active.fingerprint(),
            21,
            app.aaps.pump.ypsopump.history.YpsoMutableHistoryState(
                YpsoEventIdentity("serial", 0, 100),
                active.fingerprint(),
                active.stateFingerprint(),
                active.value1,
                active.value2,
            ),
        )
        val first = assertInstanceOf(
            YpsoHistoryReconciliation.Stable::class.java,
            YpsoHistoryReconciler.reconcile(cursor, snapshot(1, 1, listOf(terminal), fullCoverage = true)),
        )
        assertEquals(emptyList<Any>(), first.newEventsOldestFirst)
        assertEquals(listOf(100L), first.stateUpdates.map { it.identity.sequence })
        val cancelled = first.stateUpdates.single().semantics
        assertEquals(YpsoHistoryKind.TEMP_BASAL_CANCELLED, cancelled.kind)
        assertEquals(15, cancelled.requestedDurationMinutes)
        assertEquals(1, cancelled.elapsedDurationMinutes)
        assertNull(cancelled.durationMinutes)
        assertEquals(cursor.identity, first.cursor.identity)
        assertEquals(null, first.cursor.activeTbr)

        val duplicate = assertInstanceOf(
            YpsoHistoryReconciliation.Stable::class.java,
            YpsoHistoryReconciler.reconcile(first.cursor, snapshot(1, 1, listOf(terminal), fullCoverage = true)),
        )
        assertEquals(emptyList<Any>(), duplicate.newEventsOldestFirst)
        assertEquals(emptyList<Any>(), duplicate.stateUpdates)

        // An in-place rewrite keeps the previous identity and is not a strictly newer event, so it
        // must never satisfy post-dispatch attribution.
        assertEquals(
            YpsoHistoryAttribution.Blocked(YpsoHistoryAttribution.Reason.NO_NEW_EVENT),
            YpsoHistoryAttributor.attribute(
                YpsoHistoryAttemptCursor(cursor, capturedBeforeDispatch = true),
                first,
                compatible = { it.kind == YpsoHistoryKind.TEMP_BASAL_CANCELLED },
            ),
        )
    }

    @Test
    fun `TBR expiry is classified only against the same identity's requested duration`() {
        val active = entry(sequence = 100, type = 9, v1 = 110, v2 = 15)
        val cursor = YpsoHistoryCursor(
            YpsoEventIdentity("serial", 0, 100),
            active.fingerprint(),
            21,
            app.aaps.pump.ypsopump.history.YpsoMutableHistoryState(
                YpsoEventIdentity("serial", 0, 100),
                active.fingerprint(),
                active.stateFingerprint(),
                active.value1,
                active.value2,
            ),
        )
        val completed = active.copy(eventType = 10)
        val stable = assertInstanceOf(
            YpsoHistoryReconciliation.Stable::class.java,
            YpsoHistoryReconciler.reconcile(cursor, snapshot(1, 1, listOf(completed), fullCoverage = true)),
        )
        val completedSemantics = stable.stateUpdates.single().semantics
        assertEquals(YpsoHistoryKind.TEMP_BASAL_COMPLETED, completedSemantics.kind)
        assertEquals(15, completedSemantics.requestedDurationMinutes)
        assertEquals(15, completedSemantics.elapsedDurationMinutes)
    }

    @Test
    fun `standalone terminal TBR and elapsed time beyond request stay unresolved`() {
        val standalone = entry(sequence = 100, type = 10, v1 = 110, v2 = 15)
        val standaloneSemantics = YpsoHistoryClassifier.classify(standalone)
        assertEquals(YpsoHistoryKind.TEMP_BASAL_TERMINAL_UNRESOLVED, standaloneSemantics.kind)
        assertNull(standaloneSemantics.requestedDurationMinutes)
        assertEquals(15, standaloneSemantics.elapsedDurationMinutes)

        val active = entry(sequence = 101, type = 9, v1 = 110, v2 = 15)
        val cursor = YpsoHistoryCursor(
            YpsoEventIdentity("serial", 0, 101),
            active.fingerprint(),
            21,
            app.aaps.pump.ypsopump.history.YpsoMutableHistoryState(
                YpsoEventIdentity("serial", 0, 101),
                active.fingerprint(),
                active.stateFingerprint(),
                active.value1,
                active.value2,
            ),
        )
        val invalidTerminal = active.copy(eventType = 10, value2 = 16)
        val stable = assertInstanceOf(
            YpsoHistoryReconciliation.Stable::class.java,
            YpsoHistoryReconciler.reconcile(cursor, snapshot(1, 1, listOf(invalidTerminal), fullCoverage = true)),
        )
        val unresolved = stable.stateUpdates.single().semantics
        assertEquals(YpsoHistoryKind.TEMP_BASAL_TERMINAL_UNRESOLVED, unresolved.kind)
        assertEquals(15, unresolved.requestedDurationMinutes)
        assertEquals(16, unresolved.elapsedDurationMinutes)
    }

    @Test
    fun `older active TBR remains tracked after a newer event and later terminates`() {
        val active = entry(sequence = 100, type = 9, v1 = 150, v2 = 15)
        val newerBolus = entry(sequence = 101, type = 2, v1 = 200)
        val activeIdentity = YpsoEventIdentity("serial", 0, 100)
        val cursor = YpsoHistoryCursor(
            YpsoEventIdentity("serial", 0, 101),
            newerBolus.fingerprint(),
            21,
            app.aaps.pump.ypsopump.history.YpsoMutableHistoryState(
                activeIdentity,
                active.fingerprint(),
                active.stateFingerprint(),
                active.value1,
                active.value2,
            ),
        )
        val terminal = active.copy(eventType = 10, value2 = 1, index = 1)
        val stable = assertInstanceOf(
            YpsoHistoryReconciliation.Stable::class.java,
            YpsoHistoryReconciler.reconcile(
                cursor,
                snapshot(2, 2, listOf(newerBolus, terminal), fullCoverage = true),
            ),
        )
        assertEquals(emptyList<Any>(), stable.newEventsOldestFirst)
        assertEquals(activeIdentity, stable.stateUpdates.single().identity)
        assertEquals(YpsoHistoryKind.TEMP_BASAL_CANCELLED, stable.stateUpdates.single().semantics.kind)
        assertEquals(null, stable.cursor.activeTbr)
    }

    @Test
    fun `new TBR cannot replace tracked state when the older mutable row is outside coverage`() {
        val old = entry(sequence = 100, type = 2)
        val active = entry(sequence = 99, type = 9, v1 = 150, v2 = 15)
        val cursor = YpsoHistoryCursor(
            YpsoEventIdentity("serial", 0, 100),
            old.fingerprint(),
            21,
            app.aaps.pump.ypsopump.history.YpsoMutableHistoryState(
                YpsoEventIdentity("serial", 0, 99),
                active.fingerprint(),
                active.stateFingerprint(),
                active.value1,
                active.value2,
            ),
        )
        val replacement = entry(sequence = 101, type = 9, v1 = 110, v2 = 15)
        assertEquals(
            YpsoHistoryReconciliation.Reason.COVERAGE_INCOMPLETE,
            assertInstanceOf(
                YpsoHistoryReconciliation.Gap::class.java,
                YpsoHistoryReconciler.reconcile(cursor, snapshot(2, 2, listOf(replacement, old.copy(index = 1)))),
            ).reason,
        )
    }

    @Test
    fun `unverifiable tracked TBR state is a deterministic gap instead of a stale cursor`() {
        val active = entry(sequence = 99, type = 9, v1 = 150, v2 = 15)
        val newer = entry(sequence = 100)
        val cursor = YpsoHistoryCursor(
            YpsoEventIdentity("serial", 0, 100),
            newer.fingerprint(),
            21,
            app.aaps.pump.ypsopump.history.YpsoMutableHistoryState(
                YpsoEventIdentity("serial", 0, 99),
                active.fingerprint(),
                active.stateFingerprint(),
                active.value1,
                active.value2,
            ),
        )
        assertEquals(
            YpsoHistoryReconciliation.Reason.TRACKED_TBR_ROW_MISSING,
            assertInstanceOf(
                YpsoHistoryReconciliation.Gap::class.java,
                YpsoHistoryReconciler.reconcile(cursor, snapshot(1, 1, listOf(newer), fullCoverage = true)),
            ).reason,
        )
        assertEquals(
            YpsoHistoryReconciliation.Reason.COVERAGE_INCOMPLETE,
            assertInstanceOf(
                YpsoHistoryReconciliation.Gap::class.java,
                YpsoHistoryReconciler.reconcile(cursor, snapshot(1, 1, listOf(newer))),
            ).reason,
        )
    }

    @Test
    fun `multiple active TBR rows block ingestion instead of choosing one`() {
        val old = entry(sequence = 100)
        val first = entry(sequence = 101, type = 9, v1 = 150, v2 = 15)
        val second = entry(sequence = 102, type = 9, v1 = 110, v2 = 30)
        val rows = listOf(second, first, old)
        val cursor = YpsoHistoryCursor(YpsoEventIdentity("serial", 0, 100), old.fingerprint(), 21)
        assertEquals(
            YpsoHistoryReconciliation.Reason.MULTIPLE_ACTIVE_TBR_ROWS,
            assertInstanceOf(
                YpsoHistoryReconciliation.Gap::class.java,
                YpsoHistoryReconciler.reconcile(cursor, snapshot(3, 3, rows, fullCoverage = true)),
            ).reason,
        )
        assertEquals(
            YpsoHistoryReconciliation.Reason.MULTIPLE_ACTIVE_TBR_ROWS,
            assertInstanceOf(
                YpsoHistoryReconciliation.Gap::class.java,
                YpsoHistoryReconciler.bootstrap("serial", 0, snapshot(3, 3, rows, fullCoverage = true)),
            ).reason,
        )
    }

    @Test
    fun `pump clock jumps do not replace sequence identity or receipt-time ordering`() {
        val old = entry(sequence = 100).copy(factorySeconds = 842_900_000)
        val cursor = YpsoHistoryCursor(
            YpsoEventIdentity("serial", 0, 100),
            old.fingerprint(),
            21,
        )
        val afterClockBack = entry(sequence = 101).copy(factorySeconds = 842_899_880)
        val stable = assertInstanceOf(
            YpsoHistoryReconciliation.Stable::class.java,
            YpsoHistoryReconciler.reconcile(
                cursor,
                snapshot(2, 2, listOf(afterClockBack, old.copy(index = 1)), fullCoverage = true),
            ),
        )
        assertEquals(listOf(101L), stable.newEventsOldestFirst.map { it.identity.sequence })
        assertEquals(842_899_880, stable.newEventsOldestFirst.single().entry.factorySeconds)
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

    @Test
    fun `transformed target rows classify to evidenced kinds without claiming command origin`() {
        fun wire(hex: String): YpsoHistoryEntry =
            checkNotNull(YpsoHistoryEntry.decodeWire(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
        assertEquals(
            YpsoHistoryKind.IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED,
            YpsoHistoryClassifier.classify(wire("7856341202960000000000650000000000ac4d")).kind,
        )
        assertEquals(
            YpsoHistoryKind.DELAYED_BOLUS_COMPLETED,
            YpsoHistoryClassifier.classify(wire("78563412032c010f0000006600000000001989")).kind,
        )
        assertEquals(
            YpsoHistoryKind.PRIMING_FINISHED,
            YpsoHistoryClassifier.classify(wire("78563412046400002eef2c670000000000dbc6")).kind,
        )
        assertEquals(
            YpsoHistoryKind.TEMP_BASAL_TERMINAL_UNRESOLVED,
            YpsoHistoryClassifier.classify(wire("785634120ac8001e000000690000000000808d")).kind,
        )

        val active = entry(sequence = 47881, type = 9, v1 = 150, v2 = 15)
        val cancelled = entry(sequence = 47881, type = 10, v1 = 150, v2 = 1)
        assertEquals(active.fingerprint(), cancelled.fingerprint())
        assertEquals(
            YpsoHistoryKind.REWIND_FINISHED,
            YpsoHistoryClassifier.classify(wire("785634121091ff0000c9006b0000000000f3e8")).kind,
        )
        for (hex in listOf(
            "7856341202960000000000650000000000ac4d",
            "78563412032c010f0000006600000000001989",
            "78563412046400002eef2c670000000000dbc6",
            "785634120996000f000000680000000000d6e4",
            "785634120ac8001e000000690000000000808d",
            "785634120e0a00000000006a0000000000ce63",
            "785634121091ff0000c9006b0000000000f3e8",
        )) {
            assertFalse(YpsoHistoryClassifier.classify(wire(hex)).commandOriginAttributable)
        }
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
