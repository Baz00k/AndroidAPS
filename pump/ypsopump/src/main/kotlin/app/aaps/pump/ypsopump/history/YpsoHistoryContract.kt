package app.aaps.pump.ypsopump.history

/**
 * Event meanings for target firmware V05.00.52. Type identity is target-paired for the numbers this
 * evidence session exercised; every remaining number is admitted because two published third-party
 * Ypso protocol implementations agree on it, and all target-paired numbers agree with that table.
 * The publications may share lineage (one cites a common Python reference), so they corroborate
 * rather than independently confirm; target rows remain the empirical anchor. Value layouts are
 * claimed only where the target paired them, plus the centi-unit bolus convention shared by the
 * paired rows. Bolus completion rows (2, 3, 18) were paired against pump-reported delivery and carry
 * the delivered amount, including partial amounts after a bench cancellation. Abort rows (29-31)
 * remain unpaired and their amount fields are not accounting evidence. Unknown numbers and unclaimed
 * fields stay fail-closed.
 */
enum class YpsoHistoryKind {
    // Bolus family
    DELAYED_BOLUS_RUNNING,
    IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED,
    DELAYED_BOLUS_COMPLETED,
    COMBINED_BOLUS_RUNNING,
    COMBINED_BOLUS_COMPLETED,
    IMMEDIATE_BOLUS_RUNNING,
    DELAYED_BOLUS_BACKUP,
    COMBINED_BOLUS_BACKUP,
    BLIND_BOLUS_COMPLETED,
    BLIND_BOLUS_RUNNING,
    BLIND_BOLUS_ABORTED,
    IMMEDIATE_BOLUS_ABORTED,
    DELAYED_BOLUS_ABORTED,
    COMBINED_BOLUS_ABORTED,
    BOLUS_STEP_CHANGED,
    BOLUS_AMOUNT_CAP_CHANGED,
    // Basal family
    BASAL_PROFILE_CHANGED,
    BASAL_PROFILE_A_CHANGED,
    BASAL_PROFILE_B_CHANGED,
    BASAL_RATE_CAP_CHANGED,
    TEMP_BASAL_STARTED,
    TEMP_BASAL_COMPLETED,
    TEMP_BASAL_CANCELLED,
    TEMP_BASAL_TERMINAL_UNRESOLVED,
    TEMP_BASAL_ABORTED,
    TEMP_BASAL_BACKUP,
    // System family
    PRIMING_FINISHED,
    CANNULA_PRIMING_FINISHED,
    REWIND_FINISHED,
    DATE_CHANGED,
    TIME_CHANGED,
    PUMP_MODE_CHANGED,
    DAILY_TOTAL_INSULIN,
    BATTERY_REMOVED,
    DELIVERY_STATUS_CHANGED,
    // Alarm family
    ALARM,
    UNKNOWN,
}

enum class YpsoPumpModeChange { STOPPED, RESUMED }

/** Alarm codes published by both references; alarm-row value fields are deliberately unclaimed. */
enum class YpsoAlarm {
    BATTERY_REMOVED,
    BATTERY_EMPTY,
    REUSABLE_ERROR,
    NO_CARTRIDGE,
    CARTRIDGE_EMPTY,
    OCCLUSION,
    AUTO_STOP,
    LIPO_DISCHARGED,
    BATTERY_REJECTED,
}

data class YpsoHistorySemantics(
    val kind: YpsoHistoryKind,
    val amountUnits: Double? = null,
    val percent: Int? = null,
    /** Delayed/square terminal elapsed minutes (type 3), quantized by the pump; zero can include insulin. */
    val durationMinutes: Int? = null,
    /** Requested TBR minutes from the active type-9 row. */
    val requestedDurationMinutes: Int? = null,
    /** Elapsed/final TBR minutes from a terminal type-10 row or in-place rewrite. */
    val elapsedDurationMinutes: Int? = null,
    val modeChange: YpsoPumpModeChange? = null,
    /** Explicit alarm code for the ALARM kind; alarm-row value fields are unclaimed. */
    val alarm: YpsoAlarm? = null,
    val commandOriginAttributable: Boolean = false,
)

/**
 * V05.00.52 target contract. Type 2 contains no qualified origin field: manual and remote immediate
 * boluses must not be distinguished by amount, recency or receipt time. Target-paired rows override
 * reference claims (type 3 value2 is programmed duration, not a second amount).
 */
object YpsoHistoryClassifier {
    fun classify(entry: YpsoHistoryEntry): YpsoHistorySemantics =
        when (entry.eventType) {
            1 -> bolus(YpsoHistoryKind.DELAYED_BOLUS_RUNNING, entry)
            // Paired: a completed 10.0-U standard bolus and a same-link cancelled 10.0-U standard
            // bolus both reported the delivered amount (1000 and 91 centi-units).
            2 -> YpsoHistorySemantics(
                YpsoHistoryKind.IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED,
                amountUnits = entry.value1 / 100.0,
            )
            // Paired: 0.5-U/15-min cancellations reported 8 centi-units with elapsed 0 and 2 minutes.
            3 -> YpsoHistorySemantics(
                YpsoHistoryKind.DELAYED_BOLUS_COMPLETED,
                amountUnits = entry.value1 / 100.0,
                durationMinutes = entry.value2,
            )
            4 -> YpsoHistorySemantics(YpsoHistoryKind.PRIMING_FINISHED, amountUnits = entry.value1 / 100.0)
            5 -> YpsoHistorySemantics(YpsoHistoryKind.BOLUS_STEP_CHANGED)
            6 -> YpsoHistorySemantics(YpsoHistoryKind.BASAL_PROFILE_CHANGED)
            7 -> YpsoHistorySemantics(YpsoHistoryKind.BASAL_PROFILE_A_CHANGED)
            8 -> YpsoHistorySemantics(YpsoHistoryKind.BASAL_PROFILE_B_CHANGED)
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
            12 -> YpsoHistorySemantics(YpsoHistoryKind.DATE_CHANGED)
            13 -> YpsoHistorySemantics(YpsoHistoryKind.TIME_CHANGED)
            14 -> when (entry.value1) {
                3 -> YpsoHistorySemantics(YpsoHistoryKind.PUMP_MODE_CHANGED, modeChange = YpsoPumpModeChange.STOPPED)
                10 -> YpsoHistorySemantics(YpsoHistoryKind.PUMP_MODE_CHANGED, modeChange = YpsoPumpModeChange.RESUMED)
                else -> YpsoHistorySemantics(YpsoHistoryKind.UNKNOWN)
            }
            16 -> YpsoHistorySemantics(YpsoHistoryKind.REWIND_FINISHED)
            17 -> bolus(YpsoHistoryKind.COMBINED_BOLUS_RUNNING, entry)
            // Paired: a completed 1.0-U combination (0.4 immediate + 0.6/15 min) reported
            // value1 = 100 (delivered total), value2 = 40 (immediate part), value3 = 15 (minutes);
            // a cancelled run reported value1 = 40 (delivered at abort). Value1 is the delivered
            // total, matching the reference immediate = value2 layout.
            18 -> bolus(YpsoHistoryKind.COMBINED_BOLUS_COMPLETED, entry)
            19 -> bolus(YpsoHistoryKind.IMMEDIATE_BOLUS_RUNNING, entry)
            20 -> YpsoHistorySemantics(YpsoHistoryKind.DELAYED_BOLUS_BACKUP)
            21 -> YpsoHistorySemantics(YpsoHistoryKind.COMBINED_BOLUS_BACKUP)
            22 -> YpsoHistorySemantics(YpsoHistoryKind.TEMP_BASAL_BACKUP)
            23 -> YpsoHistorySemantics(YpsoHistoryKind.DAILY_TOTAL_INSULIN)
            24 -> YpsoHistorySemantics(YpsoHistoryKind.BATTERY_REMOVED)
            25 -> YpsoHistorySemantics(YpsoHistoryKind.CANNULA_PRIMING_FINISHED)
            26 -> bolus(YpsoHistoryKind.BLIND_BOLUS_COMPLETED, entry)
            27 -> bolus(YpsoHistoryKind.BLIND_BOLUS_RUNNING, entry)
            28 -> bolus(YpsoHistoryKind.BLIND_BOLUS_ABORTED, entry)
            29 -> bolus(YpsoHistoryKind.IMMEDIATE_BOLUS_ABORTED, entry)
            30 -> bolus(YpsoHistoryKind.DELAYED_BOLUS_ABORTED, entry)
            31 -> bolus(YpsoHistoryKind.COMBINED_BOLUS_ABORTED, entry)
            32 -> YpsoHistorySemantics(YpsoHistoryKind.TEMP_BASAL_ABORTED, percent = entry.value1)
            33 -> YpsoHistorySemantics(YpsoHistoryKind.BOLUS_AMOUNT_CAP_CHANGED)
            34 -> YpsoHistorySemantics(YpsoHistoryKind.BASAL_RATE_CAP_CHANGED)
            100 -> alarm(YpsoAlarm.BATTERY_REMOVED)
            101 -> alarm(YpsoAlarm.BATTERY_EMPTY)
            102 -> alarm(YpsoAlarm.REUSABLE_ERROR)
            103 -> alarm(YpsoAlarm.NO_CARTRIDGE)
            104 -> alarm(YpsoAlarm.CARTRIDGE_EMPTY)
            105 -> alarm(YpsoAlarm.OCCLUSION)
            106 -> alarm(YpsoAlarm.AUTO_STOP)
            107 -> alarm(YpsoAlarm.LIPO_DISCHARGED)
            108 -> alarm(YpsoAlarm.BATTERY_REJECTED)
            150 -> YpsoHistorySemantics(YpsoHistoryKind.DELIVERY_STATUS_CHANGED)
            else -> YpsoHistorySemantics(YpsoHistoryKind.UNKNOWN)
        }

    /** Centi-unit bolus convention shared by the paired target rows and both references. */
    private fun bolus(kind: YpsoHistoryKind, entry: YpsoHistoryEntry) =
        YpsoHistorySemantics(kind, amountUnits = entry.value1 / 100.0)

    private fun alarm(code: YpsoAlarm) = YpsoHistorySemantics(YpsoHistoryKind.ALARM, alarm = code)
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
    val fingerprint: YpsoHistoryFingerprint,
    /** Authenticated pump reboot counter observed when this cursor was anchored. */
    val pumpReboot: Long,
    /** The pump permits one active TBR; its row may later be rewritten below the newest cursor. */
    val activeTbr: YpsoMutableHistoryState? = null,
)

data class YpsoMutableHistoryState(
    val identity: YpsoEventIdentity,
    val fingerprint: YpsoHistoryFingerprint,
    val stateFingerprint: YpsoHistoryFingerprint,
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
    /**
     * Pump clock minus phone clock in milliseconds, measured on the scan's link, or null when it
     * could not be read. Includes whole-second truncation and read latency (about ±2 s).
     */
    val pumpClockOffsetMs: Long? = null,
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

    /** Diagnostic detail for the most recent INVALID_SNAPSHOT rejection; not part of the contract. */
    @Volatile
    var lastInvalidSnapshotDetail: String? = null
        private set

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
            it.matchesCursor(cursor, snapshot.pumpRebootAfter)
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
        val cursorRow = snapshot.rowsNewestFirst[cursorIndex]
        if (cursorRow.fingerprint() != cursor.fingerprint) {
            stateUpdates += YpsoHistoryEvent(cursor.identity, cursorRow)
        }
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
            val rebootReset = snapshot.pumpRebootAfter != cursor.pumpReboot && entry.sequence < priorSequence
            if (delta >= HALF_RANGE && !rebootReset) {
                return YpsoHistoryReconciliation.Gap(YpsoHistoryReconciliation.Reason.INVALID_ORDER_OR_RESET)
            }
            if (entry.sequence < priorSequence) {
                if (generation == Int.MAX_VALUE) {
                    return YpsoHistoryReconciliation.Gap(YpsoHistoryReconciliation.Reason.SEQUENCE_GENERATION_OVERFLOW)
                }
                generation++
            }
            val identity = YpsoEventIdentity(cursor.identity.pumpSerial, generation, entry.sequence)
            val event = YpsoHistoryEvent(identity, entry)
            newEvents += event
            if (event.semantics.kind == YpsoHistoryKind.TEMP_BASAL_STARTED) {
                // The snapshot validator rejects more than one active row in the window, and a
                // still-active tracked row is inside the window (verified above), so a start here
                // can only follow its terminal rewrite: a legitimate replacement start.
                activeTbr = YpsoMutableHistoryState(
                    identity,
                    entry.fingerprint(),
                    entry.stateFingerprint(),
                    entry.value1,
                    entry.value2,
                )
            } else if (event.semantics.kind == YpsoHistoryKind.TEMP_BASAL_ABORTED) {
                // Reference semantics: a TBR abort is a separate row, not an in-place rewrite of the
                // tracked row. Retaining the tracked state would assert a live TBR indefinitely, so
                // tracking ends here without fabricating a terminal row. Target verification of this
                // wire interaction is still pending; the abort event itself carries the semantics.
                activeTbr = null
            }
            priorSequence = entry.sequence
        }
        val latest = newEvents.lastOrNull()
        // Keep a running bolus inside the next scan window. Otherwise a newer system event moves
        // the cursor past it and its eventual in-place terminal amount is never read again.
        val runningAnchor = (listOf(YpsoHistoryEvent(cursor.identity, cursorRow)) + newEvents)
            .firstOrNull { it.entry.eventType in setOf(1, 17, 19) }
        // Finding the exact durable cursor in a stable snapshot proves continuity across a reboot.
        // A lower subsequent sequence is assigned the next generation above, while a scan that cannot
        // find the cursor remains a gap and cannot silently re-anchor or lose insulin.
        val nextCursor = (runningAnchor ?: latest)?.let {
            YpsoHistoryCursor(it.identity, it.entry.fingerprint(), snapshot.pumpRebootAfter, activeTbr)
        } ?: cursor.copy(fingerprint = cursorRow.fingerprint(), pumpReboot = snapshot.pumpRebootAfter, activeTbr = activeTbr)
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
    ): YpsoMutableHistoryState? {
        val candidate = activeTbrAfterReplay() ?: return null
        return candidate.let {
            YpsoMutableHistoryState(
                YpsoEventIdentity(pumpSerial, sequenceGeneration, it.sequence),
                it.fingerprint(),
                it.stateFingerprint(),
                it.value1,
                it.value2,
            )
        }
    }

    private fun invalidStableSnapshot(snapshot: YpsoHistorySnapshot): YpsoHistoryReconciliation.Gap? {
        val stableCount = snapshot.countAfter
        if (!snapshot.rowsNewestFirst.hasValidTbrTransitions()) {
            // Historical type-9 rows can remain after a separate type-32 abort. Replay transitions
            // oldest-first so abort-then-replacement is valid, while overlapping starts still fail.
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
            lastInvalidSnapshotDetail = when {
                snapshot.rowsNewestFirst.size > stableCount -> "scanned ${snapshot.rowsNewestFirst.size} rows above count $stableCount"
                snapshot.fullCoverage && snapshot.rowsNewestFirst.size != stableCount ->
                    "full coverage claimed with ${snapshot.rowsNewestFirst.size} of $stableCount rows"
                duplicateIdenticalSequence -> "duplicate sequences in one snapshot"
                else -> "row index does not match its scan position"
            }
            YpsoHistoryReconciliation.Gap(YpsoHistoryReconciliation.Reason.INVALID_SNAPSHOT)
        } else {
            null
        }
    }

    private fun List<YpsoHistoryEntry>.hasValidTbrTransitions(): Boolean {
        var active = false
        for (entry in asReversed()) {
            when (YpsoHistoryClassifier.classify(entry).kind) {
                YpsoHistoryKind.TEMP_BASAL_STARTED -> {
                    if (active) return false
                    active = true
                }
                YpsoHistoryKind.TEMP_BASAL_ABORTED -> active = false
                else -> Unit
            }
        }
        return true
    }

    private fun List<YpsoHistoryEntry>.activeTbrAfterReplay(): YpsoHistoryEntry? {
        var active: YpsoHistoryEntry? = null
        for (entry in asReversed()) {
            when (YpsoHistoryClassifier.classify(entry).kind) {
                YpsoHistoryKind.TEMP_BASAL_STARTED -> active = entry
                YpsoHistoryKind.TEMP_BASAL_ABORTED -> active = null
                else -> Unit
            }
        }
        return active
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
