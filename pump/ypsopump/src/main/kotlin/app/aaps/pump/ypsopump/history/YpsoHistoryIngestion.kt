package app.aaps.pump.ypsopump.history

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.ypsopump.bolus.YpsoBolusMessage
import java.time.ZoneId

sealed interface YpsoHistoryIngestionResult {
    data class Applied(val cursor: YpsoHistoryCursor?) : YpsoHistoryIngestionResult
    /** BOUNDED retries are owned by ingestion; NONE means the failure needs immediate attention. */
    enum class Retry { NONE, TRANSIENT, BOUNDED }
    data class Blocked(val reason: String, val retry: Retry = Retry.NONE) : YpsoHistoryIngestionResult
}

/** Persist-before-DB history ingestion. Cursor acknowledgement follows successful idempotent DB sync. */
class YpsoHistoryIngestion(
    private val store: YpsoHistoryStateStore,
    private val pumpSync: PumpSync,
    /** Basal accounting for TBR and pump Stop/Run rows; null disables it. */
    private val tbrAccounting: app.aaps.pump.ypsopump.tbr.YpsoTbrHistoryAccounting? = null,
    /** Without an ownership lookup, do not discard doses that might already have DB or journal records. */
    private val hasRecordedBolus: (pumpSerial: String, pumpId: Long) -> Boolean = { _, _ -> true },
    /** Start of AAPS' use of this pump; PumpSync accepts no record from before it. */
    private val registeredAt: (pumpSerial: String) -> Long = { 0L },
    /**
     * Resolves a provisional record created for a dispatched dose onto its pump identity. Without this
     * the terminal history row would insert a second record for the same physical bolus, because
     * PumpSync matches provisional records by temporary id and terminal rows by pump id.
     */
    private val resolveProvisional: (pumpSerial: String, pumpId: Long, timestamp: Long, amount: Double, type: BS.Type) -> Unit = { _, _, _, _, _ -> },
) {
    fun currentCursor(): YpsoHistoryCursor? = store.load().cursor
    // IOB includes past pumps too. Switching the active pump cannot make unresolved records safe.
    fun basalAccountingBlocked(): Boolean = store.load().basalAttributionBlockedSerial != null

    /**
     * Scans that reached a row needing a pump clock reading without one. Kept in memory only: a
     * restart simply grants the reading a few more scans.
     */
    private val clocklessScans = java.util.concurrent.atomic.AtomicInteger(0)
    /** Cheap local gate for a new dose. Never performs pump I/O. */
    fun bolusReadiness(pumpSerial: String, reboot: Long): YpsoBolusMessage? {
        if (basalAccountingBlocked()) return YpsoBolusMessage.SYNC_IN_PROGRESS
        if (!retryPending(pumpSerial)) return YpsoBolusMessage.SAVING_PREVIOUS_DOSE
        val cursor = store.load().cursor ?: return YpsoBolusMessage.SYNC_IN_PROGRESS
        if (cursor.identity.pumpSerial != pumpSerial || cursor.pumpReboot != reboot) {
            return YpsoBolusMessage.PUMP_RESTARTED
        }
        return null
    }
    fun isAccounted(pumpId: Long): Boolean {
        val state = store.load()
        if (state.pendingBolus != null) return false
        return (state.cursor?.identity?.aapsPumpId ?: Long.MIN_VALUE) >= pumpId
    }

    fun retryPending(pumpSerial: String): Boolean {
        val state = store.load()
        val pending = state.pendingBolus ?: return true
        if (pending.pumpSerial != pumpSerial) return false
        // Bind any provisional record for this dose to its pump identity first, so the authoritative
        // row below updates that record instead of creating a duplicate.
        resolveProvisional(
            pending.pumpSerial,
            pending.pumpId,
            pending.timestamp,
            pending.amountCentiUnits / 100.0,
            pending.type,
        )
        val result = pumpSync.replayConfirmedBolusWithPumpIdDetailed(
            pending.timestamp,
            pending.amountCentiUnits / 100.0,
            pending.type,
            pending.pumpId,
            PumpType.YPSOPUMP,
            pending.pumpSerial,
        )
        if (result == PumpSync.BolusSyncResult.REJECTED) return false
        store.commit(state.copy(pendingBolus = null))
        return true
    }

    fun ingest(
        pumpSerial: String,
        zone: ZoneId,
        reboot: Long,
        snapshot: YpsoHistorySnapshot,
        bolusType: (YpsoHistoryEvent) -> BS.Type = { BS.Type.NORMAL },
    ): YpsoHistoryIngestionResult {
        if (!retryPending(pumpSerial)) return YpsoHistoryIngestionResult.Blocked("pending PumpSync record was rejected")
        if (snapshot.pumpRebootBefore != reboot || snapshot.pumpRebootAfter != reboot) {
            return YpsoHistoryIngestionResult.Blocked("history snapshot belongs to another pump reboot epoch")
        }
        val state = store.load()
        val cursor = state.cursor?.takeIf { it.identity.pumpSerial == pumpSerial }
        if (state.basalAttributionBlockedSerial != null && state.basalAttributionBlockedSerial != pumpSerial) {
            return YpsoHistoryIngestionResult.Blocked("temporary basal identities from another pump still require reconciliation")
        }
        val reconciliation = cursor?.let { YpsoHistoryReconciler.reconcile(it, snapshot) }
            ?: YpsoHistoryReconciler.bootstrap(pumpSerial, 0, snapshot)
        when (reconciliation) {
            is YpsoHistoryReconciliation.Bootstrap -> {
                if (basalAccountingBlocked()) return YpsoHistoryIngestionResult.Blocked("temporary basal identities still require reconciliation")
                store.commit(state.copy(cursor = reconciliation.cursor))
                return YpsoHistoryIngestionResult.Applied(reconciliation.cursor)
            }
            is YpsoHistoryReconciliation.Moving -> return YpsoHistoryIngestionResult.Blocked("history moved during scan", retry = YpsoHistoryIngestionResult.Retry.TRANSIENT)
            is YpsoHistoryReconciliation.Gap -> {
                if (tbrAccounting?.hasUnprovenStarts(pumpSerial) == true) {
                    store.commit(store.load().copy(basalAttributionBlockedSerial = pumpSerial))
                }
                val detail = YpsoHistoryReconciler.lastInvalidSnapshotDetail
                    ?.takeIf { reconciliation.reason == YpsoHistoryReconciliation.Reason.INVALID_SNAPSHOT }
                return YpsoHistoryIngestionResult.Blocked(
                    "history gap: ${reconciliation.reason}${detail?.let { " ($it)" } ?: ""}",
                )
            }
            is YpsoHistoryReconciliation.Stable -> {
                val events = reconciliation.stateUpdates + reconciliation.newEventsOldestFirst
                // The offset was measured at the end of this read. It holds for a row only if the pump
                // clock was not set between that row and the read: a date/time change row after it in
                // the pump's own order (sequence) ends its validity. The clock rows in this scan cover
                // everything newer than the cursor; an in-place update (stateUpdates) is older than all.
                // Validated ring order, not raw sequence values: counters can wrap between rows.
                val lastClockChangeIndex = snapshot.rowsNewestFirst.indexOfFirst {
                    YpsoHistoryClassifier.classify(it).kind in CLOCK_CHANGES
                }.takeIf { it >= 0 }
                val waitForClock = clocklessScans.get() < CLOCK_WAIT_SCANS
                fun offsetFor(event: YpsoHistoryEvent): Long? = snapshot.pumpClockOffsetMs?.takeIf {
                    lastClockChangeIndex == null ||
                        snapshot.rowsNewestFirst.indexOfFirst { it.sequence == event.identity.sequence } in 0..lastClockChangeIndex &&
                        event !in reconciliation.stateUpdates
                }
                // Include rows already passed by the cursor. An outstanding attempt can still fit
                // an earlier independent row; checking only this scan's new events hides that choice.
                val head = reconciliation.cursor.identity
                if (!snapshot.fullCoverage && tbrAccounting?.hasUnprovenStarts(pumpSerial) == true) {
                    store.commit(store.load().copy(basalAttributionBlockedSerial = pumpSerial))
                    return YpsoHistoryIngestionResult.Blocked("temporary basal attribution needs complete history")
                }
                val replayRows = snapshot.rowsNewestFirst.mapNotNull { row ->
                    val generation = head.sequenceGeneration - if (row.sequence > head.sequence) 1 else 0
                    if (generation < 0) return@mapNotNull null
                    events.firstOrNull { it.identity.sequence == row.sequence }
                        ?: YpsoHistoryEvent(
                            YpsoEventIdentity(pumpSerial, generation, row.sequence), row
                        )
                }
                tbrAccounting?.attributionBlock(replayRows, pumpSerial, zone, ::offsetFor)?.let { reason ->
                    store.commit(store.load().copy(basalAttributionBlockedSerial = pumpSerial))
                    return YpsoHistoryIngestionResult.Blocked(reason)
                }
                // Disappearance of a conflicting row, a new clock reading or a status sample is
                // not identity evidence. An existing block clears only after the journal proves
                // the outstanding identities and their accounting is successfully replayed.
                if (basalAccountingBlocked() && tbrAccounting?.hasUnprovenStarts(pumpSerial) != false) {
                    return YpsoHistoryIngestionResult.Blocked("temporary basal identities still require reconciliation")
                }
                for (event in events) {
                    val offset = offsetFor(event)
                    when (event.semantics.kind) {
                        // Only immediate-bolus terminal rows are ingested as an instantaneous normal
                        // bolus. Square/combination terminal rows carry the pump-confirmed delivered
                        // total, but mapping them as one instantaneous normal bolus would distort
                        // their delivery-time distribution and IOB; they stay unaccounted until an
                        // evidence-backed extended-bolus reporting contract exists. Origin is never
                        // inferred from amount, recency or receipt time.
                        YpsoHistoryKind.IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED -> {
                            val resolved = YpsoPumpLocalTime.resolve(event.entry.factorySeconds, zone) as? YpsoPumpLocalTime.Resolution.Resolved
                                ?: return YpsoHistoryIngestionResult.Blocked("bolus timestamp is ambiguous")
                            val timestamp = resolved.instant.toEpochMilli()
                            // Apply the activation policy before creating a durable replay intent. Replay
                            // deliberately bypasses that cutoff so already-owned insulin survives a switch.
                            if (pumpSync.isHistoryRecordBeforeActivePump(timestamp, PumpType.YPSOPUMP, pumpSerial) &&
                                !hasRecordedBolus(pumpSerial, event.identity.aapsPumpId)) continue
                            val amount = requireNotNull(event.semantics.amountUnits)
                            val amountCentiUnits = Math.round(amount * 100).toInt()
                            val pending = YpsoPendingBolusSync(
                                pumpSerial,
                                event.identity.aapsPumpId,
                                timestamp,
                                amountCentiUnits,
                                event.identity.sequence,
                                bolusType(event),
                            )
                            store.commit(store.load().copy(pendingBolus = pending))
                            if (!retryPending(pumpSerial)) return YpsoHistoryIngestionResult.Blocked("PumpSync rejected bolus")
                        }
                        YpsoHistoryKind.DELAYED_BOLUS_COMPLETED,
                        YpsoHistoryKind.COMBINED_BOLUS_COMPLETED -> {
                            // Correct an existing extended record under its proven pump identity.
                            // Type 3 reports elapsed minutes, including zero for an initial pulse.
                            val id = event.identity.aapsPumpId
                            val existing = pumpSync.getExtendedBolusWithPumpId(id, PumpType.YPSOPUMP, pumpSerial)
                            val amount = requireNotNull(event.semantics.amountUnits)
                            val registered = registeredAt(pumpSerial)
                            val rowStart = (YpsoPumpLocalTime.resolve(event.entry.factorySeconds, zone) as? YpsoPumpLocalTime.Resolution.Resolved)
                                ?.instant?.toEpochMilli()
                            // A record this path cut at a registration (below) is already this row's
                            // part after it. It is recognized by its own values, not by the current
                            // registration, which a later pump re-registration moves.
                            val cutEarlier = existing != null && existing.isValid && event.entry.eventType == 3 && rowStart != null &&
                                existing.timestamp > rowStart && existing.timestamp < rowStart + (event.entry.value2 + 1L) * 60_000L &&
                                app.aaps.pump.ypsopump.bolus.YpsoExtendedBolusAccounting.squareFrom(existing.timestamp, rowStart, event.entry.value2, amount)
                                    .let { it.amount == existing.amount && it.duration == existing.duration }
                            if (cutEarlier) Unit
                            else if (existing != null && existing.isValid) {
                                val duration = if (event.entry.eventType == 3)
                                    app.aaps.pump.ypsopump.bolus.YpsoExtendedBolusAccounting.squareHistoryDuration(event.entry.value2, existing.duration)
                                else existing.duration
                                pumpSync.correctExtendedBolusWithPumpId(existing.timestamp, amount, duration,
                                    existing.isEmulatingTempBasal, id, PumpType.YPSOPUMP, pumpSerial)
                                val saved = pumpSync.getExtendedBolusWithPumpId(id, PumpType.YPSOPUMP, pumpSerial)
                                if (saved == null || saved.amount != amount || saved.duration != duration) {
                                    return YpsoHistoryIngestionResult.Blocked("PumpSync rejected extended bolus correction")
                                }
                            } else if (existing == null && event.entry.eventType == 3 && amount > 0.0) {
                                // A square bolus started on the pump itself. Its insulin is real and
                                // must reach IOB. Type 3 keeps the start timestamp of the running row
                                // it replaced in place, and value2 is the elapsed whole minutes, so
                                // the delivery window is evidence-backed rather than assumed.
                                // Combination rows stay out of scope: their immediate part is not
                                // separable here without risking double accounting.
                                val start = rowStart ?: return YpsoHistoryIngestionResult.Blocked("extended bolus timestamp is ambiguous")
                                // PumpSync intentionally excludes history predating activation. A
                                // terminal row wholly in that excluded period is not a retryable DB
                                // failure: retrying it pins the cursor and rescans the same ring forever.
                                // Elapsed minutes are rounded down; use the NEXT minute as the upper
                                // bound and never skip a delivery that could overlap activation. Existing
                                // records take the correction path above, regardless of their age.
                                val endUpperBound = start + (event.entry.value2 + 1L) * 60_000L
                                if (pumpSync.isHistoryRecordBeforeActivePump(endUpperBound, PumpType.YPSOPUMP, pumpSerial) || endUpperBound <= registered) continue
                                // One running at registration is recorded from then on, with its share of the amount.
                                val part = app.aaps.pump.ypsopump.bolus.YpsoExtendedBolusAccounting.squareFrom(registered, start, event.entry.value2, amount)
                                pumpSync.syncExtendedBolusWithPumpId(part.start, part.amount, part.duration, false, id, PumpType.YPSOPUMP, pumpSerial)
                                val saved = pumpSync.getExtendedBolusWithPumpId(id, PumpType.YPSOPUMP, pumpSerial)
                                if (saved == null || saved.amount != part.amount || saved.duration != part.duration) {
                                    return YpsoHistoryIngestionResult.Blocked("PumpSync rejected pump-started extended bolus")
                                }
                            }
                        }
                        YpsoHistoryKind.BASAL_PROFILE_CHANGED,
                        YpsoHistoryKind.BASAL_PROFILE_A_CHANGED,
                        YpsoHistoryKind.BASAL_PROFILE_B_CHANGED -> Unit
                        else -> tbrAccounting?.apply(event, pumpSerial, zone, offset, waitForClock)?.let {
                            val clockWait = app.aaps.pump.ypsopump.tbr.YpsoTbrHistoryAccounting.isClockWait(it)
                            if (clockWait) clocklessScans.incrementAndGet()
                            return YpsoHistoryIngestionResult.Blocked(it, retry = if (clockWait) YpsoHistoryIngestionResult.Retry.BOUNDED else YpsoHistoryIngestionResult.Retry.NONE)
                        }
                    }
                }
                clocklessScans.set(0)
                store.commit(store.load().copy(cursor = reconciliation.cursor, pendingBolus = null, basalAttributionBlockedSerial = null))
                return YpsoHistoryIngestionResult.Applied(reconciliation.cursor)
            }
        }
    }

    companion object {
        private val CLOCK_CHANGES = setOf(YpsoHistoryKind.DATE_CHANGED, YpsoHistoryKind.TIME_CHANGED)
        /** Scans a TBR row waits for a pump clock reading before its AAPS start is left unmatched. */
        private const val CLOCK_WAIT_SCANS = 3
    }
}
