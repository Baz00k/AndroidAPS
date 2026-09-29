package app.aaps.ui.activities.history

import androidx.compose.runtime.Immutable
import app.aaps.core.data.ue.ValueWithUnit

enum class HistoryKind { BOLUS, SMB, EXTENDED, CARBS, TBR, EVENT }

enum class HistoryFilter { ALL, BOLUS, CARBS, TBR, EVENTS }

@Immutable
data class HistoryItem(
    /** Database id of the underlying record — what a removal invalidates. */
    val id: Long,
    val timestamp: Long,
    val dayLabel: String,   // "Today" / "Yesterday" / date — precomputed for grouping
    val time: String,       // "12:30"
    val kind: HistoryKind,
    val title: String,
    val sub: String,
    val value: String,
    /** Original persisted values for the removal audit; never parsed from display text. */
    val auditValues: List<ValueWithUnit> = listOf(ValueWithUnit.Timestamp(timestamp)),
    /**
     * False while the record still describes delivery in progress (a running temporary basal or extended
     * bolus). Such a record cannot be selected; persistence re-checks this when removing.
     */
    val removable: Boolean = true,
    /** End time of a running record, so History can refresh when it stops being one. */
    val runningUntil: Long? = null
) {

    /** Unique across kinds — two kinds can share an id, since each table numbers its own rows. */
    val key: String get() = kind.name + id
}

@Immutable
data class HistoryUiState(
    val loading: Boolean = true,
    val items: List<HistoryItem> = emptyList(),
    val selecting: Boolean = false,
    val removing: Boolean = false,
    val selected: Set<String> = emptySet()   // HistoryItem.key
)

fun HistoryFilter.matches(kind: HistoryKind): Boolean = when (this) {
    HistoryFilter.ALL    -> true
    HistoryFilter.BOLUS  -> kind == HistoryKind.BOLUS || kind == HistoryKind.SMB || kind == HistoryKind.EXTENDED
    HistoryFilter.CARBS  -> kind == HistoryKind.CARBS
    HistoryFilter.TBR    -> kind == HistoryKind.TBR
    HistoryFilter.EVENTS -> kind == HistoryKind.EVENT
}

/** Toggle [item] in the selection. Records that are not removable never enter it. */
internal fun HistoryUiState.toggled(item: HistoryItem): HistoryUiState {
    if (removing || !item.removable) return this
    return copy(selected = if (item.key in selected) selected - item.key else selected + item.key)
}

/** Enter selection mode from a long press, selecting [item] only if it can be removed. */
internal fun HistoryUiState.startedSelecting(item: HistoryItem): HistoryUiState {
    if (removing) return this
    return copy(selecting = true, selected = if (item.removable) selected + item.key else selected)
}

/**
 * Replace the list with freshly loaded [fresh] items. A selection survives only for rows that are still
 * removable, so a refresh can never leave a running record selected.
 */
internal fun HistoryUiState.refreshedWith(fresh: List<HistoryItem>): HistoryUiState =
    copy(loading = false, items = fresh, selected = selected intersect fresh.filter { it.removable }.map { it.key }.toSet())

/** When the earliest running record ends, or null if nothing is running. */
internal fun List<HistoryItem>.nextRunningEnd(): Long? = mapNotNull { it.runningUntil }.minOrNull()

internal fun HistoryUiState.nextRunningEnd(): Long? = items.nextRunningEnd()
