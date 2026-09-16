package app.aaps.ypso.writebench

import app.aaps.pump.ypsopump.history.YpsoHistoryEntry

/**
 * Assessment of two consecutive event-value reads. The target advances a persistent selector after
 * every value read, so these rows can be protected observations but can never prove an unchanged
 * logical head without a separately reconciled selector repositioning operation.
 */
internal data class BenchCurrentHistoryObservation(
    val first: YpsoHistoryEntry?,
    val second: YpsoHistoryEntry?,
    val countStable: Boolean,
    val rebootStable: Boolean,
) {
    val startedAtLogicalHead: Boolean = first?.index == 0
    val stableHeadCursor: Boolean = false
    val disposition: String = "ADVANCING_SELECTOR_OBSERVATION_ONLY"
}

internal object BenchCurrentHistoryObservationDecoder {
    fun assess(
        firstBody: ByteArray,
        secondBody: ByteArray,
        countBefore: Int?,
        countAfter: Int,
        rebootBefore: Int?,
        rebootAfter: Int?,
    ) = BenchCurrentHistoryObservation(
        first = YpsoHistoryEntry.decodeWire(firstBody),
        second = YpsoHistoryEntry.decodeWire(secondBody),
        countStable = countBefore != null && countBefore == countAfter,
        rebootStable = rebootBefore != null && rebootBefore == rebootAfter,
    )
}
