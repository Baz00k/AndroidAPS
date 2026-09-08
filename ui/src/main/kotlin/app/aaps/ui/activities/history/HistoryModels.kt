package app.aaps.ui.activities.history

import androidx.compose.runtime.Immutable

enum class HistoryKind { BOLUS, SMB, CARBS, EVENT }

enum class HistoryFilter { ALL, BOLUS, CARBS, EVENTS }

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
    val value: String
) {

    /** Unique across kinds — two kinds can share an id, since each table numbers its own rows. */
    val key: String get() = kind.name + id
}

@Immutable
data class HistoryUiState(
    val loading: Boolean = true,
    val items: List<HistoryItem> = emptyList(),
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet()   // HistoryItem.key
)
