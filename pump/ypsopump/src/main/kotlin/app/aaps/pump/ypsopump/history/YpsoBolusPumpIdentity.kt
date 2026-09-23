package app.aaps.pump.ypsopump.history

/**
 * One checked construction of the stable AAPS pump identity for a bolus that is known only by its
 * pump block sequence.
 *
 * The pump numbers history rows with a 32-bit sequence that wraps, so AAPS packs a generation
 * counter above it. Creation, cancellation, reconciliation and later repair must derive exactly the
 * same identity for one physical dose, otherwise the same insulin is recorded twice.
 */
object YpsoBolusPumpIdentity {

    /**
     * Identity for [sequence] relative to the history cursor captured before dispatch, or null when
     * the generation would overflow the packed representation.
     */
    fun of(baselinePumpId: Long, sequence: Long): Long? {
        require(sequence in 0..0xffffffffL) { "bolus sequence is outside the pump's 32-bit range" }
        require(baselinePumpId >= 0) { "baseline pump identity must be positive" }
        var generation = baselinePumpId ushr 32
        // A sequence below the baseline can only be this dose if the pump's counter wrapped.
        if (sequence < (baselinePumpId and 0xffffffffL)) generation++
        if (generation > Int.MAX_VALUE) return null
        return (generation shl 32) or sequence
    }

    /**
     * Whether [candidate] is a later pump sequence than [baseline] across the 32-bit wrap.
     *
     * Delivery identity must only ever move forward. The pump shares this counter with its history
     * rows, so consecutive boluses are not consecutive numbers and only the ordering is meaningful.
     */
    fun isStrictlyNewer(candidate: Long, baseline: Long): Boolean {
        require(candidate in 0..0xffffffffL && baseline in 0..0xffffffffL)
        val delta = (candidate - baseline + 0x1_0000_0000L) % 0x1_0000_0000L
        return delta in 1 until 0x8000_0000L
    }
}
