package app.aaps.pump.ypsopump.history

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.ypsopump.bolus.YpsoBolusMessage

sealed interface YpsoHistoryIngestionResult {
    data class Applied(val cursor: YpsoHistoryCursor?) : YpsoHistoryIngestionResult
    /** NONE means the failure needs immediate attention; TRANSIENT ones warn only when they repeat. */
    enum class Retry { NONE, TRANSIENT }
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

    /** Records of AAPS starts pump history decides for; take them before reading history for [ingest]. */
    fun provisionalTbrs(pumpSerial: String, now: Long) = tbrAccounting?.provisional(pumpSerial, now).orEmpty()

    /** See [app.aaps.pump.ypsopump.tbr.YpsoTbrHistoryAccounting.keepStandIns]. */
    fun keepTbrStandIns(): String? = tbrAccounting?.keepStandIns()

    /** The cursor as doses are sent against it: on the pump reboot a stable read last proved continuous. */
    fun commandCursor(): YpsoHistoryCursor? = store.load().let { state -> state.cursor?.let { cursor -> state.provenReboot?.let { cursor.copy(pumpReboot = it) } ?: cursor } }

    /** Cheap local gate for a new dose. Never performs pump I/O. */
    fun bolusReadiness(pumpSerial: String, reboot: Long): YpsoBolusMessage? {
        if (!retryPending(pumpSerial)) return YpsoBolusMessage.SAVING_PREVIOUS_DOSE
        val cursor = commandCursor() ?: return YpsoBolusMessage.SYNC_IN_PROGRESS
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
        reboot: Long,
        snapshot: YpsoHistorySnapshot,
        /** [provisionalTbrs] taken before [snapshot] was read. */
        provisional: List<app.aaps.pump.ypsopump.tbr.YpsoTbrRecordLookup.Provisional> = emptyList(),
        bolusType: (YpsoHistoryEvent) -> BS.Type = { BS.Type.NORMAL },
    ): YpsoHistoryIngestionResult {
        if (!retryPending(pumpSerial)) return YpsoHistoryIngestionResult.Blocked("pending PumpSync record was rejected")
        if (snapshot.pumpRebootBefore != reboot || snapshot.pumpRebootAfter != reboot) {
            return YpsoHistoryIngestionResult.Blocked("history snapshot belongs to another pump reboot epoch")
        }
        val state = store.load()
        val cursor = state.cursor?.takeIf { it.identity.pumpSerial == pumpSerial }
        val reconciliation = cursor?.let { YpsoHistoryReconciler.reconcile(it, snapshot) }
            ?: YpsoHistoryReconciler.bootstrap(pumpSerial, 0, snapshot)
        when (reconciliation) {
            is YpsoHistoryReconciliation.Bootstrap -> {
                store.commit(state.copy(cursor = reconciliation.cursor, provenReboot = null))
                return YpsoHistoryIngestionResult.Applied(reconciliation.cursor)
            }
            is YpsoHistoryReconciliation.Moving -> return YpsoHistoryIngestionResult.Blocked("history moved during scan", retry = YpsoHistoryIngestionResult.Retry.TRANSIENT)
            is YpsoHistoryReconciliation.Gap -> {
                val detail = YpsoHistoryReconciler.lastInvalidSnapshotDetail
                    ?.takeIf { reconciliation.reason == YpsoHistoryReconciliation.Reason.INVALID_SNAPSHOT }
                return YpsoHistoryIngestionResult.Blocked(
                    "history gap: ${reconciliation.reason}${detail?.let { " ($it)" } ?: ""}",
                )
            }
            is YpsoHistoryReconciliation.Stable -> {
                val events = reconciliation.stateUpdates + reconciliation.newEventsOldestFirst
                // A stable read proves the cursor's history continues into this reboot, a sequence reset
                // included. It is recorded apart from the cursor, which keeps its reboot so the next read
                // still recognises the reset, so a history problem below never keeps doses refused.
                if ((state.provenReboot ?: reconciliation.previousCursor.pumpReboot) != reboot) store.commit(state.copy(provenReboot = reboot))
                // Every row is placed exactly, or none is: a failed clock read is retried by the next scan, and
                // the cursor never passes an invalid clock change.
                val times = YpsoPumpLocalTime.rowTimes(snapshot)
                val unplaced = events.firstOrNull { it.semantics.kind in TIMED && times.at(it.entry) == null }
                times.invalidChange?.takeIf { change -> unplaced != null || events.any { it.entry.sequence == change.sequence } }?.let {
                    return YpsoHistoryIngestionResult.Blocked("pump clock change row ${it.sequence} has an impossible setting")
                }
                if (unplaced != null) return YpsoHistoryIngestionResult.Blocked(
                    "history row ${unplaced.identity.aapsPumpId} awaits a pump clock reading", retry = YpsoHistoryIngestionResult.Retry.TRANSIENT,
                )
                // Ordered so a pass that stops part-way leaves insulin on board erring high.
                tbrAccounting?.invalidateLowering(pumpSerial, provisional)?.let { return YpsoHistoryIngestionResult.Blocked(it) }
                for (event in events) {
                    val at = times.at(event.entry)
                    when (event.semantics.kind) {
                        // Only immediate-bolus terminal rows are ingested as an instantaneous normal
                        // bolus. Square/combination terminal rows carry the pump-confirmed delivered
                        // total, but mapping them as one instantaneous normal bolus would distort
                        // their delivery-time distribution and IOB; they stay unaccounted until an
                        // evidence-backed extended-bolus reporting contract exists. Origin is never
                        // inferred from amount, recency or receipt time.
                        YpsoHistoryKind.IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED -> {
                            val timestamp = checkNotNull(at)
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
                            val rowStart = at
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
                                val start = checkNotNull(rowStart)
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
                        else -> at?.let { tbrAccounting?.apply(event, pumpSerial, it) }?.let { return YpsoHistoryIngestionResult.Blocked(it) }
                    }
                }
                tbrAccounting?.invalidateRaising(pumpSerial, provisional)?.let { return YpsoHistoryIngestionResult.Blocked(it) }
                store.commit(store.load().copy(cursor = reconciliation.cursor, pendingBolus = null, provenReboot = null))
                return YpsoHistoryIngestionResult.Applied(reconciliation.cursor)
            }
        }
    }

    companion object {
        /** Rows whose time is used: doses, temporary basals and pump stops. */
        private val TIMED = setOf(
            YpsoHistoryKind.IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED, YpsoHistoryKind.DELAYED_BOLUS_COMPLETED,
            YpsoHistoryKind.TEMP_BASAL_STARTED, YpsoHistoryKind.TEMP_BASAL_COMPLETED, YpsoHistoryKind.TEMP_BASAL_CANCELLED,
            YpsoHistoryKind.TEMP_BASAL_TERMINAL_UNRESOLVED, YpsoHistoryKind.PUMP_MODE_CHANGED,
        )
    }
}
