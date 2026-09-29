package app.aaps.ui.activities.history

import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single

internal data class HistoryRemovalResult(
    val removed: List<HistoryItem>,
    val failures: List<Pair<HistoryItem, Throwable>>
) {
    /** Removed rows disappear; rows refused as still running become unselectable; other failures stay selected for retry. */
    fun applyTo(state: HistoryUiState): HistoryUiState {
        val removedKeys = removed.map { it.key }.toSet()
        val runningKeys = failures.filter { it.second is StillRunningException }.map { it.first.key }.toSet()
        val failedKeys = failures.map { it.first.key }.toSet() - runningKeys
        return state.copy(
            items = state.items
                .filterNot { it.key in removedKeys }
                .map { if (it.key in runningKeys) it.copy(removable = false) else it },
            removing = false,
            selecting = failedKeys.isNotEmpty(),
            selected = failedKeys
        )
    }

    fun failureMessage(): String {
        val running = failures.filter { it.second is StillRunningException }
        val other = failures.size - running.size
        return buildString {
            append("Could not remove ${failures.size} record(s). Successfully removed records are no longer shown.")
            if (other > 0) append(" Failed records remain selected; you can retry.")
            if (running.isNotEmpty()) {
                append("\n\nStill running, cancel before removing:")
                running.forEach { append("\n" + it.first.time + "   " + it.first.title) }
            }
        }
    }
}

/** Persistence refused the removal because the record was still running when the transaction ran. */
internal class StillRunningException(item: HistoryItem) :
    IllegalStateException("${item.title} ${item.key} is still running")

/** Capture the selection before displaying the dialog. Cancellation never invokes [onConfirmed]. */
internal fun confirmHistoryRemoval(
    items: List<HistoryItem>,
    showConfirmation: (String, Runnable) -> Unit,
    onConfirmed: (List<HistoryItem>) -> Unit
) {
    val selection = items.filter { it.removable }
    if (selection.isEmpty()) return
    val summary = selection.sortedByDescending { it.timestamp }.joinToString("\n") {
        it.time + "   " + it.title + (if (it.value.isNotBlank()) "   " + it.value else "")
    }
    val warning = buildString {
        if (selection.any { it.kind != HistoryKind.EVENT })
            append("\n\nThis invalidates the history records and changes calculated IOB/COB. The loop will recalculate from the corrected history.")
        if (selection.any { it.kind == HistoryKind.TBR })
            append("\n\nRemoving a temporary basal can increase or decrease calculated IOB. It does not change anything on the pump.")
        if (selection.any { it.kind == HistoryKind.EXTENDED })
            append("\n\nRemoving an extended bolus lowers calculated IOB. It does not change anything on the pump.")
    }
    var confirmed = false
    showConfirmation(summary + warning, Runnable {
        if (!confirmed) {
            confirmed = true
            onConfirmed(selection)
        }
    })
}

/** Await each invalidation, collecting failures so a partially successful batch is represented honestly. */
internal fun invalidateHistoryItems(persistence: PersistenceLayer, items: List<HistoryItem>): Single<HistoryRemovalResult> =
    Observable.fromIterable(items)
        .concatMapSingle { item ->
            Single.defer {
                when (item.kind) {
                    HistoryKind.BOLUS, HistoryKind.SMB ->
                        persistence.invalidateBolus(item.id, Action.BOLUS_REMOVED, Sources.Treatments, null, item.auditValues).ignoreElement()
                    HistoryKind.EXTENDED ->
                        persistence.invalidateEndedExtendedBolus(item.id, Action.EXTENDED_BOLUS_REMOVED, Sources.Treatments, null, item.auditValues)
                            .flatMapCompletable { refuseIfRunning(item, it.refusedActive) }
                    HistoryKind.CARBS ->
                        persistence.invalidateCarbs(item.id, Action.CARBS_REMOVED, Sources.Treatments, null, item.auditValues).ignoreElement()
                    HistoryKind.TBR ->
                        persistence.invalidateEndedTemporaryBasal(item.id, Action.TEMP_BASAL_REMOVED, Sources.Treatments, null, item.auditValues)
                            .flatMapCompletable { refuseIfRunning(item, it.refusedActive) }
                    HistoryKind.EVENT ->
                        persistence.invalidateTherapyEvent(item.id, Action.CAREPORTAL_REMOVED, Sources.Treatments, null, item.auditValues).ignoreElement()
                }.toSingleDefault<Pair<HistoryItem, Throwable?>>(item to null)
            }.onErrorReturn { item to it }
        }
        .toList()
        .map { results ->
            HistoryRemovalResult(
                removed = results.filter { it.second == null }.map { it.first },
                failures = results.mapNotNull { (item, error) -> error?.let { item to it } }
            )
        }

private fun refuseIfRunning(item: HistoryItem, refused: List<*>): Completable =
    if (refused.isEmpty()) Completable.complete() else Completable.error(StillRunningException(item))
