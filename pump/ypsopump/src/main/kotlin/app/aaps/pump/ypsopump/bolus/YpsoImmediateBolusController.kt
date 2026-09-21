package app.aaps.pump.ypsopump.bolus

import app.aaps.core.data.model.BS
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoWriteAccounting
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.comm.YpsoBolusNotification
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.history.YpsoHistoryCursor
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Production owner of one bolus from durable intent through same-link block identity proof. */
internal class YpsoImmediateBolusController(
    private val bleManager: YpsoBleManager,
    private val journal: YpsoBolusAttemptJournal,
    private val serialNumber: () -> String,
    private val historyCursor: () -> YpsoHistoryCursor?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class StopResult { RETRY, DISPATCHED_OR_PENDING, NOT_APPLICABLE }

    sealed interface DeliveryResult {
        data class Started(val attempt: YpsoBolusAttempt, val observedDeliveredUnits: Double) : DeliveryResult
        data class NotSent(val detail: String) : DeliveryResult
        data class Uncertain(val detail: String) : DeliveryResult
    }

    private val delivering = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val historyYieldRequested = AtomicBoolean(false)
    private val commandOwner = AtomicReference<YpsoBleManager.BolusCommandOwner?>()

    val isBusy: Boolean get() = delivering.get()
    val cancellationRequested: Boolean get() = stopRequested.get()
    fun consumeHistoryYield(): Boolean = historyYieldRequested.getAndSet(false)
    fun currentAttempt(): YpsoBolusAttempt? = journal.current()
    fun expireStaleAttempt(immediateWindowMs: Long, extendedMarginMs: Long): YpsoBolusAttempt? =
        journal.expireObservationWindow(now(), immediateWindowMs, extendedMarginMs)

    fun beginDelivery(): Boolean {
        if (!delivering.compareAndSet(false, true)) return false
        stopRequested.set(false)
        historyYieldRequested.set(false)
        return true
    }

    fun finishDelivery() {
        commandOwner.set(null)
        delivering.set(false)
    }

    fun confirmTerminal(
        deliveredCentiUnits: Int,
        timestamp: Long,
        sequence: Long,
        historyPumpId: Long,
        cancelled: Boolean,
    ): YpsoBolusAttempt {
        val attempt = requireNotNull(journal.current())
        val block = if (attempt.shape == YpsoBolusShape.IMMEDIATE) YpsoBolusBlock.FAST else YpsoBolusBlock.SLOW
        return journal.confirmTerminal(
            attempt.requestId,
            deliveredCentiUnits,
            timestamp,
            block,
            sequence,
            historyPumpId,
            cancelled,
        )
    }

    fun markUnresolved(detail: String): YpsoBolusAttempt? {
        val attempt = journal.current() ?: return null
        return if (attempt.awaitsReconciliation) journal.unresolved(attempt.requestId, detail) else attempt
    }

    /**
     * The pump announces a terminal transition on CONTROL_NOTIFY about a second after delivery stops,
     * naming the block and its sequence. That is the earliest and most reliable proof that this exact
     * command ended; the delivered amount still comes from history.
     */
    fun observeBolusNotification(notification: YpsoBolusNotification, observedAt: Long = now()): YpsoBolusAttempt? {
        val attempt = journal.current() ?: return null
        if (!attempt.awaitsReconciliation) return null
        val block = attempt.provenCancelBlock ?: return null
        val sequence = attempt.provenSequence(block) ?: return null
        if (!notification.isTerminalFor(block, sequence)) return null
        return journal.observeBlockTerminal(attempt.requestId, observedAt)
    }

    fun observeCancelledStatus(status: BolusCommand, observedAt: Long = now()): YpsoBolusAttempt? {
        val attempt = journal.current() ?: return null
        val observation = YpsoExtendedBolusAccounting.cancelledStatusObservation(attempt, status, observedAt) ?: return null
        return journal.observeCancelStopped(attempt.requestId, observation.deliveredCentiUnits, observation.observedAt)
    }

    fun deliver(request: YpsoValidatedBolusRequest): DeliveryResult {
        check(delivering.get()) { "delivery lifecycle was not acquired" }
        run {
            val session = bleManager.session?.snapshot()
                ?: return DeliveryResult.NotSent("The pump is not set up yet.")
            // Use the durable cursor maintained by ordinary history synchronization. A bolus must not
            // trigger a potentially 128-row selector scan before dispatch. The same-link fast-block
            // sequence proves command identity; history is scanned afterward for delivery accounting.
            val cursor = historyCursor() ?: return DeliveryResult.NotSent("AAPS is still syncing with the pump. Please try again shortly.")
            if (stopRequested.get()) return DeliveryResult.NotSent("Bolus cancelled before it started.")
            val baselineConnection = bleManager.currentBolusConnectionKey()
                ?: return DeliveryResult.NotSent("Not connected to the pump. Check that it is in range.")
            val baselineStatus = readBolusStatus() ?: return DeliveryResult.NotSent("Could not read the pump. Check that it is in range.")
            if (bleManager.currentBolusConnectionKey() != baselineConnection) {
                return DeliveryResult.NotSent("The pump connection dropped. No insulin was given.")
            }
            // The pump itself is the only authority on whether it is busy. An unreachable pump cannot be
            // dosed anyway, so this read is both the readiness check and the liveness check.
            if (baselineStatus.bolusStatusCode != BolusCommand.STATUS_IDLE) {
                return DeliveryResult.NotSent("The pump is already giving a bolus.")
            }
            if (baselineStatus.extendedStatusCode != BolusCommand.STATUS_IDLE) {
                return DeliveryResult.NotSent("The pump is already giving an extended bolus.")
            }
            val serial = serialNumber()
            val generation = bleManager.session?.activeRecord()?.generation
                ?: return DeliveryResult.NotSent("Not connected to the pump. Check that it is in range.")
            val reboot = session.reboot ?: return DeliveryResult.NotSent("Could not read the pump. Check that it is in range.")
            if (cursor.identity.pumpSerial != serial || cursor.pumpReboot != reboot.toLong()) {
                return DeliveryResult.NotSent("The pump was restarted. AAPS needs to sync before the next bolus.")
            }
            val requestId = "bolus-${UUID.randomUUID()}"
            val payloadHash = YpsoWriteAccounting.sha256(YpsoCrc.appendCrc(request.payload()))
            val attempt = YpsoBolusAttempt(
                requestId = requestId,
                pumpSerial = serial,
                sessionGeneration = generation,
                sessionKeyId = session.keyId,
                treatment = request.treatment,
                requestedCentiUnits = request.centiUnits,
                payloadHash = payloadHash,
                baseline = YpsoBolusBaseline(
                    fastSequence = baselineStatus.fastSequence,
                    slowSequence = baselineStatus.extendedSequence,
                    historyPumpId = cursor.identity.aapsPumpId,
                    historyFingerprintHigh = cursor.fingerprint.high,
                    historyFingerprintLow = cursor.fingerprint.low,
                    pumpReboot = reboot,
                    observedAt = now(),
                ),
                createdAt = now(),
                shape = request.shape,
                durationMinutes = request.durationMinutes,
                immediateCentiUnits = request.immediateCentiUnits,
            )
            journal.prepare(attempt, now(), OBSERVATION_WINDOW_MS, EXTENDED_RECONCILIATION_MARGIN_MS)
            if (stopRequested.get()) return DeliveryResult.NotSent("Bolus cancelled before it started.")

            val outcome = runCatching { awaitWrite { callback ->
                bleManager.startBolus(
                    requestId,
                    request,
                    baselineConnection,
                    beforeDispatch = { reservation ->
                        check(!stopRequested.get()) { "Bolus cancelled before it started." }
                        journal.beforeDispatch(requestId, reservation.counter, now())
                    },
                    onOutcome = callback,
                )
            } }.getOrElse {
                return DeliveryResult.Uncertain(it.message ?: "The pump did not respond. Check the pump before giving more insulin.")
            }
            when (val value = outcome.first) {
                is YpsoWriteOutcome.NotSent -> {
                    journal.provenNotApplied(requestId, rejected = false, detail = value.failure.detail)
                    return DeliveryResult.NotSent(value.failure.detail)
                }
                is YpsoWriteOutcome.ProvenRejected -> {
                    journal.provenNotApplied(requestId, rejected = true, detail = value.failure.detail)
                    return DeliveryResult.NotSent(value.failure.detail)
                }
                is YpsoWriteOutcome.PossiblyApplied -> Unit
                is YpsoWriteOutcome.AcceptedUnverified -> journal.transportAccepted(requestId)
                is YpsoWriteOutcome.Verified -> Unit
            }
            val owner = outcome.second ?: return DeliveryResult.Uncertain("The pump connection dropped while sending. Check the pump before giving more insulin.")
            if (bleManager.connectionKey(owner) != baselineConnection) {
                bleManager.recordBolusUnresolved(owner, requestId, payloadHash, "The pump connection dropped while sending. Check the pump before giving more insulin.")
                journal.unresolved(requestId, "The pump connection dropped while sending. Check the pump before giving more insulin.")
                return DeliveryResult.Uncertain("The pump connection dropped while sending. Check the pump before giving more insulin.")
            }
            commandOwner.set(owner)

            val proof = pollIdentity(attempt, request, owner)
            if (proof == null) {
                bleManager.recordBolusUnresolved(owner, requestId, payloadHash, "The bolus may have been given. Check the pump before giving more insulin.")
                journal.unresolved(requestId, "The bolus may have been given. Check the pump before giving more insulin.")
                return DeliveryResult.Uncertain("The bolus may have been given. Check the pump before giving more insulin.")
            }
            val programmed = runCatching {
                when (request.shape) {
                    YpsoBolusShape.IMMEDIATE ->
                        journal.observeFastDelivering(requestId, proof.fastSequence, cents(proof.totalProgrammedUnits))
                    YpsoBolusShape.EXTENDED, YpsoBolusShape.COMBINED ->
                        journal.observeSlowDelivering(requestId, proof.extendedSequence, cents(proof.extendedTotalUnits))
                }
            }.getOrElse {
                bleManager.recordBolusUnresolved(owner, requestId, payloadHash, it.message ?: "The bolus may have been given. Check the pump before giving more insulin.")
                journal.unresolved(requestId, it.message ?: "The bolus may have been given. Check the pump before giving more insulin.")
                return DeliveryResult.Uncertain(it.message ?: "The bolus may have been given. Check the pump before giving more insulin.")
            }
            val proofDetail = when (request.shape) {
                YpsoBolusShape.IMMEDIATE -> "fast block ${proof.fastSequence}"
                YpsoBolusShape.EXTENDED, YpsoBolusShape.COMBINED -> "slow block ${proof.extendedSequence}"
            }
            if (!bleManager.verifyBolusAccepted(owner, requestId, payloadHash, "$proofDetail programmed ${request.centiUnits} centi-units")) {
                journal.unresolved(requestId, "The bolus may have been given. Check the pump before giving more insulin.")
                return DeliveryResult.Uncertain("The bolus may have been given. Check the pump before giving more insulin.")
            }
            if (stopRequested.get()) cancelProven(programmed, proof, bleManager.connectionKey(owner))
            val observedDelivered = when (request.shape) {
                YpsoBolusShape.IMMEDIATE -> proof.deliveredUnits
                YpsoBolusShape.EXTENDED, YpsoBolusShape.COMBINED -> proof.extendedDeliveredUnits
            }
            return DeliveryResult.Started(journal.current() ?: programmed, observedDelivered)
        }
    }

    fun requestStop(): StopResult {
        if (stopRequested.compareAndSet(false, true)) historyYieldRequested.set(true)
        val attempt = journal.current() ?: return StopResult.NOT_APPLICABLE
        if (attempt.cancelRequestId != null) return StopResult.DISPATCHED_OR_PENDING
        if (attempt.provenCancelBlock == null) return StopResult.RETRY
        val owner = commandOwner.get()
        val status = if (owner != null) readBolusStatus(owner) else readBolusStatus()
        if (status == null) return StopResult.RETRY
        val connectionKey = owner?.let(bleManager::connectionKey) ?: bleManager.currentBolusConnectionKey() ?: return StopResult.RETRY
        cancelProven(attempt, status, connectionKey)
        return if (journal.current()?.cancelRequestId != null) StopResult.DISPATCHED_OR_PENDING else StopResult.RETRY
    }

    @Synchronized
    private fun cancelProven(attempt: YpsoBolusAttempt, status: BolusCommand, expectedConnection: String) {
        val block = attempt.provenCancelBlock ?: return
        val delivering = when (block) {
            YpsoBolusBlock.FAST -> status.bolusStatusCode == BolusCommand.STATUS_DELIVERING
            YpsoBolusBlock.SLOW -> status.extendedStatusCode in setOf(BolusCommand.STATUS_DELIVERING, BolusCommand.STATUS_MIXED_DELIVERING)
        }
        val sequence = when (block) {
            YpsoBolusBlock.FAST -> status.fastSequence
            YpsoBolusBlock.SLOW -> status.extendedSequence
        }
        val programmed = when (block) {
            YpsoBolusBlock.FAST -> cents(status.totalProgrammedUnits)
            YpsoBolusBlock.SLOW -> cents(status.extendedTotalUnits)
        }
        val delivered = when (block) {
            YpsoBolusBlock.FAST -> cents(status.deliveredUnits)
            YpsoBolusBlock.SLOW -> cents(status.extendedDeliveredUnits)
        }
        if (attempt.cancelRequestId != null || !delivering) return
        if (sequence != attempt.provenSequence(block) || programmed != attempt.programmedCentiUnits(block)) return
        val cancelId = "cancel-${UUID.randomUUID()}"
        val cancelHash = YpsoWriteAccounting.sha256(
            YpsoCrc.appendCrc(BolusCommand.cancelPayload(extended = block == YpsoBolusBlock.SLOW))
        )
        val outcome = runCatching { awaitWrite { callback ->
            bleManager.cancelBolus(
                cancelId,
                block,
                expectedConnection,
                beforeDispatch = { reservation -> journal.requestCancel(attempt.requestId, cancelId, reservation.counter, block, now()) },
                onOutcome = callback,
            )
        } }.getOrElse {
            journal.unresolved(attempt.requestId, it.message ?: "cancel write callback timed out")
            return
        }
        when (val value = outcome.first) {
            is YpsoWriteOutcome.NotSent -> {
                if (journal.current()?.cancelRequestId == cancelId) journal.cancelNotSent(attempt.requestId, value.failure.detail)
            }
            is YpsoWriteOutcome.ProvenRejected -> {
                if (journal.current()?.cancelRequestId == cancelId) journal.cancelNotSent(attempt.requestId, value.failure.detail)
            }
            is YpsoWriteOutcome.PossiblyApplied -> {
                outcome.second?.let { bleManager.recordBolusUnresolved(it, cancelId, cancelHash, value.failure.detail) }
                journal.unresolved(attempt.requestId, value.failure.detail)
            }
            is YpsoWriteOutcome.AcceptedUnverified -> {
                journal.observeCancelDelivery(attempt.requestId, delivered)
                val owner = outcome.second ?: return
                val after = readBolusStatus(owner)
                if (after != null) observeCancelledStatus(after)
                val idle = when (block) {
                    YpsoBolusBlock.FAST -> after?.bolusStatusCode == BolusCommand.STATUS_IDLE
                    YpsoBolusBlock.SLOW -> after?.extendedStatusCode == BolusCommand.STATUS_IDLE
                }
                val detail = if (idle) {
                    "delivery stopped after cancel dispatch; cancel acceptance remains unproven"
                } else "post-cancel status unavailable or target bolus block still active"
                bleManager.recordBolusUnresolved(owner, cancelId, cancelHash, detail)
            }
            is YpsoWriteOutcome.Verified -> outcome.second?.let { readBolusStatus(it) }?.let { observeCancelledStatus(it) }
        }
    }

    private fun pollIdentity(
        attempt: YpsoBolusAttempt,
        request: YpsoValidatedBolusRequest,
        owner: YpsoBleManager.BolusCommandOwner,
    ): BolusCommand? {
        repeat(8) {
            val status = readBolusStatus(owner)
            val proven = status != null && when (request.shape) {
                YpsoBolusShape.IMMEDIATE ->
                    status.fastSequence != attempt.baseline.fastSequence &&
                        cents(status.totalProgrammedUnits) == attempt.requestedCentiUnits &&
                        status.extendedStatusCode == BolusCommand.STATUS_IDLE
                YpsoBolusShape.EXTENDED, YpsoBolusShape.COMBINED ->
                    status.extendedSequence != attempt.baseline.slowSequence &&
                        cents(status.extendedTotalUnits) == attempt.requestedCentiUnits &&
                        status.extendedMinutesTotal == attempt.durationMinutes &&
                        status.comboImmediateTotalUnits.let(::cents) == attempt.immediateCentiUnits &&
                        status.bolusStatusCode == BolusCommand.STATUS_IDLE
            }
            if (proven) return status
            Thread.sleep(150L)
        }
        return null
    }

    private fun readBolusStatus(timeoutMs: Long = 15_000): BolusCommand? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<BolusCommand?>()
        bleManager.readBolusStatus { result.set(it); latch.countDown() }
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result.get() else null
    }

    private fun readBolusStatus(owner: YpsoBleManager.BolusCommandOwner, timeoutMs: Long = 15_000): BolusCommand? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<BolusCommand?>()
        bleManager.readBolusStatus(owner) { result.set(it); latch.countDown() }
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result.get() else null
    }

    private fun awaitWrite(
        dispatch: ((YpsoWriteOutcome, YpsoBleManager.BolusCommandOwner?) -> Unit) -> Unit,
    ): Pair<YpsoWriteOutcome, YpsoBleManager.BolusCommandOwner?> {
        val latch = CountDownLatch(1)
        val value = AtomicReference<Pair<YpsoWriteOutcome, YpsoBleManager.BolusCommandOwner?>?>()
        dispatch { outcome, owner ->
            value.set(outcome to owner)
            latch.countDown()
        }
        if (!latch.await(45, TimeUnit.SECONDS)) error("The pump did not respond. Check the pump before giving more insulin.")
        return requireNotNull(value.get())
    }

    private fun cents(units: Double): Int = Math.round(units * 100.0).toInt()

    companion object {
        const val OBSERVATION_WINDOW_MS = 90_000L
        const val EXTENDED_RECONCILIATION_MARGIN_MS = 90_000L
    }
}

internal fun BS.Type.toYpsoTreatment(): YpsoBolusTreatment = when (this) {
    BS.Type.NORMAL -> YpsoBolusTreatment.NORMAL
    BS.Type.SMB -> YpsoBolusTreatment.SMB
    BS.Type.PRIMING -> YpsoBolusTreatment.PRIME
}
