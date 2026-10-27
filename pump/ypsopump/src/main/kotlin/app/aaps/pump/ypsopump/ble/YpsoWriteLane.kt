package app.aaps.pump.ypsopump.ble

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.ypsopump.ble.YpsoPumpLink.Companion.OP_TIMEOUT_MS
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single path for one [Category] of encrypted pump writes. It owns the category's
 * [YpsoWriteAccounting], its serialized transport, the frames it sends on the link and the rule for
 * when a write hands the link back. Accounting is rebuilt whenever the link opens another session;
 * the transport lives as long as the lane.
 */
internal class YpsoWriteLane(
    val category: Category,
    private val link: Link,
    private val crypto: SessionCrypto,
    private val aapsLogger: AAPSLogger,
) {
    /** What a lane needs from the link it writes on. */
    interface Link {
        val session: PumpSession?
        val scheduler: YpsoLinkScheduler

        /**
         * Sends one frame of [lease]'s write; only that lease's exact frame is admitted at the GATT boundary.
         * [mayDispatch] is checked last, right before the frame is handed to GATT; false withholds it.
         */
        fun writeFrame(epoch: YpsoConnectionEpoch, lease: YpsoLinkLease, value: ByteArray, mayDispatch: () -> Boolean = { true }): Boolean
        fun release(lease: YpsoLinkLease)

        /** Retires the open session's unresolved reservation, keeping its counter as the high-water mark. */
        fun recoverInterruptedWrite()
    }

    enum class Category(
        val operation: YpsoLinkOperation,
        /** The write belongs to a read that holds the link across many selector writes and reads. */
        val selector: Boolean,
        val deadlineMs: Long,
        /** Why a therapy write cannot start while this lane still has a write in flight. */
        val busy: String,
    ) {
        PROFILE_SELECTOR(YpsoLinkOperation.PROFILE, true, OP_TIMEOUT_MS, "another pump setting is still being written"),
        HISTORY_SELECTOR(YpsoLinkOperation.HISTORY, true, OP_TIMEOUT_MS, "pump history is still being read"),
        BOLUS(YpsoLinkOperation.BOLUS, false, 30_000, "a previous bolus command is still being sent"),
        TBR(YpsoLinkOperation.TBR, false, OP_TIMEOUT_MS, "a previous temporary basal command is still being sent"),
        ;

        val characteristic: UUID get() = checkNotNull(operation.writeUuid)
        val purpose: YpsoRemoteWrite get() = checkNotNull(operation.writePurpose)

        /**
         * Whether a write that ended in [outcome] still holds the link. A selector write never hands it
         * back; the read that acquired it does. A therapy write that keeps the link holds it for its
         * same-link status read, until [reconcile] resolves it or [recordUnresolved] gives up on it.
         */
        fun keepsLink(outcome: YpsoWriteOutcome): Boolean = when (this) {
            PROFILE_SELECTOR, HISTORY_SELECTOR -> true
            // Only a command that may have reached the pump waits for its status read.
            BOLUS -> outcome is YpsoWriteOutcome.AcceptedUnverified || outcome is YpsoWriteOutcome.PossiblyApplied
            // Every sent command is resolved by a status read on the same link. Only an unsent command,
            // or one the pump provably rejected, owns nothing.
            TBR -> outcome !is YpsoWriteOutcome.NotSent && outcome !is YpsoWriteOutcome.ProvenRejected
        }

        /**
         * Whether a write left unresolved retires its reservation. The TBR journal owns that therapy
         * decision, so later commands may proceed; a bolus reservation stays for the bolus journal.
         */
        val retiresUnresolvedWrite: Boolean get() = this == TBR
    }

    /**
     * One operation's right to write on a lane: the epoch it runs on, the link lease it holds and the id
     * that names it in write evidence. [lowerBoundRecovery] marks a history selector probe from a durable
     * lower bound, which is accounted separately from ordinary selection.
     */
    class Owner(
        val epoch: YpsoConnectionEpoch,
        val lease: YpsoLinkLease,
        val connectionId: String = UUID.randomUUID().toString(),
        val lowerBoundRecovery: Boolean = false,
    ) {
        internal val accounting: YpsoWriteAccounting.Owner get() = YpsoWriteAccounting.Owner(epoch.connection, connectionId, epoch.token)
    }

    private class Accounts(val session: PumpSession, val ordinary: YpsoWriteAccounting, val lowerBoundRecovery: YpsoWriteAccounting?)

    private val transport = YpsoSerializedWriteTransport(
        { task, delayMs -> link.scheduler.schedule(task, delayMs) },
        { task -> link.scheduler.cancel(task) },
    )
    private val lock = Any()
    private var accounts: Accounts? = null

    private fun accounts(session: PumpSession): Accounts = synchronized(lock) {
        accounts?.takeIf { it.session === session } ?: Accounts(
            session,
            // A selector is resolved only by same-link read-back from POSSIBLY_SENT.
            YpsoWriteAccounting(session, crypto, transport, persistTransportAck = !category.selector),
            if (category != Category.HISTORY_SELECTOR) null else YpsoWriteAccounting(
                session,
                crypto,
                transport,
                reservationPolicy = { owner, transaction, intent -> session.reserveLowerBoundHistoryRecovery(owner.token, transaction, intent) },
                // An ambiguous recovery probe must remain blocking. Only confirmed pump error 139 may advance
                // the exponential search; reconnect is not evidence that an uncertain selector was rejected.
                retireInterruptedWrite = false,
            ),
        ).also { accounts = it }
    }

    private fun accounting(owner: Owner, session: PumpSession? = link.session): YpsoWriteAccounting {
        val current = accounts(checkNotNull(session))
        return if (owner.lowerBoundRecovery) checkNotNull(current.lowerBoundRecovery) else current.ordinary
    }

    /**
     * Sends [plaintext] as one write of [owner]. [onOutcome] hears the transport outcome once; later
     * reconciliation answers through [reconcile]. Returns false when the write did not start, after
     * [onOutcome] has heard why.
     */
    fun write(
        owner: Owner,
        writeId: String,
        plaintext: ByteArray,
        firmware: String?,
        beforeDispatch: (PumpSession.Reservation) -> Unit = {},
        /** Checked as each frame is handed to GATT; false withholds it, so an unsent first frame is a proven not-sent. */
        mayDispatch: () -> Boolean = { true },
        onOutcome: (YpsoWriteOutcome) -> Unit,
    ): Boolean {
        require(owner.lease.operation == category.operation) { "${owner.lease.operation} cannot write on the $category lane" }
        require(!owner.lowerBoundRecovery || category == Category.HISTORY_SELECTOR) { "only history selection recovers a lower bound" }
        // Reconciliation and teardown republish through the same callback; each still settles the link.
        val heard = AtomicBoolean(false)
        val settle: (YpsoWriteOutcome) -> Unit = { outcome ->
            if (!category.keepsLink(outcome)) link.release(owner.lease)
            if (heard.compareAndSet(false, true)) onOutcome(outcome)
        }
        val session = link.session
        val started = refusal(session, owner, plaintext)?.let { (layer, detail) ->
            settle(YpsoWriteOutcome.NotSent(writeId, null, YpsoWriteFailure(layer, category.characteristic, firmware, detail = detail)))
            false
        } ?: accounting(owner, session).execute(
            YpsoWriteAccounting.Request(
                writeId = writeId,
                owner = owner.accounting,
                category = category.purpose,
                characteristic = category.characteristic,
                plaintext = plaintext,
                firmware = firmware,
                deadlineMs = category.deadlineMs,
                beforeDispatch = beforeDispatch,
                dispatch = { frame -> link.writeFrame(owner.epoch, owner.lease, frame, mayDispatch) },
                onOutcome = settle,
            ),
        )
        // A therapy write that never started owns nothing, even when its journal could not record it.
        if (!started && !category.selector) link.release(owner.lease)
        return started
    }

    private fun refusal(session: PumpSession?, owner: Owner, plaintext: ByteArray): Pair<YpsoWriteFailure.Layer, String>? {
        if (category.selector && !YpsoWritePolicy.allowsCharacteristic(category.purpose, category.characteristic, plaintext, byteArrayOf(), false)) {
            return YpsoWriteFailure.Layer.POLICY to "selector value is outside the $category allowlist"
        }
        val record = session?.snapshot()
        if (record?.reboot == null || record.read == null) return YpsoWriteFailure.Layer.SESSION to "durable pump session counters are unavailable"
        // A selector never starts behind another write of its lane, even one parked for reconciliation.
        if (category.selector && transport.hasUnresolvedWrite()) return YpsoWriteFailure.Layer.SESSION to "a previous $category write is unresolved"
        if (owner.lowerBoundRecovery &&
            (record.write == null || record.writeBootstrapState != PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND)
        ) return YpsoWriteFailure.Layer.SESSION to "durable lower-bound recovery is unavailable"
        return null
    }

    /**
     * Resolves [writeId] from same-link semantic [evidence]. A rejection is one measured on the final
     * frame and confirmed by status, so its counter is treated as consumed. Returns whether the write
     * was resolved; a resolved therapy write hands the link back.
     */
    fun reconcile(owner: Owner, writeId: String, evidence: YpsoSemanticEvidence, evidenceHash: String, detail: String): Boolean {
        require(evidence != YpsoSemanticEvidence.UNKNOWN) { "use recordUnresolved" }
        val resolution = if (evidence == YpsoSemanticEvidence.ACCEPTED) PumpSession.WriteResolution.ACCEPTED else PumpSession.WriteResolution.REJECTED_COUNTER_CONSUMED
        val resolved = accounting(owner).reconcile(writeId, owner.accounting, evidence, resolution, evidenceHash, detail)
        if (resolved && !category.selector) link.release(owner.lease)
        return resolved
    }

    /**
     * Ends a therapy write whose effect stays unproven: records [evidenceHash] against its reservation
     * and hands the link back. See [Category.retiresUnresolvedWrite] for what happens to the reservation.
     */
    fun recordUnresolved(owner: Owner, writeId: String, evidenceHash: String, detail: String): Boolean {
        require(!category.selector) { "a selector write is never left unresolved by its read" }
        try {
            return accounting(owner).reconcile(writeId, owner.accounting, YpsoSemanticEvidence.UNKNOWN, null, evidenceHash, detail)
        } finally {
            if (category.retiresUnresolvedWrite) {
                runCatching {
                    accounting(owner).ownerDisconnected(owner.epoch.connection, detail)
                    link.recoverInterruptedWrite()
                }.onFailure { aapsLogger.warn(LTag.PUMP, "YpsoPump could not retire unresolved $category write: ${it.message}") }
            }
            link.release(owner.lease)
        }
    }

    /** [connection] is gone: undispatched writes on it become not-sent and dispatched ones durably uncertain. */
    fun ownerDisconnected(connection: YpsoGattConnection, detail: String) {
        val current = synchronized(lock) { accounts } ?: return
        current.ordinary.ownerDisconnected(connection, detail)
        current.lowerBoundRecovery?.ownerDisconnected(connection, detail)
    }

    fun onCharacteristicWrite(connection: YpsoGattConnection, status: Int) =
        transport.onCharacteristicWrite(connection, category.characteristic, status)

    /** Whether a write is still in flight on [connection]; see [YpsoSerializedWriteTransport.hasUnresolvedWriteOn]. */
    fun hasUnresolvedWriteOn(connection: YpsoGattConnection): Boolean = transport.hasUnresolvedWriteOn(connection)

    /** Whether no write, on any connection, still occupies this lane. */
    fun isIdle(): Boolean = transport.isIdle()
}
