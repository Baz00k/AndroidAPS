package app.aaps.pump.ypsopump.bolus

import app.aaps.core.data.model.BS
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoWriteAccounting
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
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

    fun observeCancelledStatus(status: BolusCommand, observedAt: Long = now()): YpsoBolusAttempt? {
        val attempt = journal.current() ?: return null
        val observation = YpsoExtendedBolusAccounting.cancelledStatusObservation(attempt, status, observedAt) ?: return null
        return journal.observeCancelStopped(attempt.requestId, observation.deliveredCentiUnits, observation.observedAt)
    }

    fun deliver(request: YpsoValidatedBolusRequest): DeliveryResult {
        check(delivering.get()) { "delivery lifecycle was not acquired" }
        run {
            val session = bleManager.session?.snapshot()
                ?: return DeliveryResult.NotSent("durable pump session is unavailable")
            // Use the durable cursor maintained by ordinary history synchronization. A bolus must not
            // trigger a potentially 128-row selector scan before dispatch. The same-link fast-block
            // sequence proves command identity; history is scanned afterward for delivery accounting.
            val cursor = historyCursor() ?: return DeliveryResult.NotSent("pump history has not been initialized")
            journal.current()?.takeIf { it.inhibitsNewDose(now(), OBSERVATION_WINDOW_MS, EXTENDED_RECONCILIATION_MARGIN_MS) }?.let {
                return DeliveryResult.NotSent("the pump is still processing an earlier bolus")
            }
            if (stopRequested.get()) return DeliveryResult.NotSent("bolus cancelled before dispatch")
            val baselineConnection = bleManager.currentBolusConnectionKey()
                ?: return DeliveryResult.NotSent("authenticated connection is unavailable")
            val baselineStatus = readBolusStatus() ?: return DeliveryResult.NotSent("bolus status could not be read")
            if (bleManager.currentBolusConnectionKey() != baselineConnection) {
                return DeliveryResult.NotSent("connection changed while acquiring bolus baseline")
            }
            if (baselineStatus.bolusStatusCode != BolusCommand.STATUS_IDLE || baselineStatus.extendedStatusCode != BolusCommand.STATUS_IDLE) {
                return DeliveryResult.NotSent("another bolus is active on the pump")
            }
            val serial = serialNumber()
            val generation = bleManager.session?.activeRecord()?.generation
                ?: return DeliveryResult.NotSent("authenticated session generation is unavailable")
            val reboot = session.reboot ?: return DeliveryResult.NotSent("pump reboot epoch is unavailable")
            if (cursor.identity.pumpSerial != serial || cursor.pumpReboot != reboot.toLong()) {
                return DeliveryResult.NotSent("pump history cursor belongs to another pump epoch")
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
            if (stopRequested.get()) return DeliveryResult.NotSent("bolus cancelled before dispatch")

            val outcome = runCatching { awaitWrite { callback ->
                bleManager.startBolus(
                    requestId,
                    request,
                    baselineConnection,
                    beforeDispatch = { reservation ->
                        check(!stopRequested.get()) { "bolus cancelled before dispatch" }
                        journal.beforeDispatch(requestId, reservation.counter, now())
                    },
                    onOutcome = callback,
                )
            } }.getOrElse {
                return DeliveryResult.Uncertain(it.message ?: "bolus write callback timed out")
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
            val owner = outcome.second ?: return DeliveryResult.Uncertain("bolus write owner was lost")
            if (bleManager.connectionKey(owner) != baselineConnection) {
                bleManager.recordBolusUnresolved(owner, requestId, payloadHash, "connection changed between baseline and dispatch")
                journal.unresolved(requestId, "connection changed between baseline and dispatch")
                return DeliveryResult.Uncertain("connection changed between baseline and dispatch")
            }
            commandOwner.set(owner)

            val proof = pollIdentity(attempt, request, owner)
            if (proof == null) {
                bleManager.recordBolusUnresolved(owner, requestId, payloadHash, "same-link bolus-block identity was not observed")
                journal.unresolved(requestId, "same-link bolus-block identity was not observed")
                return DeliveryResult.Uncertain("delivery may have occurred; pump identity was not observed")
            }
            val programmed = runCatching {
                when (request.shape) {
                    YpsoBolusShape.IMMEDIATE ->
                        journal.observeFastDelivering(requestId, proof.fastSequence, cents(proof.totalProgrammedUnits))
                    YpsoBolusShape.EXTENDED, YpsoBolusShape.COMBINED ->
                        journal.observeSlowDelivering(requestId, proof.extendedSequence, cents(proof.extendedTotalUnits))
                }
            }.getOrElse {
                bleManager.recordBolusUnresolved(owner, requestId, payloadHash, it.message ?: "bolus identity proof rejected")
                journal.unresolved(requestId, it.message ?: "bolus identity proof rejected")
                return DeliveryResult.Uncertain(it.message ?: "bolus identity proof rejected")
            }
            val proofDetail = when (request.shape) {
                YpsoBolusShape.IMMEDIATE -> "fast block ${proof.fastSequence}"
                YpsoBolusShape.EXTENDED, YpsoBolusShape.COMBINED -> "slow block ${proof.extendedSequence}"
            }
            if (!bleManager.verifyBolusAccepted(owner, requestId, payloadHash, "$proofDetail programmed ${request.centiUnits} centi-units")) {
                journal.unresolved(requestId, "same-link write reconciliation owner was lost")
                return DeliveryResult.Uncertain("delivery identity was observed but write ownership was lost")
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
                beforeDispatch = { reservation -> journal.requestCancel(attempt.requestId, cancelId, reservation.counter, block) },
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
        if (!latch.await(45, TimeUnit.SECONDS)) error("bolus write callback timed out")
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
