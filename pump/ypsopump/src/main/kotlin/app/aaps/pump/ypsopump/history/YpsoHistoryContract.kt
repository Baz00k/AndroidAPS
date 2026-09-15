package app.aaps.pump.ypsopump.history

/** Target-evidenced meaning. Reference-only event numbers remain unsupported until paired. */
enum class YpsoHistoryKind {
    IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED,
    DELAYED_BOLUS_COMPLETED,
    PRIMING_FINISHED,
    TEMP_BASAL_STARTED,
    TEMP_BASAL_TERMINAL_UNRESOLVED,
    PUMP_MODE_CHANGED,
    REWIND_FINISHED,
    UNKNOWN,
}

enum class YpsoPumpModeChange { STOPPED, RESUMED, UNKNOWN }

data class YpsoHistorySemantics(
    val kind: YpsoHistoryKind,
    val amountUnits: Double? = null,
    val percent: Int? = null,
    val durationMinutes: Int? = null,
    val modeChange: YpsoPumpModeChange? = null,
    val commandOriginAttributable: Boolean = false,
)

/**
 * V05.00.52 target contract from paired observations. Type 2 contains no qualified origin field:
 * manual and remote immediate boluses must not be distinguished by amount, recency or receipt time.
 */
object YpsoHistoryClassifier {
    fun classify(entry: YpsoHistoryEntry): YpsoHistorySemantics =
        when (entry.eventType) {
            2 -> YpsoHistorySemantics(
                YpsoHistoryKind.IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED,
                amountUnits = entry.value1 / 100.0,
            )
            3 -> YpsoHistorySemantics(
                YpsoHistoryKind.DELAYED_BOLUS_COMPLETED,
                amountUnits = entry.value1 / 100.0,
                durationMinutes = entry.value2,
            )
            4 -> YpsoHistorySemantics(YpsoHistoryKind.PRIMING_FINISHED, amountUnits = entry.value1 / 100.0)
            9 -> YpsoHistorySemantics(
                YpsoHistoryKind.TEMP_BASAL_STARTED,
                percent = entry.value1,
                durationMinutes = entry.value2,
            )
            10 -> YpsoHistorySemantics(
                YpsoHistoryKind.TEMP_BASAL_TERMINAL_UNRESOLVED,
                percent = entry.value1,
                durationMinutes = entry.value2,
            )
            14 -> when (entry.value1) {
                3 -> YpsoHistorySemantics(YpsoHistoryKind.PUMP_MODE_CHANGED, modeChange = YpsoPumpModeChange.STOPPED)
                10 -> YpsoHistorySemantics(YpsoHistoryKind.PUMP_MODE_CHANGED, modeChange = YpsoPumpModeChange.RESUMED)
                else -> YpsoHistorySemantics(YpsoHistoryKind.UNKNOWN)
            }
            16 -> YpsoHistorySemantics(YpsoHistoryKind.REWIND_FINISHED)
            else -> YpsoHistorySemantics(YpsoHistoryKind.UNKNOWN)
        }
}

/** Stable event identity. Ring index, receipt time, amount and crypto key generation are not identity. */
data class YpsoEventIdentity(
    val pumpSerial: String,
    val sequenceGeneration: Int,
    val sequence: Long,
) {
    init {
        require(pumpSerial.isNotBlank())
        require(sequenceGeneration >= 0)
        require(sequence in 0..0xffffffffL)
    }

    /** AAPS PumpSync ID; PumpSync additionally scopes this by pump type and serial. */
    val aapsPumpId: Long
        get() = (sequenceGeneration.toLong() shl 32) or sequence
}

data class YpsoHistoryCursor(
    val identity: YpsoEventIdentity,
    val fingerprint: String,
    /** Authenticated pump reboot counter observed when this cursor was anchored. */
    val pumpReboot: Long,
)

data class YpsoHistoryEvent(
    val identity: YpsoEventIdentity,
    val entry: YpsoHistoryEntry,
    val semantics: YpsoHistorySemantics = YpsoHistoryClassifier.classify(entry),
)

data class YpsoHistorySnapshot(
    val countBefore: Int,
    val countAfter: Int,
    val pumpRebootBefore: Long,
    val pumpRebootAfter: Long,
    /** Independently read moving-head anchors around the row scan; null only for proven empty history. */
    val headBefore: YpsoHistoryEntry?,
    val headAfter: YpsoHistoryEntry?,
    /** Rows in pump order: moving head index 0 first, older rows after it. */
    val rowsNewestFirst: List<YpsoHistoryEntry>,
    /** True only when every row represented by the count was read under the snapshot contract. */
    val fullCoverage: Boolean,
)

data class YpsoHistoryAttemptCursor(
    val cursor: YpsoHistoryCursor,
    val capturedBeforeDispatch: Boolean,
)

sealed interface YpsoHistoryAttribution {
    data class Attributed(val event: YpsoHistoryEvent) : YpsoHistoryAttribution
    data class Blocked(val reason: Reason) : YpsoHistoryAttribution

    enum class Reason {
        CURSOR_NOT_CAPTURED_BEFORE_DISPATCH,
        HISTORY_NOT_STABLE,
        NO_NEW_EVENT,
        MULTIPLE_COMPATIBLE_EVENTS,
        UNSUPPORTED_EVENT_KIND,
        ORIGIN_NOT_ENCODED,
    }
}

sealed interface YpsoHistoryReconciliation {
    data class Bootstrap(val cursor: YpsoHistoryCursor?) : YpsoHistoryReconciliation
    data class Stable(
        val previousCursor: YpsoHistoryCursor,
        val cursor: YpsoHistoryCursor,
        val newEventsOldestFirst: List<YpsoHistoryEvent>,
    ) : YpsoHistoryReconciliation
    data class Moving(
        val countBefore: Int,
        val countAfter: Int,
        val pumpRebootBefore: Long,
        val pumpRebootAfter: Long,
        val headSequenceBefore: Long?,
        val headSequenceAfter: Long?,
    ) : YpsoHistoryReconciliation
    data class Gap(val reason: Reason) : YpsoHistoryReconciliation

    enum class Reason {
        EMPTY_AFTER_CURSOR,
        CURSOR_OVERWRITTEN_OR_SEQUENCE_RESET,
        COVERAGE_INCOMPLETE,
        INVALID_SNAPSHOT,
        CONFLICTING_SEQUENCE_PAYLOAD,
        INVALID_ORDER_OR_RESET,
        SEQUENCE_RESET_AFTER_REBOOT,
        SEQUENCE_GENERATION_OVERFLOW,
    }
}

/** Deterministic deduplication and moving-ring reconciliation independent of receipt time and amount. */
object YpsoHistoryReconciler {
    private const val HALF_RANGE = 0x80000000L
    private const val MODULUS = 0x100000000L

    fun bootstrap(pumpSerial: String, sequenceGeneration: Int, snapshot: YpsoHistorySnapshot): YpsoHistoryReconciliation {
        invalidCounts(snapshot)?.let { return it }
        moving(snapshot)?.let { return it }
        invalidStableSnapshot(snapshot)?.let { return it }
        val newest = snapshot.rowsNewestFirst.firstOrNull()
        return YpsoHistoryReconciliation.Bootstrap(
            newest?.let {
                YpsoHistoryCursor(
                    YpsoEventIdentity(pumpSerial, sequenceGeneration, it.sequence),
                    it.fingerprint(),
                    snapshot.pumpRebootAfter,
                )
            },
        )
    }

    fun reconcile(cursor: YpsoHistoryCursor, snapshot: YpsoHistorySnapshot): YpsoHistoryReconciliation {
        invalidCounts(snapshot)?.let { return it }
        moving(snapshot)?.let { return it }
        invalidStableSnapshot(snapshot)?.let { return it }
        if (snapshot.rowsNewestFirst.isEmpty()) {
            return YpsoHistoryReconciliation.Gap(YpsoHistoryReconciliation.Reason.EMPTY_AFTER_CURSOR)
        }
        val conflicts = snapshot.rowsNewestFirst.groupBy(YpsoHistoryEntry::sequence).values.any { rows ->
            rows.map(YpsoHistoryEntry::fingerprint).distinct().size > 1
        }
        if (conflicts) return YpsoHistoryReconciliation.Gap(YpsoHistoryReconciliation.Reason.CONFLICTING_SEQUENCE_PAYLOAD)

        val cursorIndex = snapshot.rowsNewestFirst.indexOfFirst {
            it.sequence == cursor.identity.sequence && it.fingerprint() == cursor.fingerprint
        }
        if (cursorIndex < 0) {
            return YpsoHistoryReconciliation.Gap(
                if (snapshot.fullCoverage) {
                    YpsoHistoryReconciliation.Reason.CURSOR_OVERWRITTEN_OR_SEQUENCE_RESET
                } else {
                    YpsoHistoryReconciliation.Reason.COVERAGE_INCOMPLETE
                },
            )
        }

        var generation = cursor.identity.sequenceGeneration
        var priorSequence = cursor.identity.sequence
        val newEvents = mutableListOf<YpsoHistoryEvent>()
        val chronological = snapshot.rowsNewestFirst.subList(0, cursorIndex).asReversed()
        for (entry in chronological) {
            val delta = (entry.sequence - priorSequence + MODULUS) % MODULUS
            if (delta == 0L) continue
            if (delta >= HALF_RANGE) {
                return YpsoHistoryReconciliation.Gap(YpsoHistoryReconciliation.Reason.INVALID_ORDER_OR_RESET)
            }
            if (entry.sequence < priorSequence) {
                if (snapshot.pumpRebootAfter != cursor.pumpReboot) {
                    return YpsoHistoryReconciliation.Gap(YpsoHistoryReconciliation.Reason.SEQUENCE_RESET_AFTER_REBOOT)
                }
                if (generation == Int.MAX_VALUE) {
                    return YpsoHistoryReconciliation.Gap(YpsoHistoryReconciliation.Reason.SEQUENCE_GENERATION_OVERFLOW)
                }
                generation++
            }
            val identity = YpsoEventIdentity(cursor.identity.pumpSerial, generation, entry.sequence)
            newEvents += YpsoHistoryEvent(identity, entry)
            priorSequence = entry.sequence
        }
        val latest = newEvents.lastOrNull()
        // Do not absorb a reboot into an unchanged cursor. A later lower sequence may be a reset
        // caused by that reboot; retaining the cursor's original reboot keeps that ambiguity blocked.
        val nextCursor = latest?.let {
            YpsoHistoryCursor(it.identity, it.entry.fingerprint(), snapshot.pumpRebootAfter)
        } ?: cursor
        return YpsoHistoryReconciliation.Stable(cursor, nextCursor, newEvents)
    }

    private fun invalidCounts(snapshot: YpsoHistorySnapshot): YpsoHistoryReconciliation.Gap? =
        if (snapshot.countBefore < 0 || snapshot.countAfter < 0) {
            YpsoHistoryReconciliation.Gap(YpsoHistoryReconciliation.Reason.INVALID_SNAPSHOT)
        } else {
            null
        }

    private fun invalidStableSnapshot(snapshot: YpsoHistorySnapshot): YpsoHistoryReconciliation.Gap? {
        val stableCount = snapshot.countAfter
        val duplicateIdenticalSequence = snapshot.rowsNewestFirst
            .groupBy(YpsoHistoryEntry::sequence)
            .values
            .any { rows -> rows.size > 1 && rows.map(YpsoHistoryEntry::fingerprint).distinct().size == 1 }
        val invalid =
            snapshot.rowsNewestFirst.size > stableCount ||
                (snapshot.fullCoverage && snapshot.rowsNewestFirst.size != stableCount) ||
                duplicateIdenticalSequence ||
                snapshot.rowsNewestFirst.withIndex().any { (logicalIndex, row) -> row.index != logicalIndex }
        return if (invalid) {
            YpsoHistoryReconciliation.Gap(YpsoHistoryReconciliation.Reason.INVALID_SNAPSHOT)
        } else {
            null
        }
    }

    private fun moving(snapshot: YpsoHistorySnapshot): YpsoHistoryReconciliation.Moving? {
        val before = snapshot.headBefore
        val after = snapshot.headAfter
        val emptyStable =
            snapshot.countBefore == 0 && snapshot.countAfter == 0 &&
                snapshot.pumpRebootBefore == snapshot.pumpRebootAfter &&
                before == null && after == null
        val anchoredStable =
            snapshot.countBefore == snapshot.countAfter &&
                snapshot.pumpRebootBefore == snapshot.pumpRebootAfter &&
                before != null && after != null &&
                before.index == 0 && after.index == 0 &&
                before.sequence == after.sequence && before.fingerprint() == after.fingerprint() &&
                snapshot.rowsNewestFirst.firstOrNull()?.let {
                    it.sequence == before.sequence && it.fingerprint() == before.fingerprint()
                } == true
        if (emptyStable || anchoredStable) return null
        return YpsoHistoryReconciliation.Moving(
            snapshot.countBefore,
            snapshot.countAfter,
            snapshot.pumpRebootBefore,
            snapshot.pumpRebootAfter,
            before?.sequence,
            after?.sequence,
        )
    }
}

/**
 * Command attribution is deliberately stricter than ingestion. A unique newer compatible row is
 * necessary, but event kind alone does not prove whether a type-2 bolus was manual or remotely commanded.
 */
object YpsoHistoryAttributor {
    fun attribute(
        attempt: YpsoHistoryAttemptCursor,
        reconciliation: YpsoHistoryReconciliation,
        compatible: (YpsoHistorySemantics) -> Boolean,
    ): YpsoHistoryAttribution {
        if (!attempt.capturedBeforeDispatch) {
            return YpsoHistoryAttribution.Blocked(YpsoHistoryAttribution.Reason.CURSOR_NOT_CAPTURED_BEFORE_DISPATCH)
        }
        val stable = reconciliation as? YpsoHistoryReconciliation.Stable
            ?: return YpsoHistoryAttribution.Blocked(YpsoHistoryAttribution.Reason.HISTORY_NOT_STABLE)
        if (stable.previousCursor != attempt.cursor) {
            return YpsoHistoryAttribution.Blocked(YpsoHistoryAttribution.Reason.HISTORY_NOT_STABLE)
        }
        if (stable.newEventsOldestFirst.any { it.semantics.kind == YpsoHistoryKind.UNKNOWN }) {
            return YpsoHistoryAttribution.Blocked(YpsoHistoryAttribution.Reason.UNSUPPORTED_EVENT_KIND)
        }
        val matches = stable.newEventsOldestFirst.filter { compatible(it.semantics) }
        val event = when (matches.size) {
            0 -> return YpsoHistoryAttribution.Blocked(YpsoHistoryAttribution.Reason.NO_NEW_EVENT)
            1 -> matches.single()
            else -> return YpsoHistoryAttribution.Blocked(YpsoHistoryAttribution.Reason.MULTIPLE_COMPATIBLE_EVENTS)
        }
        if (!event.semantics.commandOriginAttributable) {
            return YpsoHistoryAttribution.Blocked(YpsoHistoryAttribution.Reason.ORIGIN_NOT_ENCODED)
        }
        return YpsoHistoryAttribution.Attributed(event)
    }
}
