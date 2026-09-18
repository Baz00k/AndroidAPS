package app.aaps.pump.ypsopump.bolus

import app.aaps.pump.ypsopump.comm.commands.BolusCommand

/** Durable command state. Any state from POSSIBLY_APPLIED onward inhibits another automated dose. */
enum class YpsoBolusOutcome {
    NOT_SENT,
    PROVEN_REJECTED,
    POSSIBLY_APPLIED,
    ACCEPTED_UNVERIFIED,
    DELIVERING,
    COMPLETED,
    CANCEL_PENDING,
    CANCELLED_PARTIAL,
    UNRESOLVED,
}

enum class YpsoBolusTreatment { NORMAL, SMB, PRIME }

/** Wire shape of the START_STOP_BOLUS request. */
enum class YpsoBolusShape { IMMEDIATE, EXTENDED, COMBINED }

/** Pump status block that carries a command identity: the fast/immediate or slow/extended block. */
enum class YpsoBolusBlock { FAST, SLOW }

data class YpsoBolusBaseline(
    val fastSequence: Long,
    val slowSequence: Long,
    val historyPumpId: Long,
    val historyFingerprintHigh: Long,
    val historyFingerprintLow: Long,
    val pumpReboot: Int,
    val observedAt: Long,
) {
    init {
        require(fastSequence in 0..0xffffffffL)
        require(slowSequence in 0..0xffffffffL)
        require(pumpReboot >= 0)
        require(observedAt > 0)
    }
}

data class YpsoBolusAttempt(
    val requestId: String,
    val pumpSerial: String,
    val sessionGeneration: String,
    val treatment: YpsoBolusTreatment,
    /** Validated total wire amount. This is not delivered insulin until confirmed by the pump. */
    val requestedCentiUnits: Int,
    val payloadHash: String,
    val baseline: YpsoBolusBaseline,
    val createdAt: Long,
    val shape: YpsoBolusShape = YpsoBolusShape.IMMEDIATE,
    /** Extended duration in minutes; zero only for a standard immediate bolus. */
    val durationMinutes: Int = 0,
    /** Immediate part of a combination bolus; zero for immediate and extended boluses. */
    val immediateCentiUnits: Int = 0,
    val outcome: YpsoBolusOutcome = YpsoBolusOutcome.NOT_SENT,
    val dispatchCounter: Long? = null,
    val dispatchedAt: Long? = null,
    val pumpFastSequence: Long? = null,
    val pumpSlowSequence: Long? = null,
    val pumpHistoryId: Long? = null,
    val confirmedCentiUnits: Int? = null,
    val deliveryTimestamp: Long? = null,
    val cancelRequestId: String? = null,
    val cancelCounter: Long? = null,
    val cancelBlock: YpsoBolusBlock? = null,
    /** Pump-reported delivered amount observed for the cancel target during the cancellation run. */
    val cancelObservedCentiUnits: Int? = null,
    val detail: String? = null,
) {
    init {
        require(requestId.isNotBlank() && pumpSerial.isNotBlank() && sessionGeneration.isNotBlank())
        require(requestedCentiUnits in BolusCommand.MIN_BOLUS_X100..BolusCommand.MAX_BOLUS_X100)
        require(requestedCentiUnits % BolusCommand.BOLUS_STEP_X100 == 0)
        when (shape) {
            YpsoBolusShape.IMMEDIATE -> {
                require(durationMinutes == 0) { "an immediate bolus cannot carry a duration" }
                require(immediateCentiUnits == 0) { "an immediate bolus cannot carry a combination part" }
            }
            YpsoBolusShape.EXTENDED -> {
                require(durationMinutes in 1..BolusCommand.MAX_DURATION_MINUTES)
                require(immediateCentiUnits == 0) { "an extended bolus cannot carry a combination part" }
            }
            YpsoBolusShape.COMBINED -> {
                require(durationMinutes in 1..BolusCommand.MAX_DURATION_MINUTES)
                require(immediateCentiUnits >= BolusCommand.MIN_BOLUS_X100)
                require(immediateCentiUnits % BolusCommand.BOLUS_STEP_X100 == 0)
                require(requestedCentiUnits - immediateCentiUnits >= BolusCommand.MIN_BOLUS_X100)
                require((requestedCentiUnits - immediateCentiUnits) % BolusCommand.BOLUS_STEP_X100 == 0)
            }
        }
        require(payloadHash.matches(Regex("[0-9a-f]{64}")))
        require(createdAt > 0)
        require(dispatchCounter == null || dispatchCounter >= 0)
        require(pumpFastSequence == null || pumpFastSequence in 0..0xffffffffL)
        require(pumpSlowSequence == null || pumpSlowSequence in 0..0xffffffffL)
        require(confirmedCentiUnits == null || confirmedCentiUnits in 0..requestedCentiUnits)
        require((confirmedCentiUnits == null) == (deliveryTimestamp == null))
        require(cancelCounter == null || cancelCounter >= 0)
        require((cancelRequestId == null) == (cancelCounter == null))
        require((cancelRequestId == null) == (cancelBlock == null))
        require(cancelObservedCentiUnits == null || cancelObservedCentiUnits in 0..requestedCentiUnits)
        require(cancelObservedCentiUnits == null || cancelRequestId != null)
        require(detail == null || detail.isNotBlank())
    }

    val requestedUnits: Double get() = requestedCentiUnits / 100.0
    val confirmedUnits: Double? get() = confirmedCentiUnits?.div(100.0)
    val requestedExtendedCentiUnits: Int get() = requestedCentiUnits - immediateCentiUnits

    /** Programmed amount of the block that carries the requested delivery. */
    fun programmedCentiUnits(block: YpsoBolusBlock): Int? =
        when (block) {
            YpsoBolusBlock.FAST -> when (shape) {
                YpsoBolusShape.IMMEDIATE -> requestedCentiUnits
                YpsoBolusShape.COMBINED -> immediateCentiUnits
                YpsoBolusShape.EXTENDED -> null
            }
            // Captured target behavior: the extended block reports the whole programmed total for a
            // square bolus and for a combination bolus (its injected amount includes the immediate part).
            YpsoBolusBlock.SLOW -> when (shape) {
                YpsoBolusShape.IMMEDIATE -> null
                YpsoBolusShape.EXTENDED, YpsoBolusShape.COMBINED -> requestedCentiUnits
            }
        }

    fun provenSequence(block: YpsoBolusBlock): Long? =
        when (block) {
            YpsoBolusBlock.FAST -> pumpFastSequence
            YpsoBolusBlock.SLOW -> pumpSlowSequence
        }

    /**
     * Cancellation target: the slow block whenever it was proven, otherwise the proven fast block.
     * A combination bolus during its immediate phase can only target the fast identity.
     */
    val provenCancelBlock: YpsoBolusBlock?
        get() = when (shape) {
            YpsoBolusShape.IMMEDIATE -> if (pumpFastSequence != null) YpsoBolusBlock.FAST else null
            YpsoBolusShape.EXTENDED -> if (pumpSlowSequence != null) YpsoBolusBlock.SLOW else null
            YpsoBolusShape.COMBINED ->
                if (pumpSlowSequence != null) YpsoBolusBlock.SLOW
                else if (pumpFastSequence != null) YpsoBolusBlock.FAST
                else null
        }

    val inhibitsAutomatedDelivery: Boolean
        get() = outcome in setOf(
            YpsoBolusOutcome.POSSIBLY_APPLIED,
            YpsoBolusOutcome.ACCEPTED_UNVERIFIED,
            YpsoBolusOutcome.DELIVERING,
            YpsoBolusOutcome.CANCEL_PENDING,
            YpsoBolusOutcome.UNRESOLVED,
        )
}

interface YpsoBolusAttemptStore {
    fun load(): YpsoBolusAttempt?
    /** Must durably commit before returning; failure throws. */
    fun commit(attempt: YpsoBolusAttempt)
}

/**
 * Persist-first bolus lifecycle independent of BLE callbacks and PumpSync. The caller supplies only
 * pump-confirmed observations; this class never turns the requested amount or an ACK into insulin.
 */
class YpsoBolusAttemptJournal(private val store: YpsoBolusAttemptStore) {

    fun current(): YpsoBolusAttempt? = store.load()

    fun prepare(attempt: YpsoBolusAttempt): YpsoBolusAttempt {
        require(attempt.outcome == YpsoBolusOutcome.NOT_SENT && attempt.dispatchCounter == null)
        val existing = store.load()
        require(existing == null || !existing.inhibitsAutomatedDelivery) { "an earlier bolus remains unresolved" }
        store.commit(attempt)
        return attempt
    }

    fun beforeDispatch(requestId: String, counter: Long, now: Long): YpsoBolusAttempt =
        update(requestId) {
            require(it.outcome == YpsoBolusOutcome.NOT_SENT && it.dispatchCounter == null)
            it.copy(outcome = YpsoBolusOutcome.POSSIBLY_APPLIED, dispatchCounter = counter, dispatchedAt = now)
        }

    fun transportAccepted(requestId: String): YpsoBolusAttempt =
        update(requestId) {
            require(it.outcome == YpsoBolusOutcome.POSSIBLY_APPLIED)
            it.copy(outcome = YpsoBolusOutcome.ACCEPTED_UNVERIFIED)
        }

    fun provenNotApplied(requestId: String, rejected: Boolean, detail: String): YpsoBolusAttempt =
        update(requestId) {
            require(it.outcome in setOf(YpsoBolusOutcome.NOT_SENT, YpsoBolusOutcome.POSSIBLY_APPLIED))
            it.copy(outcome = if (rejected) YpsoBolusOutcome.PROVEN_REJECTED else YpsoBolusOutcome.NOT_SENT, detail = detail)
        }

    /**
     * Closes an unknown dispatched command only after a stable pump observation proves no effect:
     * unchanged block sequences, idle/zero bolus status, and no history movement since the baseline.
     * The command was dispatched, so retain its dispatch identity and classify it as proven rejected.
     */
    fun reconcileNoEffect(requestId: String, detail: String): YpsoBolusAttempt =
        update(requestId) {
            require(it.outcome in setOf(YpsoBolusOutcome.POSSIBLY_APPLIED, YpsoBolusOutcome.ACCEPTED_UNVERIFIED, YpsoBolusOutcome.UNRESOLVED))
            require(it.dispatchCounter != null && it.dispatchedAt != null)
            require(it.pumpFastSequence == null && it.pumpSlowSequence == null && it.confirmedCentiUnits == null)
            it.copy(outcome = YpsoBolusOutcome.PROVEN_REJECTED, detail = detail)
        }

    /** Binds a changed fast-block identity and its exact programmed amount. */
    fun observeFastDelivering(requestId: String, fastSequence: Long, programmedCentiUnits: Int): YpsoBolusAttempt =
        update(requestId) {
            require(it.outcome in setOf(YpsoBolusOutcome.POSSIBLY_APPLIED, YpsoBolusOutcome.ACCEPTED_UNVERIFIED, YpsoBolusOutcome.DELIVERING))
            val expected = requireNotNull(it.programmedCentiUnits(YpsoBolusBlock.FAST)) { "attempt has no fast delivery block" }
            require(isStrictlyNewerUnsigned(fastSequence, it.baseline.fastSequence)) { "stale or invalid bolus status sequence" }
            require(programmedCentiUnits == expected) { "pump programmed amount differs from request" }
            require(it.pumpFastSequence == null || it.pumpFastSequence == fastSequence) { "bolus status identity changed" }
            it.copy(outcome = YpsoBolusOutcome.DELIVERING, pumpFastSequence = fastSequence)
        }

    /** Binds a changed slow/extended-block identity and its exact programmed amount. */
    fun observeSlowDelivering(requestId: String, slowSequence: Long, programmedCentiUnits: Int): YpsoBolusAttempt =
        update(requestId) {
            require(it.outcome in setOf(YpsoBolusOutcome.POSSIBLY_APPLIED, YpsoBolusOutcome.ACCEPTED_UNVERIFIED, YpsoBolusOutcome.DELIVERING))
            val expected = requireNotNull(it.programmedCentiUnits(YpsoBolusBlock.SLOW)) { "attempt has no slow delivery block" }
            require(isStrictlyNewerUnsigned(slowSequence, it.baseline.slowSequence)) { "stale or invalid extended bolus status sequence" }
            require(programmedCentiUnits == expected) { "pump programmed extended amount differs from request" }
            require(it.pumpSlowSequence == null || it.pumpSlowSequence == slowSequence) { "extended bolus status identity changed" }
            it.copy(outcome = YpsoBolusOutcome.DELIVERING, pumpSlowSequence = slowSequence)
        }

    fun requestCancel(requestId: String, cancelRequestId: String, counter: Long, block: YpsoBolusBlock): YpsoBolusAttempt =
        update(requestId) {
            require(it.outcome in setOf(YpsoBolusOutcome.POSSIBLY_APPLIED, YpsoBolusOutcome.ACCEPTED_UNVERIFIED, YpsoBolusOutcome.DELIVERING))
            require(it.cancelRequestId == null) { "cancellation is already owned" }
            requireNotNull(it.programmedCentiUnits(block)) { "attempt has no such delivery block" }
            require(it.provenSequence(block) != null) { "cancellation target identity was never proven" }
            require(it.provenCancelBlock == block) { "attempt prefers a different cancellation target" }
            it.copy(
                outcome = YpsoBolusOutcome.CANCEL_PENDING,
                cancelRequestId = cancelRequestId,
                cancelCounter = counter,
                cancelBlock = block,
            )
        }

    /** Records pump-reported delivered amount for the cancel target while cancellation is in flight. */
    fun observeCancelDelivery(requestId: String, deliveredCentiUnits: Int): YpsoBolusAttempt =
        update(requestId) {
            require(it.outcome == YpsoBolusOutcome.CANCEL_PENDING && it.cancelBlock != null)
            require(deliveredCentiUnits in 0..it.requestedCentiUnits)
            it.copy(cancelObservedCentiUnits = maxOf(it.cancelObservedCentiUnits ?: 0, deliveredCentiUnits))
        }

    fun cancelNotSent(requestId: String, detail: String): YpsoBolusAttempt =
        update(requestId) {
            require(it.outcome == YpsoBolusOutcome.CANCEL_PENDING && it.cancelRequestId != null)
            it.copy(
                outcome = if (it.pumpFastSequence != null || it.pumpSlowSequence != null) {
                    YpsoBolusOutcome.DELIVERING
                } else {
                    YpsoBolusOutcome.ACCEPTED_UNVERIFIED
                },
                cancelRequestId = null,
                cancelCounter = null,
                cancelBlock = null,
                cancelObservedCentiUnits = null,
                detail = detail,
            )
        }

    fun confirmTerminal(
        requestId: String,
        deliveredCentiUnits: Int,
        timestamp: Long,
        block: YpsoBolusBlock,
        sequence: Long,
        historyPumpId: Long,
        cancelled: Boolean,
    ): YpsoBolusAttempt = update(requestId) {
        require(it.outcome !in setOf(YpsoBolusOutcome.NOT_SENT, YpsoBolusOutcome.PROVEN_REJECTED))
        require(deliveredCentiUnits in 0..it.requestedCentiUnits)
        val identity = requireNotNull(it.provenSequence(block)) { "terminal attribution requires a previously proven bolus status identity" }
        require(identity == sequence) { "terminal identity does not match the proven block sequence" }
        val baselineSequence = when (block) {
            YpsoBolusBlock.FAST -> it.baseline.fastSequence
            YpsoBolusBlock.SLOW -> it.baseline.slowSequence
        }
        require(isStrictlyNewerUnsigned(sequence, baselineSequence))
        require(historyPumpId > it.baseline.historyPumpId) { "terminal history is not newer than the attempt baseline" }
        val complete = !cancelled && deliveredCentiUnits == it.requestedCentiUnits
        it.copy(
            outcome = if (complete) YpsoBolusOutcome.COMPLETED else YpsoBolusOutcome.CANCELLED_PARTIAL,
            pumpFastSequence = if (block == YpsoBolusBlock.FAST) sequence else it.pumpFastSequence,
            pumpSlowSequence = if (block == YpsoBolusBlock.SLOW) sequence else it.pumpSlowSequence,
            pumpHistoryId = historyPumpId,
            confirmedCentiUnits = deliveredCentiUnits,
            deliveryTimestamp = timestamp,
            detail = null,
        )
    }

    fun unresolved(requestId: String, detail: String): YpsoBolusAttempt =
        update(requestId) { it.copy(outcome = YpsoBolusOutcome.UNRESOLVED, detail = detail) }

    private fun update(requestId: String, transform: (YpsoBolusAttempt) -> YpsoBolusAttempt): YpsoBolusAttempt {
        val current = requireNotNull(store.load()) { "bolus attempt is missing" }
        require(current.requestId == requestId) { "bolus request identity mismatch" }
        val next = transform(current)
        store.commit(next)
        return next
    }

    private fun isStrictlyNewerUnsigned(candidate: Long, baseline: Long): Boolean {
        require(candidate in 0..0xffffffffL && baseline in 0..0xffffffffL)
        val delta = (candidate - baseline + 0x1_0000_0000L) % 0x1_0000_0000L
        return delta in 1 until 0x8000_0000L
    }
}
