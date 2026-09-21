package app.aaps.pump.ypsopump.history

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpSync
import java.time.ZoneId

sealed interface YpsoHistoryIngestionResult {
    data class Applied(val cursor: YpsoHistoryCursor?) : YpsoHistoryIngestionResult
    data class Blocked(val reason: String) : YpsoHistoryIngestionResult
}

/** Persist-before-DB history ingestion. Cursor acknowledgement follows successful idempotent DB sync. */
class YpsoHistoryIngestion(
    private val store: YpsoHistoryStateStore,
    private val pumpSync: PumpSync,
    /**
     * Resolves a provisional record created for a dispatched dose onto its pump identity. Without this
     * the terminal history row would insert a second record for the same physical bolus, because
     * PumpSync matches provisional records by temporary id and terminal rows by pump id.
     */
    private val resolveProvisional: (pumpSerial: String, pumpId: Long, timestamp: Long, amount: Double, type: BS.Type) -> Unit = { _, _, _, _, _ -> },
) {
    fun currentCursor(): YpsoHistoryCursor? = store.load().cursor
    /** Cheap local gate for a new dose. Never performs pump I/O. */
    fun bolusReadiness(pumpSerial: String, reboot: Long): String? {
        if (!retryPending(pumpSerial)) return "AAPS is still saving a previous dose. Please try again shortly."
        val cursor = store.load().cursor ?: return "AAPS is still syncing with the pump. Please try again shortly."
        if (cursor.identity.pumpSerial != pumpSerial || cursor.pumpReboot != reboot) {
            return "The pump was restarted. AAPS needs to sync before the next bolus."
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
        val reconciliation = cursor?.let { YpsoHistoryReconciler.reconcile(it, snapshot) }
            ?: YpsoHistoryReconciler.bootstrap(pumpSerial, 0, snapshot)
        when (reconciliation) {
            is YpsoHistoryReconciliation.Bootstrap -> {
                store.commit(state.copy(cursor = reconciliation.cursor))
                return YpsoHistoryIngestionResult.Applied(reconciliation.cursor)
            }
            is YpsoHistoryReconciliation.Moving -> return YpsoHistoryIngestionResult.Blocked("history moved during scan")
            is YpsoHistoryReconciliation.Gap -> {
                val detail = YpsoHistoryReconciler.lastInvalidSnapshotDetail
                    ?.takeIf { reconciliation.reason == YpsoHistoryReconciliation.Reason.INVALID_SNAPSHOT }
                return YpsoHistoryIngestionResult.Blocked(
                    "history gap: ${reconciliation.reason}${detail?.let { " ($it)" } ?: ""}",
                )
            }
            is YpsoHistoryReconciliation.Stable -> {
                for (event in reconciliation.newEventsOldestFirst) {
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
                            val amount = requireNotNull(event.semantics.amountUnits)
                            val amountCentiUnits = Math.round(amount * 100).toInt()
                            if (amountCentiUnits == 0) continue
                            val pending = YpsoPendingBolusSync(
                                pumpSerial,
                                event.identity.aapsPumpId,
                                resolved.instant.toEpochMilli(),
                                amountCentiUnits,
                                event.identity.sequence,
                                bolusType(event),
                            )
                            store.commit(store.load().copy(pendingBolus = pending))
                            if (!retryPending(pumpSerial)) return YpsoHistoryIngestionResult.Blocked("PumpSync rejected bolus")
                        }
                        YpsoHistoryKind.BASAL_PROFILE_CHANGED,
                        YpsoHistoryKind.BASAL_PROFILE_A_CHANGED,
                        YpsoHistoryKind.BASAL_PROFILE_B_CHANGED -> Unit
                        else -> Unit
                    }
                }
                store.commit(store.load().copy(cursor = reconciliation.cursor, pendingBolus = null))
                return YpsoHistoryIngestionResult.Applied(reconciliation.cursor)
            }
        }
    }
}
