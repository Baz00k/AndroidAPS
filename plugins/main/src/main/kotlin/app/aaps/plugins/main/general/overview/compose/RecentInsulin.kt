package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.EB

/**
 * Entries for the IOB-tap undo sheet, newest first: [boluses] already queried from the window start (primes
 * excluded, they never enter IOB) and extended boluses whose delivery overlaps the window. An extended bolus still running at [now] is listed
 * but not removable.
 */
internal fun recentInsulinEntries(
    boluses: List<BS>,
    extendedBoluses: List<EB>,
    windowStart: Long,
    now: Long,
    limit: Int,
    timeString: (Long) -> String,
    unitsString: (Double) -> String
): List<HomeUiState.InsulinEntry> {
    val bolusEntries = boluses
        .filter { it.isValid && it.amount > 0 && it.type != BS.Type.PRIMING }
        .map { b ->
            HomeUiState.InsulinEntry(
                id = b.id,
                time = timeString(b.timestamp),
                units = unitsString(b.amount),
                kind = if (b.type == BS.Type.SMB) "SMB" else "",
                timestamp = b.timestamp,
                amount = b.amount
            )
        }
    val extendedEntries = extendedBoluses
        .filter { it.isValid && it.amount > 0 && it.timestamp <= now && it.end > windowStart }
        .map { e ->
            val running = e.end > now
            HomeUiState.InsulinEntry(
                id = e.id,
                time = timeString(e.timestamp),
                units = unitsString(e.amount),
                kind = "Extended · ${e.duration / 60_000} min" + if (running) " · running" else "",
                timestamp = e.timestamp,
                amount = e.amount,
                type = HomeUiState.InsulinType.EXTENDED,
                durationMs = e.duration,
                removable = !running
            )
        }
    return (bolusEntries + extendedEntries).sortedByDescending { it.timestamp }.take(limit)
}
