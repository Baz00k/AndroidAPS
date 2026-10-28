package app.aaps.pump.ypsopump.bolus

import android.os.SystemClock
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.ypsopump.comm.YpsoBolusNotification
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.history.YpsoBolusPumpIdentity
import app.aaps.pump.ypsopump.history.YpsoEventIdentity
import app.aaps.pump.ypsopump.history.YpsoHistoryClassifier
import app.aaps.pump.ypsopump.history.YpsoHistoryCursor
import app.aaps.pump.ypsopump.history.YpsoHistoryFingerprint
import app.aaps.pump.ypsopump.history.YpsoHistoryKind
import app.aaps.pump.ypsopump.history.YpsoHistoryReconciler
import app.aaps.pump.ypsopump.history.YpsoHistoryReconciliation
import app.aaps.pump.ypsopump.history.YpsoHistorySnapshot
import app.aaps.pump.ypsopump.history.YpsoPumpLocalTime
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** The AAPS bolus records [YpsoBolusDelivery] keeps for its doses. */
internal interface YpsoBolusRecords {
    /** Saves the provisional record of a dispatched dose; false when nothing was saved now. */
    fun addProvisional(timestamp: Long, units: Double, temporaryId: Long, type: BS.Type, pumpSerial: String): Boolean

    /** Merges the record under [bolus]'s temporary ID with its pump ID, carrying [bolus]'s amount and time. */
    fun syncWithTemporaryId(bolus: BS, type: BS.Type)

    /**
     * Like [syncWithTemporaryId] for an inferred link: refuses to merge two records when either was
     * removed. A removed provisional record with no pump record yet takes the pump ID and stays removed.
     */
    fun bindNotified(bolus: BS, type: BS.Type): PersistenceLayer.TempIdBinding

    fun bolus(pumpId: Long, pumpSerial: String): BS?

    /** Bolus records of this pump from [from], including removed ones. */
    fun bolusesFrom(from: Long, pumpSerial: String): List<BS>

    fun extended(pumpId: Long, pumpSerial: String): EB?

    /** Saves a running extended bolus under its pump ID; false when AAPS did not take it. */
    fun syncExtended(start: Long, units: Double, duration: Long, pumpId: Long, pumpSerial: String): Boolean

    /** Overwrites the extended bolus under [pumpId] with what the pump proved. */
    fun correctExtended(start: Long, units: Double, duration: Long, emulatingTempBasal: Boolean, pumpId: Long, pumpSerial: String)

    /** Whether AAPS currently counts any extended bolus as running. */
    fun extendedRunning(): Boolean
}

/**
 * Owns bolus delivery: immediate and extended starts, Stop and extended cancellation, the provisional
 * record and its binding to pump history, and the repair passes that keep both consistent.
 *
 * Every dose is journalled before it can leave the phone. Whether it was requested, acknowledged or
 * confirmed is decided only here, from same-link status, the pump's own notifications and terminal
 * history. Insulin that may be in the body is recorded at once and never retried.
 */
internal class YpsoBolusDelivery(
    private val link: YpsoBolusLink,
    private val records: YpsoBolusRecords,
    journalFile: File,
    private val serialNumber: () -> String,
    private val historyCursor: () -> YpsoHistoryCursor?,
    /** Local gate that history can account for a new dose; never pump I/O. */
    private val historyReadiness: (pumpSerial: String, reboot: Long) -> YpsoBolusMessage?,
    /** Shows or clears the persistent unresolved-bolus warning. */
    private val showWarning: (Boolean) -> Unit,
    private val aapsLogger: AAPSLogger,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    /** A bolus request: units and carbs as AAPS asked. */
    data class Order(val units: Double, val carbs: Double, val type: BS.Type)

    sealed interface Result {
        /** Nothing reached the pump. [invalidRequest] when the request itself cannot be sent. */
        data class Failed(val reason: YpsoBolusMessage, val invalidRequest: Boolean = false) : Result

        /** The request failed validation; [detail] is the validator's text. Nothing reached the pump. */
        data class Invalid(val detail: String) : Result

        /** The lifecycle failed unexpectedly; whatever was dispatched stays journalled. */
        data class Error(val detail: String?) : Result

        /** Insulin may have been delivered; it is recorded and must not be repeated. [cancel] for a cancellation. */
        data class Uncertain(val reason: YpsoBolusMessage, val cancel: Boolean = false) : Result

        /** The pump announced the dose stopped; history supplies its amount. */
        data object AwaitingHistory : Result

        /** The dose ended and [units] is recorded. [proven] when same-command status saw it all delivered. */
        data class Delivered(val units: Double, val proven: Boolean) : Result

        data class ExtendedStarted(val units: Double, val durationMinutes: Int) : Result

        /** The extended bolus ended with [units] recorded. */
        data class Cancelled(val units: Double) : Result

        /** No extended bolus runs. */
        data object NothingToCancel : Result
    }

    /** What history ingestion may do with the scan just reconciled. */
    sealed interface HistoryPass {
        /** This dose's terminal row is still being matched to it; ingest it on a later pass. */
        data object Hold : HistoryPass

        /** Ingest; [treatment] is the record type for the bolus row with that pump ID. */
        class Proceed(val treatment: (pumpId: Long) -> BS.Type) : HistoryPass
    }

    private val store = YpsoBolusAttemptFileStore(journalFile)
    private val journal = YpsoBolusAttemptJournal(store)
    private val controller = YpsoImmediateBolusController(link, journal, serialNumber, historyCursor, now, sleep)

    /** Stop was requested for the bolus being prepared, before its controller lifecycle began. */
    private val stopBeforeStart = AtomicBoolean(false)

    val isBusy: Boolean get() = controller.isBusy

    /** Counter-floor evidence for journal-loss recovery, read under the journal's lock. */
    fun recoveryEvidence(): YpsoBolusAttemptFileStore.RecoveryEvidence? = journal.locked { store.recoveryEvidence() }

    /** A Stop pressed from now on belongs to the next [deliver], even while its link is reopening. */
    fun beginRequest() {
        stopBeforeStart.set(false)
    }

    /**
     * Stops the bolus being prepared or delivered. The stop is marked before [handOverLink] takes the
     * link from background history, so a delivery starting meanwhile sends nothing; only then is a
     * dispatched dose cancelled.
     */
    fun stop(handOverLink: () -> Unit) {
        stopBeforeStart.set(true)
        handOverLink()
        runCatching { controller.requestStop() }
            .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump stop bolus failed: ${it.message}") }
    }

    fun onNotification(notification: YpsoBolusNotification, connectionKey: String?, receivedAt: Long) {
        controller.observeBolusNotification(notification, connectionKey, receivedAt = receivedAt)
    }

    /**
     * Process death must not turn the finite immediate-bolus observation period into a permanent
     * therapy block. Identity and accounting evidence remain durable and recoverable.
     */
    fun expireStale() {
        controller.expireStaleAttempt(OBSERVATION_WINDOW_MS, EXTENDED_RECONCILIATION_MARGIN_MS)
    }

    /** [maxUnits] reads the AAPS bolus constraint once the link is held, just before validation. */
    fun deliver(order: Order, maxUnits: () -> Double, progress: (Double) -> Unit): Result {
        // A stop pressed while the link was reopening belongs to this bolus; beginDelivery clears the
        // controller's own flag, so it is carried across here and nothing is dispatched.
        if (stopBeforeStart.get()) return Result.Failed(YpsoBolusMessage.BOLUS_CANCELLED_BEFORE_START)
        if (!controller.beginDelivery()) return Result.Failed(YpsoBolusMessage.ANOTHER_BOLUS_IN_PROGRESS)
        if (stopBeforeStart.get()) controller.requestStop()
        try {
            return runCatching { deliverImmediate(order, maxUnits, progress) }.getOrElse {
                aapsLogger.error(LTag.PUMP, "YpsoPump bolus lifecycle failed: ${it.message}")
                Result.Error(it.message)
            }
        } finally {
            controller.finishDelivery()
            // The recorded amount is the requested one until the terminal history row proves what was
            // actually delivered, so reconcile in the background without delaying this command.
            link.reconcileHistoryLater("bolus delivery")
        }
    }

    private fun deliverImmediate(order: Order, maxUnits: () -> Double, progress: (Double) -> Unit): Result {
        if (order.carbs != 0.0) return Result.Failed(YpsoBolusMessage.CARBS_NOT_STORED, invalidRequest = true)
        val constrainedMaximum = maxUnits()
        val request = runCatching { YpsoBolusRequestValidator.validate(order.units, order.type.toYpsoTreatment(), constrainedMaximum) }
            .getOrElse { return Result.Invalid(it.message ?: "invalid request") }
        when (link.therapyStatus(stopWhen = { controller.cancellationRequested })) {
            YpsoBolusLink.Readiness.READY              -> Unit
            YpsoBolusLink.Readiness.CANCELLED          -> return Result.Failed(YpsoBolusMessage.BOLUS_CANCELLED_BEFORE_START)
            YpsoBolusLink.Readiness.HISTORY_BUSY       -> return Result.Failed(YpsoBolusMessage.PUMP_BUSY)
            YpsoBolusLink.Readiness.STATUS_UNAVAILABLE -> return Result.Failed(YpsoBolusMessage.PUMP_UNREADABLE)
        }
        if (link.stoppedOrEmpty()) return Result.Failed(YpsoBolusMessage.PUMP_STOPPED_OR_EMPTY)
        val reboot = link.epoch()?.reboot ?: return Result.Failed(YpsoBolusMessage.PUMP_UNREADABLE)
        historyReadiness(serialNumber(), reboot.toLong())?.let { return Result.Failed(it) }
        return when (val result = controller.deliver(request)) {
            is YpsoImmediateBolusController.DeliveryResult.Started -> {
                recordProvisional(result.attempt, order.type)
                awaitTerminal(result.attempt.requestId, progress, result.observedDeliveredUnits)
            }
            is YpsoImmediateBolusController.DeliveryResult.NotSent -> Result.Failed(result.reason)
            is YpsoImmediateBolusController.DeliveryResult.Uncertain -> {
                // Delivery may have happened. Account for it in full now; terminal history corrects the
                // amount later. Insulin that is possibly in the body must never be invisible to IOB.
                controller.currentAttempt()?.let { recordProvisional(it, order.type) }
                Result.Uncertain(result.reason)
            }
            is YpsoImmediateBolusController.DeliveryResult.AwaitingHistory -> {
                // The pump announced this dose stopped but no status showed its amount. The requested
                // amount stays counted until its history row, found by the notified sequence, replaces it.
                // The quiet wait needs that record: without it the dose is urgent at once.
                if (!recordProvisional(result.attempt, order.type)) {
                    controller.markUnresolved("the provisional record of a notified bolus was not saved")
                    refreshWarning()
                    Result.Uncertain(YpsoBolusMessage.MAY_HAVE_BEEN_GIVEN)
                } else Result.AwaitingHistory
            }
        }
    }

    /**
     * Records the dispatched dose immediately so it reaches IOB without waiting for pump history. The
     * full requested amount is the conservative figure; the terminal history row later replaces it with
     * the delivered amount through [provisionalTemporaryId], which also prevents a duplicate record.
     * Returns whether the record was saved now; a record already present under the same id returns false.
     */
    private fun recordProvisional(attempt: YpsoBolusAttempt, type: BS.Type): Boolean {
        if (attempt.shape != YpsoBolusShape.IMMEDIATE) return false
        val timestamp = attempt.dispatchedAt ?: attempt.createdAt
        return runCatching {
            records.addProvisional(timestamp, attempt.requestedUnits, provisionalTemporaryId(attempt), type, serialNumber())
        }.onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump provisional bolus accounting failed: ${it.message}") }
            .getOrDefault(false)
    }

    private fun awaitTerminal(requestId: String, progress: (Double) -> Unit, initiallyDelivered: Double): Result {
        val deadline = elapsed() + TERMINAL_WAIT_MS
        progress(initiallyDelivered)
        var cancellationSignalled = false
        /** Highest same-command pump-reported delivered amount, or -1 before any status proved one. */
        var observedDeliveredCentiUnits = -1
        while (elapsed() < deadline) {
            if (controller.cancellationRequested && !cancellationSignalled) {
                // Stop is a request, not terminal evidence. Signal it promptly once, then continue
                // observing same-command status and authoritative type-2 history for partial delivery.
                cancellationSignalled = controller.requestStop() == YpsoImmediateBolusController.StopResult.DISPATCHED_OR_PENDING
            }
            val attempt = controller.currentAttempt()
            if (attempt?.requestId != requestId) {
                controller.markUnresolved("another request owns the bolus journal")
                refreshWarning()
                return Result.Uncertain(YpsoBolusMessage.NOT_MATCHED_TO_PUMP)
            }
            if (attempt.confirmedCentiUnits != null) {
                val delivered = attempt.confirmedUnits ?: 0.0
                progress(delivered)
                return Result.Delivered(delivered, proven = true)
            }
            val status = link.status()
            val provenSequence = attempt.pumpFastSequence
            if (status != null && provenSequence != null &&
                status.fastSequence == provenSequence &&
                cents(status.totalProgrammedUnits) == attempt.requestedCentiUnits
            ) {
                // This is same-command status evidence suitable for UI progress. Pump history remains
                // authoritative for the final delivered amount and PumpSync accounting.
                progress(status.deliveredUnits)
                observedDeliveredCentiUnits = cents(status.deliveredUnits)
            }
            // The pump announced that this exact dose finished. Insulin is already accounted for, so the
            // command completes here; background history replaces the requested amount with the
            // delivered one. History is never read on the therapy path: it cannot finish in time.
            if (attempt.blockTerminalAt != null) {
                val delivered = attempt.requestedUnits
                progress(delivered)
                // A stop was requested, so the pump may have ended this dose early. The amount is not
                // knowable here, so report it as uncertain rather than claiming the full request was
                // given; history reconciliation corrects the record to what was actually delivered.
                if (cancellationSignalled || controller.cancellationRequested) {
                    refreshWarning()
                    return Result.Uncertain(YpsoBolusMessage.STOPPED_AMOUNT_UNKNOWN)
                }
                // The pump uses one terminal code for normal completion and for a cancellation made on
                // the pump itself, and the announcement carries no amount. Full delivery may therefore
                // only be claimed when same-command status actually observed it; otherwise the recorded
                // amount stays the request and history confirms the delivered figure shortly after.
                return Result.Delivered(delivered, proven = observedDeliveredCentiUnits == attempt.requestedCentiUnits)
            }
            sleep(250L)
        }
        // Insulin was recorded at dispatch, so nothing is unaccounted; only the exact amount is pending.
        controller.markUnresolved("the pump did not report the end of this bolus")
        refreshWarning()
        return Result.Uncertain(YpsoBolusMessage.NOT_CONFIRMED_FINISHED)
    }

    fun startExtended(units: Double, durationMinutes: Int, maxUnits: Double): Result {
        val request = runCatching {
            YpsoBolusRequestValidator.validateDelivery(
                units,
                durationMinutes,
                immediateUnits = 0.0,
                treatment = YpsoBolusTreatment.NORMAL,
                aapsMaxBolus = maxUnits,
            )
        }.getOrElse { return Result.Invalid(it.message ?: "invalid request") }
        if (!controller.beginDelivery()) return Result.Failed(YpsoBolusMessage.ANOTHER_BOLUS_IN_PROGRESS)
        try {
            when (link.therapyStatus(stopWhen = { false })) {
                YpsoBolusLink.Readiness.READY              -> Unit
                YpsoBolusLink.Readiness.HISTORY_BUSY       -> return Result.Failed(YpsoBolusMessage.PUMP_BUSY)
                YpsoBolusLink.Readiness.CANCELLED,
                YpsoBolusLink.Readiness.STATUS_UNAVAILABLE -> return Result.Failed(YpsoBolusMessage.PUMP_UNREADABLE)
            }
            if (link.stoppedOrEmpty()) return Result.Failed(YpsoBolusMessage.PUMP_STOPPED_OR_EMPTY)
            val reboot = link.epoch()?.reboot ?: return Result.Failed(YpsoBolusMessage.PUMP_UNREADABLE)
            historyReadiness(serialNumber(), reboot.toLong())?.let { return Result.Failed(it) }
            return when (val result = controller.deliver(request)) {
                is YpsoImmediateBolusController.DeliveryResult.Started -> {
                    val attempt = result.attempt
                    val start = requireNotNull(attempt.dispatchedAt)
                    val duration = request.durationMinutes * 60_000L
                    val pumpId = attempt.pumpSlowSequence
                        ?.let { YpsoBolusPumpIdentity.of(attempt.baseline.historyPumpId, it) }
                        ?: return uncertainExtended(YpsoBolusMessage.EXTENDED_NOT_SAVED, "extended delivery identity was never journalled")
                    val synced = records.syncExtended(start, request.units, duration, pumpId, serialNumber())
                    if (!synced && !extendedAccountingMatches(pumpId, start, request.units, duration, serialNumber())) {
                        return uncertainExtended(YpsoBolusMessage.EXTENDED_NOT_SAVED, "AAPS rejected the extended bolus accounting record")
                    }
                    Result.ExtendedStarted(request.units, request.durationMinutes)
                }
                is YpsoImmediateBolusController.DeliveryResult.NotSent -> Result.Failed(result.reason)
                is YpsoImmediateBolusController.DeliveryResult.Uncertain -> Result.Uncertain(result.reason)
                // Only immediate doses can reach this state.
                is YpsoImmediateBolusController.DeliveryResult.AwaitingHistory ->
                    uncertainExtended(YpsoBolusMessage.MAY_HAVE_BEEN_GIVEN, "extended bolus reached a fast-bolus state")
            }
        } finally {
            controller.finishDelivery()
            // An extended dose whose identity could not be bound is still recoverable from its
            // terminal history row, so reconcile in the background without delaying this command.
            link.reconcileHistoryLater("extended bolus delivery")
        }
    }

    /**
     * The pump may be delivering, so the dose is reported as uncertain and never as not delivered.
     * [journalDetail] stays untranslated: it is durable diagnostic evidence, not operator text.
     */
    private fun uncertainExtended(reason: YpsoBolusMessage, journalDetail: String, cancel: Boolean = false): Result {
        controller.markUnresolved(journalDetail)
        refreshWarning()
        return Result.Uncertain(reason, cancel)
    }

    fun cancelExtended(): Result {
        // Therapy cancellation outranks abandonable accounting. Do not overlap bolus status/cancel I/O
        // with an in-flight selector transaction; it must first reconcile at a selector-safe boundary.
        if (!link.yieldHistory()) return Result.Failed(YpsoBolusMessage.PUMP_BUSY)
        val attempt = controller.currentAttempt()
        if (attempt == null || attempt.shape == YpsoBolusShape.IMMEDIATE || !attempt.awaitsReconciliation) {
            if (records.extendedRunning()) return Result.Failed(YpsoBolusMessage.EXTENDED_NOT_STARTED_BY_AAPS)
            return Result.NothingToCancel
        }
        if (!controller.beginDelivery()) return Result.Failed(YpsoBolusMessage.ANOTHER_BOLUS_IN_PROGRESS)
        try {
            controller.requestStop()
            val deadline = elapsed() + TERMINAL_WAIT_MS
            while (elapsed() < deadline) {
                controller.requestStop()
                val current = controller.currentAttempt() ?: return Result.Failed(YpsoBolusMessage.EXTENDED_NOT_MATCHED_TO_PUMP)
                if (current.confirmedCentiUnits != null) return Result.Cancelled(current.confirmedUnits ?: 0.0)
                current.cancelStoppedAt?.let { stoppedAt ->
                    return finishStatusConfirmedCancellation(current, requireNotNull(current.cancelObservedCentiUnits), stoppedAt)
                }
                // The pump announced that this exact slow sequence stopped. That is terminal proof the
                // delivery ended; only the delivered amount is still outstanding, so resolve now and let
                // background history replace the elapsed estimate with the exact figure.
                current.blockTerminalAt?.let { return finishUnprovenCancellation(current) }
                if (!link.isConnected) {
                    aapsLogger.debug(LTag.PUMP, "YpsoPump reconnecting to confirm extended bolus cancellation")
                    if (!link.reconnect(RECONNECT_MS)) {
                        sleep(250L)
                        continue
                    }
                }
                val status = link.status()
                val observed = status?.let { controller.observeCancelledStatus(it, now()) }
                if (observed?.cancelStoppedAt != null) {
                    return finishStatusConfirmedCancellation(observed, requireNotNull(observed.cancelObservedCentiUnits), observed.cancelStoppedAt)
                }
                // History is deliberately not read here. A large scan cannot finish inside this window
                // and previously consumed it entirely, starving the status read that proves cancellation.
                // Background recovery reconciles the durable record after the command returns.
                sleep(250L)
            }
            return finishUnprovenCancellation(controller.currentAttempt())
        } finally {
            controller.finishDelivery()
            // Reconcile the authoritative record without holding up the command result.
            link.reconcileHistoryLater("extended bolus cancellation")
        }
    }

    /**
     * Closes a cancellation that returned without a pump-confirmed delivered amount.
     *
     * Shortening the record is only safe once the pump has proved that delivery stopped. A cancel
     * that was merely dispatched may never have reached the pump, so the insulin may still be
     * running; reducing the record then would hide insulin from IOB and invite stacking. The
     * programmed record therefore stands until pump evidence replaces it, which over-states insulin
     * in the safe direction and leaves background history to correct it.
     */
    private fun finishUnprovenCancellation(attempt: YpsoBolusAttempt?): Result {
        val unconfirmed = YpsoBolusMessage.EXTENDED_CANCEL_UNCONFIRMED
        if (attempt == null) return uncertainExtended(unconfirmed, "no bolus attempt owns this cancellation", cancel = true)
        // Only the pump's own terminal announcement proves this delivery ended.
        val announced = attempt.blockTerminalAt ?: return uncertainExtended(
            YpsoBolusMessage.EXTENDED_STOP_UNCONFIRMED,
            "cancellation was dispatched but the pump never announced a terminal transition",
            cancel = true,
        )
        val sequence = attempt.pumpSlowSequence
            ?: return uncertainExtended(unconfirmed, "extended delivery identity was never journalled", cancel = true)
        val window = YpsoExtendedBolusAccounting.unprovenCancelWindow(attempt, announced)
        val centiUnits = YpsoExtendedBolusAccounting.elapsedCentiUnits(attempt, window)
        val pumpId = YpsoBolusPumpIdentity.of(attempt.baseline.historyPumpId, sequence)
            ?: return uncertainExtended(unconfirmed, "extended bolus identity generation is not representable", cancel = true)
        records.correctExtended(window.start, centiUnits / 100.0, window.duration, false, pumpId, serialNumber())
        if (!extendedAccountingMatches(pumpId, window.start, centiUnits / 100.0, window.duration, serialNumber())) {
            return uncertainExtended(YpsoBolusMessage.EXTENDED_CANCEL_NOT_SAVED, "cancelled extended bolus accounting was rejected", cancel = true)
        }
        // The recorded amount is only the elapsed schedule, so the attempt must stay open for
        // reconciliation: closing it here with confirmTerminal would make the estimate permanent.
        aapsLogger.info(
            LTag.PUMP,
            "YpsoPump extended bolus cancelled; recorded ${centiUnits / 100.0} U over ${window.duration / 60_000} min pending history",
        )
        return Result.Cancelled(centiUnits / 100.0)
    }

    private fun finishStatusConfirmedCancellation(attempt: YpsoBolusAttempt, deliveredCentiUnits: Int, observedAt: Long): Result {
        // Terminal cancel evidence is only accepted against a proven slow sequence, so it exists here.
        val sequence = requireNotNull(attempt.pumpSlowSequence)
        val pumpId = YpsoBolusPumpIdentity.of(attempt.baseline.historyPumpId, sequence)
            ?: return uncertainExtended(
                YpsoBolusMessage.STOPPED_AMOUNT_NOT_SAVED,
                "extended bolus identity generation is not representable",
                cancel = true,
            )
        val terminal = YpsoExtendedBolusAccounting.terminalWindow(attempt, deliveredCentiUnits, observedAt)
        records.correctExtended(terminal.start, deliveredCentiUnits / 100.0, terminal.duration, false, pumpId, serialNumber())
        if (!extendedAccountingMatches(pumpId, terminal.start, deliveredCentiUnits / 100.0, terminal.duration, serialNumber())) {
            return uncertainExtended(YpsoBolusMessage.STOPPED_AMOUNT_NOT_SAVED, "post-cancel extended bolus accounting was rejected", cancel = true)
        }
        controller.confirmTerminal(
            deliveredCentiUnits,
            terminal.end,
            sequence,
            pumpId,
            cancelled = deliveredCentiUnits < attempt.requestedCentiUnits,
        )
        return Result.Cancelled(deliveredCentiUnits / 100.0)
    }

    private fun extendedAccountingMatches(pumpId: Long, timestamp: Long, amount: Double, duration: Long, serial: String): Boolean =
        YpsoExtendedBolusAccounting.matches(records.extended(pumpId, serial), pumpId, timestamp, amount, duration, serial)

    /** Shows the warning for the latest dose, or a notified dose whose insulin can still act, while unresolved. */
    @Synchronized
    fun refreshWarning() {
        if (controller.expireAwaitingHistory().isNotEmpty()) {
            aapsLogger.warn(LTag.PUMP, "YpsoPump history did not confirm a notified bolus in time")
        }
        val at = now()
        // The latest dose, plus a notified dose history contradicted or never confirmed while its
        // insulin can still matter. Older unresolved doses stay in the journal for accounting only.
        showWarning(
            controller.currentAttempt()?.hasUnresolvedWarning == true ||
                controller.retainedAttempts().any {
                    it.hasUnresolvedWarning && it.notifiedFastSequence != null && at - (it.dispatchedAt ?: 0L) < NOTIFIED_WARNING_WINDOW_MS
                },
        )
    }

    // ---- history ----

    /** Whether a bolus history row is already accounted: a record carries it, or a journalled dose owns it. */
    fun ownsHistoryBolus(pumpSerial: String, pumpId: Long): Boolean =
        records.bolus(pumpId, pumpSerial) != null || journalledImmediate(journal.all(), pumpSerial, pumpId) != null

    /**
     * Reconciles the journal with a stable history scan before it is ingested: repairs earlier imports,
     * resolves the current dose from its terminal row, and holds that row while it is still being
     * matched to its dose.
     */
    fun reconcileHistory(pumpSerial: String, generation: String, reboot: Int, snapshot: YpsoHistorySnapshot): HistoryPass {
        repairJournalledAccounting(pumpSerial)
        reconcileAttempt(pumpSerial, generation, reboot, snapshot)
        refreshWarning()
        // A failure before the atomic replace keeps every dose, one after it the pruned journal; ingestion goes on.
        runCatching { forgetSettled(pumpSerial) }
            .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump bolus journal was not pruned: ${it.message}") }
        val attempt = controller.currentAttempt()
        val terminalSequence = when (attempt?.shape) {
            YpsoBolusShape.IMMEDIATE -> attempt.pumpFastSequence
            YpsoBolusShape.EXTENDED, YpsoBolusShape.COMBINED -> attempt.pumpSlowSequence
            null -> null
        }
        val terminalKinds = when (attempt?.shape) {
            YpsoBolusShape.IMMEDIATE -> setOf(YpsoHistoryKind.IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED)
            YpsoBolusShape.EXTENDED -> setOf(YpsoHistoryKind.DELAYED_BOLUS_COMPLETED)
            YpsoBolusShape.COMBINED -> setOf(YpsoHistoryKind.COMBINED_BOLUS_COMPLETED)
            null -> emptySet()
        }
        if (attempt?.holdsTerminalRow(now(), OBSERVATION_WINDOW_MS, EXTENDED_RECONCILIATION_MARGIN_MS) == true &&
            terminalSequence != null && snapshot.rowsNewestFirst.any {
                it.sequence == terminalSequence && YpsoHistoryClassifier.classify(it).kind in terminalKinds
            }
        ) return HistoryPass.Hold
        val retained = controller.retainedAttempts()
        return HistoryPass.Proceed { pumpId ->
            val owner = if (attempt?.pumpHistoryId == pumpId) attempt
            else runCatching { journalledImmediate(retained, pumpSerial, pumpId) }.getOrNull()
            owner?.treatment?.toRecordType() ?: BS.Type.NORMAL
        }
    }

    /** The one journalled immediate dose a history row belongs to; used for import and repair alike. */
    private fun journalledImmediate(attempts: List<YpsoBolusAttempt>, pumpSerial: String, pumpId: Long): YpsoBolusAttempt? {
        val immediate = attempts.filter { it.shape == YpsoBolusShape.IMMEDIATE && it.pumpSerial == pumpSerial }
        val proven = immediate.filter { it.accountingPumpId == pumpId }
        check(proven.size <= 1) { "multiple bolus attempts claim the same pump history identity" }
        proven.singleOrNull()?.let { return it }
        // A notified sequence is weaker evidence: when two doses name the same row, neither owns it.
        return immediate.filter { it.historyAccountingId == pumpId }.singleOrNull()
    }

    /**
     * The history row a journalled dose accounts to: its status-proven identity, else the sequence the
     * pump notified for it. Only the proven one attributes the command; both link its provisional record.
     */
    private val YpsoBolusAttempt.historyAccountingId: Long?
        get() = accountingPumpId ?: notifiedAccountingPumpId.takeIf { pumpFastSequence == null }

    /**
     * Links the provisional record for this dose to the pump identity of its terminal history row, so
     * the authoritative amount updates that record rather than inserting a second one. A row larger than
     * the request is another dose: the pump cannot deliver more than was programmed.
     */
    fun bindProvisional(pumpSerial: String, pumpId: Long, timestamp: Long, amount: Double, type: BS.Type) {
        val attempt = journalledImmediate(journal.all(), pumpSerial, pumpId) ?: return
        // Linked only by a notified sequence, now or on a replay after it was confirmed.
        if (attempt.pumpFastSequence == null) {
            bindNotified(attempt, pumpSerial, pumpId, timestamp, Math.round(amount * 100).toInt())
            return
        }
        if (Math.round(amount * 100) > attempt.requestedCentiUnits) {
            aapsLogger.error(LTag.PUMP, "YpsoPump history row $pumpId exceeds bolus request ${attempt.requestId}; not merged")
            return
        }
        records.syncWithTemporaryId(
            BS(
                timestamp = timestamp, amount = amount, type = type,
                ids = IDs(temporaryId = provisionalTemporaryId(attempt), pumpId = pumpId, pumpType = PumpType.YPSOPUMP, pumpSerial = pumpSerial),
            ),
            type,
        )
    }

    /**
     * Merges a dose whose only link to its history row is the sequence the pump notified for it. The
     * link is inferred, not proven, so merging two records refuses when either was removed: carrying a
     * removal across an inferred link could erase another dose. What is refused keeps both records,
     * counting insulin high, and shows the warning. A provisional record the user removed before its
     * row was imported takes the row's pump ID and stays removed, so the import cannot reinstate it.
     */
    private fun bindNotified(attempt: YpsoBolusAttempt, pumpSerial: String, pumpId: Long, timestamp: Long, centiUnits: Int) {
        if (centiUnits > attempt.requestedCentiUnits) {
            controller.rejectNotified(attempt.requestId, "history row $pumpId delivered more than this bolus requested")
            refreshWarning()
            return
        }
        val treatment = attempt.treatment.toRecordType()
        val binding = records.bindNotified(
            BS(
                timestamp = timestamp, amount = centiUnits / 100.0, type = treatment,
                ids = IDs(temporaryId = provisionalTemporaryId(attempt), pumpId = pumpId, pumpType = PumpType.YPSOPUMP, pumpSerial = pumpSerial),
            ),
            treatment,
        )
        when (binding) {
            // The dose is confirmed only once one record carries the row: the merged one (still removed
            // if the user removed it), or the imported row alone when the provisional record was never
            // written (process death).
            PersistenceLayer.TempIdBinding.NO_RECORD -> Unit
            PersistenceLayer.TempIdBinding.BOUND,
            PersistenceLayer.TempIdBinding.PUMP_RECORD_ONLY ->
                if (attempt.outcome != YpsoBolusOutcome.COMPLETED && attempt.outcome != YpsoBolusOutcome.CANCELLED_PARTIAL) {
                    runCatching { controller.confirmNotifiedTerminal(attempt.requestId, centiUnits, timestamp, pumpId) }
                        .onFailure { aapsLogger.warn(LTag.PUMP, "YpsoPump notified bolus ${attempt.requestId} not confirmed: ${it.message}") }
                }
            PersistenceLayer.TempIdBinding.REFUSED ->
                controller.rejectNotified(attempt.requestId, "a record of this bolus was removed or belongs to another dose")
        }
        aapsLogger.info(LTag.PUMP, "YpsoPump notified bolus ${attempt.requestId} and history row $pumpId: $binding")
        refreshWarning()
    }

    /** Repairs earlier imports using journalled identities, never matching by dose or proximity. */
    private fun repairJournalledAccounting(pumpSerial: String) {
        repairNotifiedAccounting(pumpSerial)
        val attempts = journal.all().filter { it.pumpSerial == pumpSerial && it.accountingPumpId != null && it.dispatchedAt != null }
        if (attempts.isEmpty()) return
        val from = attempts.minOf { it.dispatchedAt!! }.coerceAtLeast(1L) - 60_000L
        val saved = records.bolusesFrom(from, pumpSerial)
        for (attempt in attempts) {
            val id = attempt.accountingPumpId!!
            check(attempts.count { it.accountingPumpId == id } == 1) { "ambiguous journalled pump bolus identity" }
            // Notified doses are repaired above, under the rules for an inferred link.
            if (attempt.shape == YpsoBolusShape.IMMEDIATE && attempt.pumpFastSequence == null) continue
            if (attempt.shape == YpsoBolusShape.IMMEDIATE && saved.singleOrNull { it.ids.pumpId == id }
                    ?.let { Math.round(it.amount * 100) > attempt.requestedCentiUnits } == true) continue
            if (attempt.shape == YpsoBolusShape.IMMEDIATE) {
                val temporaryId = provisionalTemporaryId(attempt)
                val provisional = saved.singleOrNull { it.ids.temporaryId == temporaryId } ?: continue
                val imported = saved.singleOrNull { it.ids.pumpId == id } ?: continue
                if (provisional.id == imported.id) continue
                // The imported amount is already pump-confirmed. This transaction atomically merges
                // both records even if their timestamps predate the current driver activation.
                records.syncWithTemporaryId(imported.copy(ids = imported.ids.copy(temporaryId = temporaryId)), provisional.type)
                aapsLogger.info(LTag.PUMP, "YpsoPump merged journalled provisional bolus with pump event $id")
            } else {
                val existing = records.extended(id, pumpSerial) ?: continue
                val duration = extendedOverrun(attempt, existing) ?: continue
                records.correctExtended(existing.timestamp, existing.amount, duration, existing.isEmulatingTempBasal, id, pumpSerial)
            }
        }
    }

    /** The duration an extended record must be cut to because the pump stopped earlier, or null. */
    private fun extendedOverrun(attempt: YpsoBolusAttempt, existing: EB): Long? {
        if (attempt.cancelStoppedAt == null && attempt.blockTerminalAt == null) return null
        val delivered = Math.round(existing.amount * 100).toInt()
        val window = YpsoExtendedBolusAccounting.terminalWindow(attempt, delivered, now())
        // A terminal history row may already have supplied a shorter elapsed window.
        // The observed stop is an upper bound, not permission to extend that delivery again.
        return window.duration.takeIf { existing.duration > it }
    }

    /**
     * Forgets journalled doses that can no longer affect accounting, so the journal stays bounded. A
     * dose that never left the phone or was proven rejected carries none. A confirmed dose is settled
     * once ingestion has moved past its history row, so no scan offers that row again, and a record
     * carries the row with nothing left to repair. The journal itself keeps every dose still being
     * resolved and the evidence recovery needs.
     */
    private fun forgetSettled(pumpSerial: String) {
        val candidates = journal.forgettable()
        if (candidates.isEmpty()) return
        val ingestedThrough = historyCursor()?.identity?.takeIf { it.pumpSerial == pumpSerial }?.aapsPumpId ?: -1L
        val settled = candidates.filter { attempt ->
            val id = attempt.pumpHistoryId
            when {
                attempt.outcome in NO_INSULIN -> true
                attempt.outcome !in CONFIRMED || attempt.pumpSerial != pumpSerial || id == null || ingestedThrough <= id -> false
                attempt.shape == YpsoBolusShape.IMMEDIATE -> records.bolus(id, pumpSerial)?.let { provisionalSettled(attempt, it) } == true
                else -> records.extended(id, pumpSerial)?.let { extendedOverrun(attempt, it) == null } == true
            }
        }
        val forgotten = journal.forget(settled)
        if (forgotten > 0) aapsLogger.info(LTag.PUMP, "YpsoPump bolus journal forgot $forgotten settled doses")
    }

    /**
     * Whether the record carrying a dose's row proves its provisional record needs the journal no more:
     * the row's record took over the provisional identity (a merge keeps a removal of either), or the
     * row is larger than the request and so is never merged. Absence of a provisional record is not
     * proof: removed records can be invisible to lookups, so such a dose stays journalled.
     */
    private fun provisionalSettled(attempt: YpsoBolusAttempt, imported: BS): Boolean =
        imported.ids.temporaryId == provisionalTemporaryId(attempt) ||
            attempt.pumpFastSequence != null && Math.round(imported.amount * 100) > attempt.requestedCentiUnits

    /**
     * A notified dose whose history row was imported before it could be linked (a scan interrupted
     * between import and link) still has its own record; link the two now, under the same rules.
     */
    private fun repairNotifiedAccounting(pumpSerial: String) {
        val open = controller.retainedAttempts().filter {
            it.pumpSerial == pumpSerial && it.pumpFastSequence == null && it.notifiedAccountingPumpId != null &&
                it.outcome in setOf(
                YpsoBolusOutcome.POSSIBLY_APPLIED, YpsoBolusOutcome.ACCEPTED_UNVERIFIED,
                YpsoBolusOutcome.AWAITING_HISTORY, YpsoBolusOutcome.UNRESOLVED,
            )
        }
        for (attempt in open) {
            val id = checkNotNull(attempt.notifiedAccountingPumpId)
            // The same choice as at import: a row two doses name, or one a proven dose owns, is neither's.
            if (runCatching { journalledImmediate(journal.all(), pumpSerial, id) }.getOrNull()?.requestId != attempt.requestId) continue
            val imported = records.bolus(id, pumpSerial) ?: continue
            bindNotified(attempt, pumpSerial, id, imported.timestamp, Math.round(imported.amount * 100).toInt())
        }
    }

    private fun reconcileAttempt(pumpSerial: String, generation: String, reboot: Int, snapshot: YpsoHistorySnapshot) {
        val attempt = controller.currentAttempt() ?: return
        if (!attempt.awaitsReconciliation) return
        // A notified dose is linked by its exact sequence when history imports that row.
        if (attempt.shape == YpsoBolusShape.IMMEDIATE && attempt.pumpFastSequence == null && attempt.notifiedAccountingPumpId != null) return
        if (attempt.pumpSerial != pumpSerial || attempt.sessionGeneration != generation ||
            attempt.baseline.pumpReboot != reboot
        ) {
            controller.markUnresolved("pump identity epoch changed before terminal bolus reconciliation")
            return
        }
        val cursor = YpsoHistoryCursor(
            YpsoEventIdentity(pumpSerial, (attempt.baseline.historyPumpId ushr 32).toInt(), attempt.baseline.historyPumpId and 0xffffffffL),
            YpsoHistoryFingerprint(attempt.baseline.historyFingerprintHigh, attempt.baseline.historyFingerprintLow),
            reboot.toLong(),
        )
        val stable = YpsoHistoryReconciler.reconcile(cursor, snapshot) as? YpsoHistoryReconciliation.Stable ?: return
        if (attempt.shape != YpsoBolusShape.IMMEDIATE) {
            reconcileExtended(attempt, pumpSerial, YpsoPumpLocalTime.rowTimes(snapshot), stable)
            return
        }
        val status = link.status()?.let {
            YpsoImmediateBolusStatus(it.fastSequence, it.bolusStatusCode, cents(it.totalProgrammedUnits), cents(it.deliveredUnits))
        }
        when (val resolution = YpsoImmediateBolusReconciler.reconcile(attempt, status, stable.stateUpdates + stable.newEventsOldestFirst)) {
            is YpsoImmediateBolusReconciliation.AttemptCompleted -> {
                val timestamp = YpsoPumpLocalTime.rowTimes(snapshot).at(resolution.event.entry) ?: return
                controller.confirmTerminal(
                    resolution.amountCentiUnits,
                    timestamp,
                    resolution.event.identity.sequence,
                    resolution.event.identity.aapsPumpId,
                    cancelled = resolution.amountCentiUnits < attempt.requestedCentiUnits,
                )
            }
            is YpsoImmediateBolusReconciliation.Unresolved -> {
                // A dose that proved neither its status identity nor a notified sequence is never merged
                // onto a row by amount or time alone: that row may be another dose. Both records stay,
                // counting insulin high, and the warning asks the operator to check.
                if (resolution.reason != YpsoImmediateBolusReconciliation.Reason.NO_COMPATIBLE_HISTORY)
                    controller.markUnresolved("history reconciliation: ${resolution.reason}")
            }
            is YpsoImmediateBolusReconciliation.ConfirmedInsulin -> Unit
        }
    }

    private fun reconcileExtended(attempt: YpsoBolusAttempt, pumpSerial: String, times: YpsoPumpLocalTime.RowTimes, stable: YpsoHistoryReconciliation.Stable) {
        when (val resolution = YpsoExtendedBolusReconciler.reconcile(attempt, stable.stateUpdates + stable.newEventsOldestFirst)) {
            is YpsoExtendedBolusReconciliation.AttemptCompleted -> {
                val historyTimestamp = times.at(resolution.event.entry) ?: return
                val observedWindow = YpsoExtendedBolusAccounting.terminalWindow(attempt, resolution.amountCentiUnits, now())
                val terminal = if (resolution.event.entry.eventType == 3)
                    observedWindow.copy(
                        duration = YpsoExtendedBolusAccounting.squareHistoryDuration(resolution.event.entry.value2, observedWindow.duration),
                    )
                else observedWindow
                val pumpId = resolution.event.identity.aapsPumpId
                val units = resolution.amountCentiUnits / 100.0
                records.correctExtended(terminal.start, units, terminal.duration, false, pumpId, pumpSerial)
                if (!extendedAccountingMatches(pumpId, terminal.start, units, terminal.duration, pumpSerial)) {
                    controller.markUnresolved("terminal extended bolus accounting was rejected")
                    refreshWarning()
                    return
                }
                controller.confirmTerminal(
                    resolution.amountCentiUnits,
                    terminal.end.coerceAtLeast(historyTimestamp),
                    resolution.event.identity.sequence,
                    pumpId,
                    cancelled = resolution.amountCentiUnits < attempt.requestedCentiUnits,
                )
            }
            is YpsoExtendedBolusReconciliation.Unresolved ->
                if (resolution.reason == YpsoExtendedBolusReconciliation.Reason.NO_COMPATIBLE_HISTORY) recoverRunningExtended(attempt, pumpSerial)
                else controller.markUnresolved("extended history reconciliation: ${resolution.reason}")
        }
    }

    /** Repairs restart/crash gaps after durable slow-block identity proof but before the record was saved. */
    private fun recoverRunningExtended(attempt: YpsoBolusAttempt, pumpSerial: String) {
        val start = attempt.dispatchedAt ?: return
        val duration = attempt.durationMinutes * 60_000L
        // A dispatch that lost its link before the slow sequence was journalled has no identity to
        // repair under. Its insulin is not lost: the terminal history row is ingested on its own
        // evidence. Throwing here would abort every later ingestion instead.
        val sequence = attempt.pumpSlowSequence ?: return
        val pumpId = YpsoBolusPumpIdentity.of(attempt.baseline.historyPumpId, sequence) ?: return
        if (extendedAccountingMatches(pumpId, start, attempt.requestedUnits, duration, pumpSerial)) return
        val status = link.status() ?: return
        if (status.extendedSequence != attempt.pumpSlowSequence ||
            status.extendedStatusCode !in setOf(BolusCommand.STATUS_DELIVERING, BolusCommand.STATUS_MIXED_DELIVERING) ||
            cents(status.extendedTotalUnits) != attempt.requestedCentiUnits ||
            status.extendedMinutesTotal != attempt.durationMinutes ||
            cents(status.comboImmediateTotalUnits) != attempt.immediateCentiUnits
        ) return
        val synced = records.syncExtended(start, attempt.requestedUnits, duration, pumpId, pumpSerial)
        if (!synced && !extendedAccountingMatches(pumpId, start, attempt.requestedUnits, duration, pumpSerial)) {
            controller.markUnresolved("active extended bolus accounting recovery was rejected")
            refreshWarning()
        }
    }

    /** Stable per-attempt identity so the provisional record can be found again after a restart. */
    private fun provisionalTemporaryId(attempt: YpsoBolusAttempt): Long =
        attempt.requestId.hashCode().toLong() and 0xffffffffL or ((attempt.createdAt / 1000L) shl 32)

    private fun cents(units: Double): Int = Math.round(units * 100.0).toInt()

    companion object {
        /** How long an immediate dose keeps first claim on its terminal evidence after dispatch. */
        const val OBSERVATION_WINDOW_MS = YpsoImmediateBolusController.OBSERVATION_WINDOW_MS
        const val EXTENDED_RECONCILIATION_MARGIN_MS = YpsoImmediateBolusController.EXTENDED_RECONCILIATION_MARGIN_MS
        /** How long a command waits for the pump to report the end of a dose or a cancellation. */
        private const val TERMINAL_WAIT_MS = 90_000L
        private const val RECONNECT_MS = 10_000L
        /** A notified dose's warning stays while its insulin can still act (typical insulin duration). */
        private const val NOTIFIED_WARNING_WINDOW_MS = 6 * 60 * 60_000L
        private val CONFIRMED = setOf(YpsoBolusOutcome.COMPLETED, YpsoBolusOutcome.CANCELLED_PARTIAL)
        private val NO_INSULIN = setOf(YpsoBolusOutcome.NOT_SENT, YpsoBolusOutcome.PROVEN_REJECTED)
    }
}

internal fun YpsoBolusTreatment.toRecordType(): BS.Type = when (this) {
    YpsoBolusTreatment.NORMAL -> BS.Type.NORMAL
    YpsoBolusTreatment.SMB -> BS.Type.SMB
    YpsoBolusTreatment.PRIME -> BS.Type.PRIMING
}
