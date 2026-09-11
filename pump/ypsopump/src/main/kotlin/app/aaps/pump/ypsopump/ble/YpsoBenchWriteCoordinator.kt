package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import java.security.MessageDigest
import java.util.UUID

/**
 * Non-therapy whole-write boundary. Policy and readiness are checked before counter reservation or
 * encryption. A reservation is persisted before encryption, POSSIBLY_SENT before platform dispatch,
 * and remains durable until measured semantic/counter evidence reconciles it.
 */
internal class YpsoBenchWriteCoordinator(
    private val session: PumpSession,
    private val crypto: SessionCrypto,
    private val readiness: YpsoCommandReadiness,
    private val transport: YpsoSerializedWriteTransport,
) {
    data class Owner(
        val gatt: Any,
        val connectionId: String,
        val token: PumpSession.Token,
    ) {
        fun readinessOwner() = YpsoCommandReadiness.Owner(gatt, connectionId, token.generation)
    }

    data class Reconciliation(
        val semantic: YpsoSemanticEvidence,
        val counter: PumpSession.WriteResolution?,
        val evidenceHash: String,
        val detail: String,
    )

    private data class Pending(
        val gatt: Any,
        val connectionId: String,
        val generation: String,
        val reservationId: String,
    )

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()

    fun writeSelector(
        writeId: String,
        owner: Owner,
        category: YpsoRemoteWrite,
        characteristic: UUID,
        plaintext: ByteArray,
        firmware: String?,
        deadlineMs: Long,
        dispatch: (ByteArray) -> Boolean,
        onOutcome: (YpsoWriteOutcome) -> Unit,
    ): Boolean {
        require(category == YpsoRemoteWrite.HISTORY_SELECTOR || category == YpsoRemoteWrite.SETTINGS_SELECTOR)
        val artifact = YpsoArtifactPolicy.NON_THERAPY_BENCH
        if (!YpsoWritePolicy.allowsCharacteristic(artifact, category, characteristic, plaintext, byteArrayOf(), false)) {
            onOutcome(
                notSent(
                    writeId,
                    characteristic,
                    firmware,
                    YpsoWriteFailure.Layer.POLICY,
                    "selector is outside the reviewed bench allowlist",
                ),
            )
            return false
        }
        val record = session.snapshot()
        val counterCertain =
            record?.reboot != null &&
                record.read != null &&
                record.write != null &&
                (record.reservation == null || record.reservation.phase == PumpSession.Phase.VERIFIED)
        val ready = readiness.snapshot(owner.readinessOwner(), counterCertain, setupRequired = true)
        if (!ready.commandReady) {
            onOutcome(notSent(writeId, characteristic, firmware, YpsoWriteFailure.Layer.READINESS, checkNotNull(ready.reason)))
            return false
        }
        if (transport.hasUnresolvedWrite()) {
            onOutcome(
                notSent(writeId, characteristic, firmware, YpsoWriteFailure.Layer.SESSION, "another whole write awaits reconciliation"),
            )
            return false
        }

        val transaction =
            runCatching { session.begin(owner.token) }.getOrElse {
                onOutcome(notSent(writeId, characteristic, firmware, YpsoWriteFailure.Layer.SESSION, it.message ?: "session unavailable"))
                return false
            }
        var transactionOpen = true

        fun finish() {
            if (!transactionOpen) return
            transactionOpen = false
            session.finish(owner.token, transaction)
        }
        val reservation =
            runCatching {
                session.reserve(
                    owner.token,
                    transaction,
                    PumpSession.WriteIntent(
                        writeId,
                        characteristic.toString(),
                        category.name,
                        MessageDigest.getInstance("SHA-256").digest(plaintext).joinToString("") { "%02x".format(it) },
                    ),
                )
            }.getOrElse {
                finish()
                onOutcome(
                    notSent(writeId, characteristic, firmware, YpsoWriteFailure.Layer.SESSION, it.message ?: "counter reservation failed"),
                )
                return false
            }
        val encrypted =
            runCatching { session.encryptReserved(owner.token, transaction, plaintext, crypto) }.getOrElse {
                runCatching { session.markNotSent(owner.token, transaction) }
                finish()
                onOutcome(
                    notSent(
                        writeId,
                        characteristic,
                        firmware,
                        YpsoWriteFailure.Layer.ENCRYPTION,
                        it.message ?: "encryption failed",
                        reservation.counter,
                    ),
                )
                return false
            }
        val frames =
            runCatching { YpsoFraming.chunkPayload(encrypted) }.getOrElse {
                encrypted.fill(0)
                runCatching { session.markNotSent(owner.token, transaction) }
                finish()
                onOutcome(
                    notSent(
                        writeId,
                        characteristic,
                        firmware,
                        YpsoWriteFailure.Layer.ENCRYPTION,
                        it.message ?: "framing failed",
                        reservation.counter,
                    ),
                )
                return false
            }
        encrypted.fill(0)
        runCatching { session.advance(owner.token, transaction, PumpSession.Phase.POSSIBLY_SENT) }.getOrElse {
            finish()
            onOutcome(
                YpsoWriteOutcome.PossiblyApplied(
                    writeId,
                    reservation.counter,
                    failure(characteristic, firmware, YpsoWriteFailure.Layer.SESSION, it.message ?: "could not persist dispatch boundary"),
                ),
            )
            return false
        }
        synchronized(lock) {
            pending[writeId] = Pending(owner.gatt, owner.connectionId, owner.token.generation, reservation.id)
        }

        val started =
            transport.start(
                YpsoSerializedWriteTransport.Request(
                    writeId = writeId,
                    owner = YpsoSerializedWriteTransport.Owner(owner.gatt, owner.connectionId, owner.token.generation),
                    category = category,
                    characteristic = characteristic,
                    counter = reservation.counter,
                    firmware = firmware,
                    frames = frames,
                    deadlineMs = deadlineMs,
                    dispatch = dispatch,
                    onOutcome = { outcome ->
                        val delivered =
                            when (outcome) {
                                is YpsoWriteOutcome.NotSent ->
                                    runCatching {
                                        session.markNotSent(owner.token, transaction)
                                        synchronized(lock) { pending.remove(writeId) }
                                        outcome
                                    }.getOrElse {
                                        YpsoWriteOutcome.PossiblyApplied(
                                            writeId,
                                            reservation.counter,
                                            failure(
                                                characteristic,
                                                firmware,
                                                YpsoWriteFailure.Layer.SESSION,
                                                it.message ?: "not-sent rollback failed",
                                            ),
                                        )
                                    }
                                is YpsoWriteOutcome.AcceptedUnverified ->
                                    runCatching {
                                        session.advance(owner.token, transaction, PumpSession.Phase.ACKED)
                                        outcome
                                    }.getOrElse {
                                        YpsoWriteOutcome.PossiblyApplied(
                                            writeId,
                                            reservation.counter,
                                            failure(
                                                characteristic,
                                                firmware,
                                                YpsoWriteFailure.Layer.SESSION,
                                                it.message ?: "ACK persistence failed",
                                            ),
                                        )
                                    }
                                else -> outcome
                            }
                        finish()
                        onOutcome(delivered)
                    },
                ),
            )
        if (!started) {
            runCatching { session.markNotSent(owner.token, transaction) }
            synchronized(lock) { pending.remove(writeId) }
            finish()
            onOutcome(
                notSent(
                    writeId,
                    characteristic,
                    firmware,
                    YpsoWriteFailure.Layer.SESSION,
                    "transport already owns a whole write",
                    reservation.counter,
                ),
            )
        }
        return started
    }

    /** No retry is performed. Rejection requires an explicit measured counter-consumption result. */
    fun reconcile(
        writeId: String,
        owner: Owner,
        reconciliation: Reconciliation,
    ): Boolean {
        val operation = synchronized(lock) { pending[writeId] } ?: return false
        require(operation.gatt === owner.gatt) { "Recovery owner belongs to another GATT" }
        require(operation.connectionId == owner.connectionId) { "Recovery owner belongs to another connection" }
        require(operation.generation == owner.token.generation) { "Recovery owner belongs to another key generation" }
        validateReconciliation(reconciliation)
        transport.reconcile(writeId, reconciliation.semantic, reconciliation.detail) { terminal ->
            if (terminal) {
                session.resolveWrite(
                    owner.token,
                    operation.reservationId,
                    checkNotNull(reconciliation.counter),
                    reconciliation.evidenceHash,
                    reconciliation.detail,
                )
                synchronized(lock) { pending.remove(writeId) }
            } else {
                session.recordUnresolvedWriteEvidence(
                    owner.token,
                    operation.reservationId,
                    reconciliation.evidenceHash,
                    reconciliation.detail,
                )
            }
        }
        return true
    }

    /** Live ownership is gone after disconnect; the durable reservation remains the recovery source. */
    fun ownerDisconnected(
        gatt: Any,
        detail: String,
    ): Boolean {
        val ownedWrite = transport.ownsGatt(gatt)
        transport.cancelOwner(gatt, detail)
        releaseOwner(gatt)
        return ownedWrite
    }

    /** The acknowledged write remains durable, but the closed GATT must not stay reachable in memory. */
    fun releaseOwner(gatt: Any) {
        transport.releaseOwner(gatt)
        synchronized(lock) { pending.entries.removeAll { it.value.gatt === gatt } }
    }

    /** Recover and reconcile a durable pending selector after process death. */
    fun reconcilePersisted(
        owner: Owner,
        writeId: String,
        reconciliation: Reconciliation,
    ): YpsoWriteOutcome? {
        validateReconciliation(reconciliation)
        val reservation = session.snapshot()?.reservation?.takeIf { it.operationId == writeId } ?: return null
        if (reservation.phase == PumpSession.Phase.RESERVED) {
            require(
                reconciliation.semantic == YpsoSemanticEvidence.REJECTED &&
                    reconciliation.counter == PumpSession.WriteResolution.REJECTED_COUNTER_NOT_CONSUMED,
            ) { "A pre-dispatch reservation is recoverable only as proven not sent" }
            session.recoverReservedNotSent(owner.token, writeId, reconciliation.evidenceHash, reconciliation.detail)
        } else {
            reconciliation.counter?.let {
                session.resolveWrite(owner.token, reservation.id, it, reconciliation.evidenceHash, reconciliation.detail)
            } ?: session.recordUnresolvedWriteEvidence(
                owner.token,
                reservation.id,
                reconciliation.evidenceHash,
                reconciliation.detail,
            )
        }
        val characteristic = UUID.fromString(checkNotNull(reservation.characteristic))
        return when (reconciliation.semantic) {
            YpsoSemanticEvidence.ACCEPTED -> YpsoWriteOutcome.Verified(writeId, reservation.counter, reconciliation.detail)
            YpsoSemanticEvidence.REJECTED ->
                YpsoWriteOutcome.ProvenRejected(
                    writeId,
                    reservation.counter,
                    YpsoWriteFailure(YpsoWriteFailure.Layer.RECONCILIATION, characteristic, null, detail = reconciliation.detail),
                )
            YpsoSemanticEvidence.UNKNOWN ->
                YpsoWriteOutcome.PossiblyApplied(
                    writeId,
                    reservation.counter,
                    YpsoWriteFailure(YpsoWriteFailure.Layer.RECONCILIATION, characteristic, null, detail = reconciliation.detail),
                )
        }
    }

    data class PendingWrite(
        val writeId: String,
        val counter: Long,
        val characteristic: UUID,
        val purpose: YpsoRemoteWrite,
        val phase: PumpSession.Phase,
        val payloadHash: String,
    )

    fun pendingWrite(): PendingWrite? =
        (session.snapshot() ?: session.activeRecord())?.reservation?.let { reservation ->
            PendingWrite(
                reservation.operationId ?: return@let null,
                reservation.counter,
                UUID.fromString(reservation.characteristic ?: return@let null),
                YpsoRemoteWrite.valueOf(reservation.purpose ?: return@let null),
                reservation.phase,
                reservation.payloadHash ?: return@let null,
            )
        }

    private fun validateReconciliation(reconciliation: Reconciliation) {
        require(reconciliation.evidenceHash.matches(Regex("[0-9a-f]{64}")))
        require(reconciliation.detail.isNotBlank() && reconciliation.detail.length <= 4096)
        when (reconciliation.semantic) {
            YpsoSemanticEvidence.ACCEPTED -> require(reconciliation.counter == PumpSession.WriteResolution.ACCEPTED)
            YpsoSemanticEvidence.REJECTED ->
                require(
                    reconciliation.counter == PumpSession.WriteResolution.REJECTED_COUNTER_CONSUMED ||
                        reconciliation.counter == PumpSession.WriteResolution.REJECTED_COUNTER_NOT_CONSUMED,
                )
            YpsoSemanticEvidence.UNKNOWN -> require(reconciliation.counter == null)
        }
    }

    private fun notSent(
        writeId: String,
        characteristic: UUID,
        firmware: String?,
        layer: YpsoWriteFailure.Layer,
        detail: String,
        counter: Long? = null,
    ) = YpsoWriteOutcome.NotSent(writeId, counter, failure(characteristic, firmware, layer, detail))

    private fun failure(
        characteristic: UUID,
        firmware: String?,
        layer: YpsoWriteFailure.Layer,
        detail: String,
    ) = YpsoWriteFailure(layer, characteristic, firmware, detail = detail)
}
