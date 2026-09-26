package app.aaps.ui.activities.history

import androidx.compose.runtime.Immutable

enum class HistoryKind { BOLUS, SMB, CARBS, TBR, EVENT }

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
    val value: String
) {

    /** Unique across kinds — two kinds can share an id, since each table numbers its own rows. */
    val key: String get() = kind.name + id

    /** Basal history affects insulin accounting and is display-only here. */
    val removable: Boolean get() = kind != HistoryKind.TBR
}

@Immutable
data class HistoryUiState(
    val loading: Boolean = true,
    val items: List<HistoryItem> = emptyList(),
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet()   // HistoryItem.key
)

fun HistoryFilter.matches(kind: HistoryKind): Boolean = when (this) {
    HistoryFilter.ALL    -> true
    HistoryFilter.BOLUS  -> kind == HistoryKind.BOLUS || kind == HistoryKind.SMB
    HistoryFilter.CARBS  -> kind == HistoryKind.CARBS
    HistoryFilter.TBR    -> kind == HistoryKind.TBR
    HistoryFilter.EVENTS -> kind == HistoryKind.EVENT
}
