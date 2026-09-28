package app.aaps.ui.activities.history

import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single

internal data class HistoryRemovalResult(
    val removed: List<HistoryItem>,
    val failures: List<Pair<HistoryItem, Throwable>>
) {
    fun applyTo(state: HistoryUiState): HistoryUiState {
        val removedKeys = removed.map { it.key }.toSet()
        val failedKeys = failures.map { it.first.key }.toSet()
        return state.copy(
            items = state.items.filterNot { it.key in removedKeys },
            removing = false,
            selecting = failedKeys.isNotEmpty(),
            selected = failedKeys
        )
    }
}

/** Capture the selection before displaying the dialog. Cancellation never invokes [onConfirmed]. */
internal fun confirmHistoryRemoval(
    items: List<HistoryItem>,
    showConfirmation: (String, Runnable) -> Unit,
    onConfirmed: (List<HistoryItem>) -> Unit
) {
    if (items.isEmpty()) return
    val selection = items.toList()
    val summary = selection.sortedByDescending { it.timestamp }.joinToString("\n") {
        it.time + "   " + it.title + (if (it.value.isNotBlank()) "   " + it.value else "")
    }
    val warning = buildString {
        if (selection.any { it.kind != HistoryKind.EVENT })
            append("\n\nThis invalidates the history records and changes calculated IOB/COB. The loop will recalculate from the corrected history.")
        if (selection.any { it.kind == HistoryKind.TBR })
            append("\n\nRemoving a temporary basal can increase or decrease calculated IOB. It does not cancel a temporary basal on the pump.")
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
                    HistoryKind.CARBS ->
                        persistence.invalidateCarbs(item.id, Action.CARBS_REMOVED, Sources.Treatments, null, item.auditValues).ignoreElement()
                    HistoryKind.TBR ->
                        persistence.invalidateTemporaryBasal(item.id, Action.TEMP_BASAL_REMOVED, Sources.Treatments, null, item.auditValues).ignoreElement()
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
