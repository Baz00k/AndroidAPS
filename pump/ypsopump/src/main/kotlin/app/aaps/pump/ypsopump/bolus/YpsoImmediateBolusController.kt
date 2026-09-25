package app.aaps.pump.ypsopump.bolus

import app.aaps.core.data.model.BS
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoWriteAccounting
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.comm.YpsoBolusNotification
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.history.YpsoBolusPumpIdentity
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
        data class NotSent(val reason: YpsoBolusMessage) : DeliveryResult
        data class Uncertain(val reason: YpsoBolusMessage) : DeliveryResult
        /**
         * Status never proved this command's programmed amount, but the pump named its sequence on
         * CONTROL_NOTIFY and announced it stopped: a fast bolus. History supplies the amount.
         */
        data class AwaitingHistory(val attempt: YpsoBolusAttempt) : DeliveryResult
    }

    private val delivering = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val historyYieldRequested = AtomicBoolean(false)
    private val commandOwner = AtomicReference<YpsoBleManager.BolusCommandOwner?>()

    /** Terminal announcement seen before its block identity was journalled: block, sequence, time. */
    private val pendingTerminal = AtomicReference<Triple<YpsoBolusBlock, Long, Long>?>()

    /**
     * The dispatch in flight: its connection and counter. Notifications received on another connection,
     * or before this was armed, are not its evidence. See [noteReceived].
     */
    private data class Dispatch(val connectionKey: String, val counter: Long, val armedAt: Long)
    private val dispatchConnection = AtomicReference<Dispatch?>()

    /** Where the notified sequence of the latest attempt was seen; outlives the command for its terminal. */
    private data class NotifiedSource(val requestId: String, val connectionKey: String, val counter: Long, val armedAt: Long)
    private val notifiedSource = AtomicReference<NotifiedSource?>()

    /**
     * First new fast sequence seen after dispatch from either source. Status and notifications must
     * agree on one sequence, whichever reported first.
     */
    private val firstNewFastSequence = AtomicReference<Long?>()

    /** Test seam: what the dispatch hook arms once the dispatch is durable. */
    @Synchronized
    internal fun armDispatchConnection(connectionKey: String, counter: Long, armedAt: Long = System.nanoTime()) {
        firstNewFastSequence.set(null)
        notifiedSource.set(null)
        conflictSeen.set(false)
        dispatchConnection.set(Dispatch(connectionKey, counter, armedAt))
    }

    /** Two different new fast sequences were seen after this dispatch: no identity may be adopted. */
    private val conflictSeen = AtomicBoolean(false)

    /** Test seam for the end of an unproven identity poll. */
    internal fun awaitHistoryAfterUnprovenPoll(requestId: String): YpsoBolusAttempt? = awaitHistoryIfNotified(requestId)

    val isBusy: Boolean get() = delivering.get()

    /** Every retained attempt, oldest first. */
    fun retainedAttempts(): List<YpsoBolusAttempt> = journal.all()
    fun confirmNotifiedTerminal(requestId: String, deliveredCentiUnits: Int, timestamp: Long, historyPumpId: Long): YpsoBolusAttempt =
        journal.confirmNotifiedTerminal(requestId, deliveredCentiUnits, timestamp, historyPumpId)
    fun rejectNotified(requestId: String, detail: String): YpsoBolusAttempt = journal.rejectNotified(requestId, detail)
    fun expireAwaitingHistory(): List<YpsoBolusAttempt> = journal.expireAwaitingHistory(now())
    val cancellationRequested: Boolean get() = stopRequested.get()
    fun consumeHistoryYield(): Boolean = historyYieldRequested.getAndSet(false)
    fun currentAttempt(): YpsoBolusAttempt? = journal.current()
    fun expireStaleAttempt(immediateWindowMs: Long, extendedMarginMs: Long): YpsoBolusAttempt? =
        journal.expireObservationWindow(now(), immediateWindowMs, extendedMarginMs)

    fun beginDelivery(): Boolean {
        if (!delivering.compareAndSet(false, true)) return false
        stopRequested.set(false)
        historyYieldRequested.set(false)
        pendingTerminal.set(null)
        return true
    }

    fun finishDelivery() {
        commandOwner.set(null)
        dispatchConnection.set(null)
        firstNewFastSequence.set(null)
        conflictSeen.set(false)
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
    fun observeBolusNotification(
        notification: YpsoBolusNotification,
        connectionKey: String? = null,
        observedAt: Long = now(),
        /** [System.nanoTime] when the BLE callback received it, before any wait for a lock. */
        receivedAt: Long = System.nanoTime(),
    ): YpsoBolusAttempt? {
        val attempt = journal.current() ?: return null
        if (!attempt.awaitsReconciliation) return null
        observeNotifiedFast(attempt, notification, connectionKey, observedAt, receivedAt)
        val block = when (attempt.shape) {
            YpsoBolusShape.IMMEDIATE -> YpsoBolusBlock.FAST
            YpsoBolusShape.EXTENDED, YpsoBolusShape.COMBINED -> YpsoBolusBlock.SLOW
        }
        // A short bolus can finish while its identity is still being proven, so the terminal
        // notification may arrive before the sequence is journalled. Remember it against the sequence
        // the pump named; dropping it here would strand the command until its timeout.
        val sequence = attempt.provenSequence(block)
            ?: return rememberPendingTerminal(notification, block, observedAt)
        if (!notification.isTerminalFor(block, sequence)) return null
        return journal.observeBlockTerminal(attempt.requestId, observedAt)
    }

    /**
     * Keeps the fast sequence the pump names after an immediate dispatch, durably. A bolus that ends
     * before any status read can prove its programmed amount leaves this as its only link to its
     * history row. Only notifications on the dispatch connection after the dispatch was committed
     * count; any other new sequence makes the evidence unusable rather than being chosen between.
     */
    private fun observeNotifiedFast(
        attempt: YpsoBolusAttempt,
        notification: YpsoBolusNotification,
        connectionKey: String?,
        observedAt: Long,
        receivedAt: Long,
    ) {
        if (attempt.shape != YpsoBolusShape.IMMEDIATE) return
        val sequence = notification.fastSequence
        if (sequence == 0L || notification.fastStatusCode == YpsoBolusNotification.STATUS_IDLE) return
        if (!YpsoBolusPumpIdentity.isStrictlyNewer(sequence, attempt.baseline.fastSequence)) return
        // Rows share this counter, so a dose after dispatch is also newer than the durable cursor.
        if (!YpsoBolusPumpIdentity.isStrictlyNewer(sequence, attempt.baseline.historyPumpId and 0xffffffffL)) return
        val terminalAt = observedAt.takeIf { notification.statusCode(YpsoBolusBlock.FAST) in YpsoBolusNotification.TERMINAL_CODES }
        runCatching {
            // The terminal announcement for an already notified sequence may arrive after the command
            // returned; it counts only on the connection that sequence was first seen on.
            val source = notifiedSource.get()
            if (attempt.notifiedFastSequence == sequence && source != null && source.requestId == attempt.requestId) {
                if (terminalAt != null && connectionKey == source.connectionKey && receivedAt - source.armedAt >= 0) {
                    journal.observeNotifiedTerminal(attempt.requestId, source.counter, sequence, terminalAt)
                }
                return
            }
            val dispatched = dispatchConnection.get()
            // Evidence is only attributable while this command owns its dispatch on that connection,
            // for the committed dispatch counter, and was received after that dispatch was armed.
            if (dispatched == null || connectionKey != dispatched.connectionKey) return
            if (attempt.dispatchCounter != dispatched.counter || receivedAt - dispatched.armedAt < 0) return
            if (!noteNewFastSequence(attempt, dispatched.counter, sequence)) return
            val saved = journal.observeNotifiedFast(attempt.requestId, dispatched.counter, sequence, terminalAt)
            if (saved.notifiedFastSequence == sequence && saved.notifiedDispatchCounter == dispatched.counter) {
                notifiedSource.compareAndSet(null, NotifiedSource(attempt.requestId, dispatched.connectionKey, dispatched.counter, dispatched.armedAt))
            }
        }
    }

    /**
     * Holds a terminal announcement that arrived before its block identity was proven. It is applied
     * by [applyPendingTerminal] once the proof names the same sequence, and never otherwise.
     */
    private fun rememberPendingTerminal(
        notification: YpsoBolusNotification,
        block: YpsoBolusBlock,
        observedAt: Long,
    ): YpsoBolusAttempt? {
        val sequence = notification.sequence(block)
        if (notification.statusCode(block) !in YpsoBolusNotification.TERMINAL_CODES || sequence == 0L) return null
        pendingTerminal.set(Triple(block, sequence, observedAt))
        return null
    }

    /** Applies a terminal announcement that raced ahead of this attempt's identity proof. */
    internal fun applyPendingTerminal(requestId: String, block: YpsoBolusBlock, sequence: Long) {
        val pending = pendingTerminal.getAndSet(null) ?: return
        if (pending.first != block || pending.second != sequence) return
        runCatching { journal.observeBlockTerminal(requestId, pending.third) }
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
                ?: return DeliveryResult.NotSent(YpsoBolusMessage.PUMP_NOT_SET_UP)
            // Use the durable cursor maintained by ordinary history synchronization. A bolus must not
            // trigger a potentially 128-row selector scan before dispatch. The same-link fast-block
            // sequence proves command identity; history is scanned afterward for delivery accounting.
            val cursor = historyCursor() ?: return DeliveryResult.NotSent(YpsoBolusMessage.SYNC_IN_PROGRESS)
            if (stopRequested.get()) return DeliveryResult.NotSent(YpsoBolusMessage.BOLUS_CANCELLED_BEFORE_START)
            val baselineConnection = bleManager.currentBolusConnectionKey()
                ?: return DeliveryResult.NotSent(YpsoBolusMessage.PUMP_NOT_CONNECTED)
            val baselineStatus = readBolusStatus() ?: return DeliveryResult.NotSent(YpsoBolusMessage.PUMP_UNREADABLE)
            if (bleManager.currentBolusConnectionKey() != baselineConnection) {
                return DeliveryResult.NotSent(YpsoBolusMessage.CONNECTION_DROPPED_NO_INSULIN)
            }
            // The pump itself is the only authority on whether it is busy. An unreachable pump cannot be
            // dosed anyway, so this read is both the readiness check and the liveness check.
            if (baselineStatus.bolusStatusCode != BolusCommand.STATUS_IDLE) {
                return DeliveryResult.NotSent(YpsoBolusMessage.PUMP_ALREADY_BOLUSING)
            }
            if (baselineStatus.extendedStatusCode != BolusCommand.STATUS_IDLE) {
                return DeliveryResult.NotSent(YpsoBolusMessage.PUMP_ALREADY_EXTENDED_BOLUSING)
            }
            val serial = serialNumber()
            val generation = bleManager.session?.activeRecord()?.generation
                ?: return DeliveryResult.NotSent(YpsoBolusMessage.PUMP_NOT_CONNECTED)
            val reboot = session.reboot ?: return DeliveryResult.NotSent(YpsoBolusMessage.PUMP_UNREADABLE)
            if (cursor.identity.pumpSerial != serial || cursor.pumpReboot != reboot.toLong()) {
                return DeliveryResult.NotSent(YpsoBolusMessage.PUMP_RESTARTED)
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
            if (stopRequested.get()) return DeliveryResult.NotSent(YpsoBolusMessage.BOLUS_CANCELLED_BEFORE_START)

            val outcome = runCatching { awaitWrite { callback ->
                bleManager.startBolus(
                    requestId,
                    request,
                    baselineConnection,
                    beforeDispatch = { reservation ->
                        check(!stopRequested.get()) { "bolus was cancelled before dispatch" }
                        journal.beforeDispatch(requestId, reservation.counter, now())
                        // Armed only once the dispatch is durable, before any frame leaves. A re-dispatch
                        // re-arms, so evidence from the rejected dispatch never carries over.
                        armDispatchConnection(baselineConnection, reservation.counter)
                    },
                    onOutcome = callback,
                )
            } }.getOrElse {
                return DeliveryResult.Uncertain(YpsoBolusMessage.PUMP_DID_NOT_RESPOND)
            }
            when (val value = outcome.first) {
                is YpsoWriteOutcome.NotSent -> {
                    journal.provenNotApplied(requestId, rejected = false, detail = value.failure.detail)
                    return DeliveryResult.NotSent(YpsoBolusMessage.PUMP_DID_NOT_RESPOND)
                }
                is YpsoWriteOutcome.ProvenRejected -> {
                    journal.provenNotApplied(requestId, rejected = true, detail = value.failure.detail)
                    return DeliveryResult.NotSent(YpsoBolusMessage.PUMP_DID_NOT_RESPOND)
                }
                is YpsoWriteOutcome.PossiblyApplied -> Unit
                is YpsoWriteOutcome.AcceptedUnverified -> journal.transportAccepted(requestId)
                is YpsoWriteOutcome.Verified -> Unit
            }
            val owner = outcome.second ?: return DeliveryResult.Uncertain(YpsoBolusMessage.CONNECTION_DROPPED_WHILE_SENDING)
            if (bleManager.connectionKey(owner) != baselineConnection) {
                bleManager.recordBolusUnresolved(owner, requestId, payloadHash, "connection changed between dispatch and identity proof")
                journal.unresolved(requestId, "connection changed between dispatch and identity proof")
                return DeliveryResult.Uncertain(YpsoBolusMessage.CONNECTION_DROPPED_WHILE_SENDING)
            }
            commandOwner.set(owner)

            val proof = pollIdentity(attempt, request, owner)
            if (proof == null) {
                bleManager.recordBolusUnresolved(owner, requestId, payloadHash, "no bolus status proved this command's block identity")
                // A fast bolus can end before any status read shows its programmed amount. The pump still
                // named its sequence and announced it stopped, which lets history account for it.
                awaitHistoryIfNotified(requestId)?.let { return DeliveryResult.AwaitingHistory(it) }
                journal.unresolved(requestId, "no bolus status proved this command's block identity")
                return DeliveryResult.Uncertain(YpsoBolusMessage.MAY_HAVE_BEEN_GIVEN)
            }
            val programmed = runCatching {
                when (request.shape) {
                    YpsoBolusShape.IMMEDIATE ->
                        journal.observeFastDelivering(requestId, proof.fastSequence, cents(proof.totalProgrammedUnits))
                    YpsoBolusShape.EXTENDED, YpsoBolusShape.COMBINED ->
                        journal.observeSlowDelivering(requestId, proof.extendedSequence, cents(proof.extendedTotalUnits))
                }
            }.getOrElse {
                bleManager.recordBolusUnresolved(owner, requestId, payloadHash, it.message ?: "delivery identity could not be journalled")
                journal.unresolved(requestId, it.message ?: "delivery identity could not be journalled")
                return DeliveryResult.Uncertain(YpsoBolusMessage.MAY_HAVE_BEEN_GIVEN)
            }
            when (request.shape) {
                YpsoBolusShape.IMMEDIATE -> applyPendingTerminal(requestId, YpsoBolusBlock.FAST, proof.fastSequence)
                YpsoBolusShape.EXTENDED, YpsoBolusShape.COMBINED ->
                    applyPendingTerminal(requestId, YpsoBolusBlock.SLOW, proof.extendedSequence)
            }
            val proofDetail = when (request.shape) {
                YpsoBolusShape.IMMEDIATE -> "fast block ${proof.fastSequence}"
                YpsoBolusShape.EXTENDED, YpsoBolusShape.COMBINED -> "slow block ${proof.extendedSequence}"
            }
            if (!bleManager.verifyBolusAccepted(owner, requestId, payloadHash, "$proofDetail programmed ${request.centiUnits} centi-units")) {
                journal.unresolved(requestId, "bolus acceptance could not be reconciled with write accounting")
                return DeliveryResult.Uncertain(YpsoBolusMessage.MAY_HAVE_BEEN_GIVEN)
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
        var firstObserved: Long? = null
        repeat(8) {
            val status = readBolusStatus(owner)
            val immediate = request.shape == YpsoBolusShape.IMMEDIATE
            if (immediate) status?.fastSequence?.let { noteStatusSequence(attempt, it) }
            // Two deliveries were seen across status and notifications: neither identity is this command's.
            if (immediate && conflictSeen.get()) return null
            when (val step = YpsoBolusIdentityPoll.evaluate(attempt, request, status, firstObserved ?: firstNewFastSequence.get())) {
                is YpsoBolusIdentityPoll.Step.Proven    -> return step.status
                is YpsoBolusIdentityPoll.Step.Abandon   -> {
                    val counter = dispatchConnection.get()?.counter
                    if (immediate && counter != null) runCatching { journal.markNotifiedConflict(attempt.requestId, counter) }
                    return null
                }
                is YpsoBolusIdentityPoll.Step.KeepGoing -> firstObserved = step.firstObservedSequence
            }
            // The pump announced the notified block stopped and status shows it cleared: no later read
            // can show its programmed amount, so waiting longer only delays the command.
            if (immediate && status?.bolusStatusCode == BolusCommand.STATUS_IDLE &&
                journal.current()?.takeIf { it.requestId == attempt.requestId }?.notifiedTerminalAt != null) return null
            Thread.sleep(150L)
        }
        return null
    }

    /** A status-reported new fast sequence joins the notifications' evidence. */
    private fun noteStatusSequence(attempt: YpsoBolusAttempt, sequence: Long) {
        if (sequence == 0L || !YpsoBolusPumpIdentity.isStrictlyNewer(sequence, attempt.baseline.fastSequence)) return
        val counter = dispatchConnection.get()?.counter ?: return
        noteNewFastSequence(attempt, counter, sequence)
    }

    /**
     * Records [sequence] as the first new fast sequence after dispatch, or, when a different one was
     * already seen by either source, persists the conflict. Returns whether [sequence] is still usable.
     */
    /** Synchronized with [armDispatchConnection], so evidence validated for an older dispatch never lands here. */
    @Synchronized
    private fun noteNewFastSequence(attempt: YpsoBolusAttempt, dispatchCounter: Long, sequence: Long): Boolean {
        if (dispatchConnection.get()?.counter != dispatchCounter) return false
        if (conflictSeen.get()) return false
        val first = firstNewFastSequence.get() ?: sequence.also { firstNewFastSequence.set(it) }
        if (first == sequence) return true
        conflictSeen.set(true)
        runCatching { journal.markNotifiedConflict(attempt.requestId, dispatchCounter) }
        return false
    }

    /**
     * Moves an unproven fast bolus to [YpsoBolusOutcome.AWAITING_HISTORY] when its notification evidence
     * is complete and uncontested. Anything less stays uncertain with its warning.
     */
    private fun awaitHistoryIfNotified(requestId: String): YpsoBolusAttempt? {
        val current = journal.current()?.takeIf { it.requestId == requestId } ?: return null
        if (current.shape != YpsoBolusShape.IMMEDIATE || current.pumpFastSequence != null) return null
        if (current.notifiedAccountingPumpId == null || current.notifiedTerminalAt == null) return null
        if (current.outcome !in setOf(YpsoBolusOutcome.POSSIBLY_APPLIED, YpsoBolusOutcome.ACCEPTED_UNVERIFIED)) return null
        return runCatching { journal.awaitHistory(requestId, now() + HISTORY_CONFIRMATION_WINDOW_MS) }.getOrNull()
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
        if (!latch.await(45, TimeUnit.SECONDS)) error("bolus write callback did not arrive within 45 seconds")
        return requireNotNull(value.get())
    }

    private fun cents(units: Double): Int = Math.round(units * 100.0).toInt()

    companion object {
        const val OBSERVATION_WINDOW_MS = 90_000L
        const val EXTENDED_RECONCILIATION_MARGIN_MS = 90_000L
        /**
         * How long a notified fast bolus waits quietly for its history row before its warning shows.
         * Background history reaches a new row within minutes once caught up; this leaves room for a
         * reconnect or a slow scan without hiding a dose history cannot confirm.
         */
        const val HISTORY_CONFIRMATION_WINDOW_MS = 30 * 60_000L
    }
}

internal fun BS.Type.toYpsoTreatment(): YpsoBolusTreatment = when (this) {
    BS.Type.NORMAL -> YpsoBolusTreatment.NORMAL
    BS.Type.SMB -> YpsoBolusTreatment.SMB
    BS.Type.PRIMING -> YpsoBolusTreatment.PRIME
}
