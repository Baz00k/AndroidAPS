package app.aaps.pump.ypsopump.history

/** Target-evidenced meaning. Reference-only event numbers remain unsupported until paired. */
enum class YpsoHistoryKind {
    IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED,
    DELAYED_BOLUS_COMPLETED,
    PRIMING_FINISHED,
    BASAL_PROFILE_CHANGED,
    TEMP_BASAL_STARTED,
    TEMP_BASAL_COMPLETED,
    TEMP_BASAL_CANCELLED,
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
    /** Delayed/square bolus programmed duration (type 3); never a TBR request or elapsed value. */
    val durationMinutes: Int? = null,
    /** Requested TBR minutes from the active type-9 row. */
    val requestedDurationMinutes: Int? = null,
    /** Elapsed/final TBR minutes from a terminal type-10 row or in-place rewrite. */
    val elapsedDurationMinutes: Int? = null,
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
            6 -> YpsoHistorySemantics(YpsoHistoryKind.BASAL_PROFILE_CHANGED)
            9 -> YpsoHistorySemantics(
                YpsoHistoryKind.TEMP_BASAL_STARTED,
                percent = entry.value1,
                requestedDurationMinutes = entry.value2,
            )
            10 -> YpsoHistorySemantics(
                YpsoHistoryKind.TEMP_BASAL_TERMINAL_UNRESOLVED,
                percent = entry.value1,
                elapsedDurationMinutes = entry.value2,
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
    /** Identity fingerprint; mutable terminal-state fields are deliberately excluded. */
    val fingerprint: String,
    /** Authenticated pump reboot counter observed when this cursor was anchored. */
    val pumpReboot: Long,
    /** The pump permits one active TBR; its row may later be rewritten below the newest cursor. */
    val activeTbr: YpsoMutableHistoryState? = null,
)

data class YpsoMutableHistoryState(
    val identity: YpsoEventIdentity,
    val fingerprint: String,
    val stateFingerprint: String,
    val percent: Int,
    val requestedDurationMinutes: Int,
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
        /** In-place changes whose relative time versus newer events is not encoded by the row. */
        val stateUpdates: List<YpsoHistoryEvent> = emptyList(),
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
        TRACKED_TBR_ROW_MISSING,
        MULTIPLE_ACTIVE_TBR_ROWS,
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
                    snapshot.rowsNewestFirst.singleActiveTbr(pumpSerial, sequenceGeneration),
                )
            },
        )
    }

    fun reconcile(cursor: YpsoHistoryCursor, snapshot: YpsoHistorySnapshot): YpsoHistoryReconciliation {
        invalidCounts(snapshot)?.let { return it }
        moving(snapshot)?.let { return it }
        if (hasConflictingSequencePayload(snapshot)) {
            return YpsoHistoryReconciliation.Gap(YpsoHistoryReconciliation.Reason.CONFLICTING_SEQUENCE_PAYLOAD)
        }
        invalidStableSnapshot(snapshot)?.let { return it }
        if (snapshot.rowsNewestFirst.isEmpty()) {
            return YpsoHistoryReconciliation.Gap(YpsoHistoryReconciliation.Reason.EMPTY_AFTER_CURSOR)
        }

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
        val stateUpdates = mutableListOf<YpsoHistoryEvent>()
        var activeTbr = cursor.activeTbr
        activeTbr?.let { tracked ->
            val current = snapshot.rowsNewestFirst.firstOrNull {
                it.sequence == tracked.identity.sequence && it.fingerprint() == tracked.fingerprint
            } ?: return YpsoHistoryReconciliation.Gap(
                if (snapshot.fullCoverage) {
                    // Complete coverage proves the mutable row is gone; its terminal state is unknowable.
                    YpsoHistoryReconciliation.Reason.TRACKED_TBR_ROW_MISSING
                } else {
                    YpsoHistoryReconciliation.Reason.COVERAGE_INCOMPLETE
                },
            )
            if (current.stateFingerprint() != tracked.stateFingerprint) {
                val update = YpsoHistoryEvent(
                    tracked.identity,
                    current,
                    semantics =
                        if (current.eventType == 10) {
                            YpsoHistorySemantics(
                                kind =
                                    when {
                                        current.value2 < tracked.requestedDurationMinutes -> YpsoHistoryKind.TEMP_BASAL_CANCELLED
                                        current.value2 == tracked.requestedDurationMinutes -> YpsoHistoryKind.TEMP_BASAL_COMPLETED
                                        else -> YpsoHistoryKind.TEMP_BASAL_TERMINAL_UNRESOLVED
                                    },
                                percent = current.value1,
                                requestedDurationMinutes = tracked.requestedDurationMinutes,
                                elapsedDurationMinutes = current.value2,
                            )
                        } else {
                            YpsoHistoryClassifier.classify(current)
                        },
                )
                if (update.semantics.kind == YpsoHistoryKind.TEMP_BASAL_STARTED) {
                    activeTbr = tracked.copy(stateFingerprint = current.stateFingerprint())
                } else {
                    stateUpdates += update
                    activeTbr = null
                }
            }
        }
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
            val event = YpsoHistoryEvent(identity, entry)
            newEvents += event
            if (event.semantics.kind == YpsoHistoryKind.TEMP_BASAL_STARTED) {
                // The snapshot validator rejects more than one active row, and a tracked row was
                // already verified above, so this can only be a legitimate replacement start.
                activeTbr = YpsoMutableHistoryState(
                    identity,
                    entry.fingerprint(),
                    entry.stateFingerprint(),
                    entry.value1,
                    entry.value2,
                )
            }
            priorSequence = entry.sequence
        }
        val latest = newEvents.lastOrNull()
        // Do not absorb a reboot into an unchanged cursor. A later lower sequence may be a reset
        // caused by that reboot; retaining the cursor's original reboot keeps that ambiguity blocked.
        val nextCursor = latest?.let {
            YpsoHistoryCursor(it.identity, it.entry.fingerprint(), snapshot.pumpRebootAfter, activeTbr)
        } ?: cursor.copy(activeTbr = activeTbr)
        return YpsoHistoryReconciliation.Stable(cursor, nextCursor, newEvents, stateUpdates)
    }

    private fun invalidCounts(snapshot: YpsoHistorySnapshot): YpsoHistoryReconciliation.Gap? =
        if (snapshot.countBefore < 0 || snapshot.countAfter < 0) {
            YpsoHistoryReconciliation.Gap(YpsoHistoryReconciliation.Reason.INVALID_SNAPSHOT)
        } else {
            null
        }

    private fun hasConflictingSequencePayload(snapshot: YpsoHistorySnapshot): Boolean =
        snapshot.rowsNewestFirst.groupBy(YpsoHistoryEntry::sequence).values.any { rows ->
            rows.map(YpsoHistoryEntry::fingerprint).distinct().size > 1
        }

    private fun List<YpsoHistoryEntry>.singleActiveTbr(
        pumpSerial: String,
        sequenceGeneration: Int,
    ): YpsoMutableHistoryState? =
        singleOrNull { YpsoHistoryClassifier.classify(it).kind == YpsoHistoryKind.TEMP_BASAL_STARTED }?.let {
            YpsoMutableHistoryState(
                YpsoEventIdentity(pumpSerial, sequenceGeneration, it.sequence),
                it.fingerprint(),
                it.stateFingerprint(),
                it.value1,
                it.value2,
            )
        }

    private fun invalidStableSnapshot(snapshot: YpsoHistorySnapshot): YpsoHistoryReconciliation.Gap? {
        val stableCount = snapshot.countAfter
        val activeTbrRows = snapshot.rowsNewestFirst.count {
            YpsoHistoryClassifier.classify(it).kind == YpsoHistoryKind.TEMP_BASAL_STARTED
        }
        if (activeTbrRows > 1) {
            // The pump permits one active TBR; two active rows mean the model cannot choose safely.
            return YpsoHistoryReconciliation.Gap(YpsoHistoryReconciliation.Reason.MULTIPLE_ACTIVE_TBR_ROWS)
        }
        val duplicateIdenticalSequence = snapshot.rowsNewestFirst
            .groupBy(YpsoHistoryEntry::sequence)
            .values
            .any { rows -> rows.size > 1 }
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
        // In-place state updates keep the previous identity and are not strictly newer events;
        // they must never satisfy post-dispatch attribution.
        val observed = stable.newEventsOldestFirst
        if (observed.any { it.semantics.kind == YpsoHistoryKind.UNKNOWN }) {
            return YpsoHistoryAttribution.Blocked(YpsoHistoryAttribution.Reason.UNSUPPORTED_EVENT_KIND)
        }
        val matches = observed.filter { compatible(it.semantics) }
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
