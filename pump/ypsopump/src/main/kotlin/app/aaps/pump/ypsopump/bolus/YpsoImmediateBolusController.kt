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

/** Production owner of one immediate bolus from durable intent through same-link identity proof. */
internal class YpsoImmediateBolusController(
    private val bleManager: YpsoBleManager,
    private val journal: YpsoBolusAttemptJournal,
    private val serialNumber: () -> String,
    private val historyCursor: () -> YpsoHistoryCursor?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface DeliveryResult {
        data class Started(val attempt: YpsoBolusAttempt, val observedDeliveredUnits: Double) : DeliveryResult
        data class NotSent(val detail: String) : DeliveryResult
        data class Uncertain(val detail: String) : DeliveryResult
    }

    private val delivering = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val commandOwner = AtomicReference<YpsoBleManager.BolusCommandOwner?>()

    val isBusy: Boolean get() = delivering.get()
    val cancellationRequested: Boolean get() = stopRequested.get()
    fun currentAttempt(): YpsoBolusAttempt? = journal.current()

    fun beginDelivery(): Boolean {
        if (!delivering.compareAndSet(false, true)) return false
        stopRequested.set(false)
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
        return journal.confirmTerminal(
            attempt.requestId,
            deliveredCentiUnits,
            timestamp,
            YpsoBolusBlock.FAST,
            sequence,
            historyPumpId,
            cancelled,
        )
    }

    fun markUnresolved(detail: String): YpsoBolusAttempt? {
        val attempt = journal.current() ?: return null
        return if (attempt.inhibitsAutomatedDelivery) journal.unresolved(attempt.requestId, detail) else attempt
    }

    fun deliver(request: YpsoValidatedBolusRequest): DeliveryResult {
        require(request.shape == YpsoBolusShape.IMMEDIATE)
        check(delivering.get()) { "delivery lifecycle was not acquired" }
        run {
            val session = bleManager.session?.snapshot()
                ?: return DeliveryResult.NotSent("durable pump session is unavailable")
            val cursor = historyCursor() ?: return DeliveryResult.NotSent("stable pump history baseline is unavailable")
            journal.current()?.takeIf { it.inhibitsAutomatedDelivery }?.let {
                return DeliveryResult.NotSent("earlier bolus ${it.requestId} remains ${it.outcome}")
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
            require(cursor.identity.pumpSerial == serial && cursor.pumpReboot == reboot.toLong()) {
                "history baseline belongs to another pump epoch"
            }
            val requestId = "bolus-${UUID.randomUUID()}"
            val payloadHash = YpsoWriteAccounting.sha256(YpsoCrc.appendCrc(request.payload()))
            val attempt = YpsoBolusAttempt(
                requestId = requestId,
                pumpSerial = serial,
                sessionGeneration = generation,
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
            )
            journal.prepare(attempt)
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

            val proof = pollIdentity(attempt, owner)
            if (proof == null) {
                bleManager.recordBolusUnresolved(owner, requestId, payloadHash, "same-link fast-block identity was not observed")
                journal.unresolved(requestId, "same-link fast-block identity was not observed")
                return DeliveryResult.Uncertain("delivery may have occurred; pump identity was not observed")
            }
            val programmed = runCatching {
                journal.observeFastDelivering(requestId, proof.fastSequence, cents(proof.totalProgrammedUnits))
            }.getOrElse {
                bleManager.recordBolusUnresolved(owner, requestId, payloadHash, it.message ?: "bolus identity proof rejected")
                journal.unresolved(requestId, it.message ?: "bolus identity proof rejected")
                return DeliveryResult.Uncertain(it.message ?: "bolus identity proof rejected")
            }
            if (!bleManager.verifyBolusAccepted(owner, requestId, payloadHash, "fast block ${proof.fastSequence} programmed ${request.centiUnits} centi-units")) {
                journal.unresolved(requestId, "same-link write reconciliation owner was lost")
                return DeliveryResult.Uncertain("delivery identity was observed but write ownership was lost")
            }
            if (stopRequested.get()) cancelProven(programmed, proof, bleManager.connectionKey(owner))
            return DeliveryResult.Started(journal.current() ?: programmed, proof.deliveredUnits)
        }
    }

    fun requestStop() {
        stopRequested.set(true)
        val attempt = journal.current() ?: return
        if (attempt.provenCancelBlock != YpsoBolusBlock.FAST) return
        val owner = commandOwner.get()
        val status = if (owner != null) readBolusStatus(owner) else readBolusStatus()
        if (status == null) return
        val connectionKey = owner?.let(bleManager::connectionKey) ?: bleManager.currentBolusConnectionKey() ?: return
        cancelProven(attempt, status, connectionKey)
    }

    @Synchronized
    private fun cancelProven(attempt: YpsoBolusAttempt, status: BolusCommand, expectedConnection: String) {
        if (attempt.cancelRequestId != null || status.bolusStatusCode != BolusCommand.STATUS_DELIVERING) return
        if (status.fastSequence != attempt.pumpFastSequence || cents(status.totalProgrammedUnits) != attempt.requestedCentiUnits) return
        val cancelId = "cancel-${UUID.randomUUID()}"
        val cancelHash = YpsoWriteAccounting.sha256(YpsoCrc.appendCrc(BolusCommand.cancelPayload(false)))
        val outcome = runCatching { awaitWrite { callback ->
            bleManager.cancelBolus(
                cancelId,
                YpsoBolusBlock.FAST,
                expectedConnection,
                beforeDispatch = { reservation -> journal.requestCancel(attempt.requestId, cancelId, reservation.counter, YpsoBolusBlock.FAST) },
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
                journal.observeCancelDelivery(attempt.requestId, cents(status.deliveredUnits))
                val owner = outcome.second ?: return
                val after = readBolusStatus(owner)
                val detail = if (after?.bolusStatusCode == BolusCommand.STATUS_IDLE) {
                    "delivery stopped after cancel dispatch; cancel acceptance remains unproven"
                } else "post-cancel status unavailable or fast block still active"
                bleManager.recordBolusUnresolved(owner, cancelId, cancelHash, detail)
            }
            is YpsoWriteOutcome.Verified -> Unit
        }
    }

    private fun pollIdentity(attempt: YpsoBolusAttempt, owner: YpsoBleManager.BolusCommandOwner): BolusCommand? {
        repeat(8) {
            val status = readBolusStatus(owner)
            if (status != null && status.fastSequence != attempt.baseline.fastSequence &&
                cents(status.totalProgrammedUnits) == attempt.requestedCentiUnits &&
                status.extendedStatusCode == BolusCommand.STATUS_IDLE) return status
            if (stopRequested.get() && status != null && status.fastSequence != attempt.baseline.fastSequence) return status
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
}

internal fun BS.Type.toYpsoTreatment(): YpsoBolusTreatment = when (this) {
    BS.Type.NORMAL -> YpsoBolusTreatment.NORMAL
    BS.Type.SMB -> YpsoBolusTreatment.SMB
    BS.Type.PRIMING -> YpsoBolusTreatment.PRIME
}
