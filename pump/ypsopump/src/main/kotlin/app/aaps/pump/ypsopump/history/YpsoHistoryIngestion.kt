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
) {
    fun currentCursor(): YpsoHistoryCursor? = store.load().cursor

    fun retryPending(pumpSerial: String): Boolean {
        val state = store.load()
        val pending = state.pendingBolus ?: return true
        if (pending.pumpSerial != pumpSerial) return false
        val result = pumpSync.syncBolusWithPumpIdDetailed(
            pending.timestamp,
            pending.amountCentiUnits / 100.0,
            BS.Type.NORMAL,
            pending.pumpId,
            PumpType.YPSOPUMP,
            pending.pumpSerial,
        )
        if (result == PumpSync.BolusSyncResult.REJECTED) return false
        store.commit(state.copy(pendingBolus = null))
        return true
    }

    fun ingest(pumpSerial: String, zone: ZoneId, reboot: Long, snapshot: YpsoHistorySnapshot): YpsoHistoryIngestionResult {
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
            is YpsoHistoryReconciliation.Gap -> return YpsoHistoryIngestionResult.Blocked("history gap: ${reconciliation.reason}")
            is YpsoHistoryReconciliation.Stable -> {
                for (event in reconciliation.newEventsOldestFirst) {
                    when (event.semantics.kind) {
                        // Terminal bolus rows were paired against pump-reported delivery: type 2 and
                        // type 3 carry the delivered amount (including a cancelled partial), and type
                        // 18 value1 carries the delivered amount observed at cancellation. All are
                        // pump-confirmed insulin regardless of who started the bolus; origin is never
                        // inferred from amount, recency or receipt time.
                        YpsoHistoryKind.IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED,
                        YpsoHistoryKind.DELAYED_BOLUS_COMPLETED,
                        YpsoHistoryKind.COMBINED_BOLUS_COMPLETED -> {
                            val resolved = YpsoPumpLocalTime.resolve(event.entry.factorySeconds, zone) as? YpsoPumpLocalTime.Resolution.Resolved
                                ?: return YpsoHistoryIngestionResult.Blocked("bolus timestamp is ambiguous")
                            val amount = requireNotNull(event.semantics.amountUnits)
                            val pending = YpsoPendingBolusSync(
                                pumpSerial,
                                event.identity.aapsPumpId,
                                resolved.instant.toEpochMilli(),
                                Math.round(amount * 100).toInt(),
                                event.identity.sequence,
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
