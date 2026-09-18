package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import java.security.MessageDigest
import java.util.UUID

/**
 * Durable lifecycle for one logical encrypted pump write.
 *
 * Policy, command readiness and semantic interpretation stay with the domain caller. This boundary
 * owns exact intent binding, reservation/encryption/dispatch ordering, typed transport outcomes,
 * durable uncertainty and live-owner teardown. It deliberately exposes no counter or probe mode.
 */
internal open class YpsoWriteAccounting(
    protected val session: PumpSession,
    private val crypto: SessionCrypto,
    private val transport: YpsoSerializedWriteTransport,
) {
    data class Owner(val gatt: Any, val connectionId: String, val token: PumpSession.Token)

    data class Request(
        val writeId: String,
        val owner: Owner,
        val category: YpsoRemoteWrite,
        val characteristic: UUID,
        val plaintext: ByteArray,
        val firmware: String?,
        val deadlineMs: Long,
        /** Domain journal hook; must durably persist command identity before POSSIBLY_SENT. */
        val beforeDispatch: (PumpSession.Reservation) -> Unit = {},
        val dispatch: (ByteArray) -> Boolean,
        val onOutcome: (YpsoWriteOutcome) -> Unit,
    )

    private data class Pending(
        val gatt: Any,
        val connectionId: String,
        val generation: String,
        val reservationId: String,
    )

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()
    private val claimedWriteIds = mutableSetOf<String>()

    fun execute(request: Request): Boolean {
        if (!claim(request.writeId)) {
            request.onOutcome(notSent(request, YpsoWriteFailure.Layer.SESSION, "write ID is already owned"))
            return false
        }
        if (transport.hasUnresolvedWrite()) {
            releaseClaim(request.writeId)
            request.onOutcome(notSent(request, YpsoWriteFailure.Layer.SESSION, "previous transport owner must be released before recovery"))
            return false
        }
        val transaction = runCatching {
            prepareSession(request.owner)
            session.begin(request.owner.token)
        }.getOrElse {
            releaseClaim(request.writeId)
            request.onOutcome(notSent(request, YpsoWriteFailure.Layer.SESSION, it.message ?: "session unavailable"))
            return false
        }
        var transactionOpen = true
        fun finish() {
            if (!transactionOpen) return
            transactionOpen = false
            session.finish(request.owner.token, transaction)
        }

        val intent = PumpSession.WriteIntent(
            request.writeId,
            request.characteristic.toString(),
            request.category.name,
            sha256(request.plaintext),
        )
        val reservation = runCatching { reserve(request.owner, transaction, intent) }.getOrElse {
            finish()
            releaseClaim(request.writeId)
            request.onOutcome(notSent(request, YpsoWriteFailure.Layer.SESSION, it.message ?: "counter reservation failed"))
            return false
        }
        val encrypted = runCatching {
            session.encryptReserved(request.owner.token, transaction, request.plaintext, crypto)
        }.getOrElse {
            runCatching { session.markNotSent(request.owner.token, transaction) }
            finish()
            releaseClaim(request.writeId)
            request.onOutcome(notSent(request, YpsoWriteFailure.Layer.ENCRYPTION, it.message ?: "encryption failed", reservation.counter))
            return false
        }
        val frames = runCatching { YpsoFraming.chunkPayload(encrypted) }.getOrElse {
            encrypted.fill(0)
            runCatching { session.markNotSent(request.owner.token, transaction) }
            finish()
            releaseClaim(request.writeId)
            request.onOutcome(notSent(request, YpsoWriteFailure.Layer.ENCRYPTION, it.message ?: "framing failed", reservation.counter))
            return false
        }
        encrypted.fill(0)
        runCatching { request.beforeDispatch(reservation) }.getOrElse {
            runCatching { session.markNotSent(request.owner.token, transaction) }
            finish()
            releaseClaim(request.writeId)
            request.onOutcome(notSent(request, YpsoWriteFailure.Layer.SESSION, it.message ?: "domain dispatch journal failed", reservation.counter))
            return false
        }
        runCatching { session.advance(request.owner.token, transaction, PumpSession.Phase.POSSIBLY_SENT) }.getOrElse {
            finish()
            releaseClaim(request.writeId)
            request.onOutcome(
                YpsoWriteOutcome.PossiblyApplied(
                    request.writeId,
                    reservation.counter,
                    failure(request, YpsoWriteFailure.Layer.SESSION, it.message ?: "could not persist dispatch boundary"),
                ),
            )
            return false
        }
        synchronized(lock) {
            pending[request.writeId] = Pending(request.owner.gatt, request.owner.connectionId, request.owner.token.generation, reservation.id)
        }
        val started = transport.start(
            YpsoSerializedWriteTransport.Request(
                writeId = request.writeId,
                owner = YpsoSerializedWriteTransport.Owner(request.owner.gatt, request.owner.connectionId, request.owner.token.generation),
                category = request.category,
                characteristic = request.characteristic,
                counter = reservation.counter,
                firmware = request.firmware,
                frames = frames,
                deadlineMs = request.deadlineMs,
                dispatch = request.dispatch,
                onOutcome = { outcome ->
                    val delivered = when (outcome) {
                        is YpsoWriteOutcome.NotSent -> runCatching {
                            session.markNotSent(request.owner.token, transaction)
                            release(request.writeId)
                            outcome
                        }.getOrElse {
                            YpsoWriteOutcome.PossiblyApplied(
                                request.writeId,
                                reservation.counter,
                                failure(request, YpsoWriteFailure.Layer.SESSION, it.message ?: "not-sent rollback failed"),
                            )
                        }
                        is YpsoWriteOutcome.AcceptedUnverified -> runCatching {
                            session.advance(request.owner.token, transaction, PumpSession.Phase.ACKED)
                            outcome
                        }.getOrElse {
                            YpsoWriteOutcome.PossiblyApplied(
                                request.writeId,
                                reservation.counter,
                                failure(request, YpsoWriteFailure.Layer.SESSION, it.message ?: "ACK persistence failed"),
                            )
                        }
                        is YpsoWriteOutcome.ProvenRejected -> outcome
                        is YpsoWriteOutcome.PossiblyApplied ->
                            if (automaticCounterRecovery && outcome.failure.isPumpCounterError()) {
                                runCatching {
                                    finish()
                                    session.rejectCounterTooLow(
                                        request.owner.token,
                                        reservation.id,
                                        sha256("${request.writeId}:${reservation.counter}:139".toByteArray()),
                                        "pump returned APPERR_COUNTER_ERROR (139) for final command frame",
                                    )
                                    release(request.writeId)
                                    YpsoWriteOutcome.ProvenRejected(request.writeId, reservation.counter, outcome.failure)
                                }.getOrElse { failure ->
                                    YpsoWriteOutcome.PossiblyApplied(
                                        request.writeId,
                                        reservation.counter,
                                        failure(request, YpsoWriteFailure.Layer.SESSION, failure.message ?: "counter recovery persistence failed"),
                                    )
                                }
                            } else outcome
                        is YpsoWriteOutcome.Verified -> outcome
                    }
                    finish()
                    request.onOutcome(delivered)
                },
            ),
        )
        if (!started) {
            runCatching { session.markNotSent(request.owner.token, transaction) }
            release(request.writeId)
            finish()
            request.onOutcome(notSent(request, YpsoWriteFailure.Layer.SESSION, "transport already owns a whole write", reservation.counter))
        }
        return started
    }

    fun reconcile(
        writeId: String,
        owner: Owner,
        semantic: YpsoSemanticEvidence,
        resolution: PumpSession.WriteResolution?,
        evidenceHash: String,
        detail: String,
    ): Boolean {
        validateEvidence(semantic, resolution, evidenceHash, detail)
        val operation = synchronized(lock) { pending[writeId] } ?: return false
        require(operation.gatt === owner.gatt && operation.connectionId == owner.connectionId && operation.generation == owner.token.generation) {
            "Reconciliation owner does not own the dispatched write"
        }
        var reconciled = false
        transport.reconcile(writeId, semantic, detail) { terminal ->
            reconciled = terminal
            if (terminal) {
                session.resolveWrite(owner.token, operation.reservationId, checkNotNull(resolution), evidenceHash, detail)
                release(writeId)
            } else {
                session.recordUnresolvedWriteEvidence(owner.token, operation.reservationId, evidenceHash, detail)
            }
        }
        return reconciled
    }

    fun ownerDisconnected(gatt: Any, detail: String): Boolean {
        val ownedWrite = transport.ownsGatt(gatt)
        transport.cancelOwner(gatt, detail)
        releaseOwner(gatt)
        return ownedWrite
    }

    protected fun releaseOwner(gatt: Any) {
        transport.releaseOwner(gatt)
        synchronized(lock) {
            val writeIds = pending.filterValues { it.gatt === gatt }.keys
            pending.keys.removeAll(writeIds)
            claimedWriteIds.removeAll(writeIds)
        }
    }

    protected open fun reserve(
        owner: Owner,
        transaction: String,
        intent: PumpSession.WriteIntent,
    ): PumpSession.Reservation = session.reserve(owner.token, transaction, intent)

    protected open fun prepareSession(owner: Owner) {
        session.recoverInterruptedWrite(owner.token)
    }

    protected open val automaticCounterRecovery: Boolean = true

    private fun validateEvidence(
        semantic: YpsoSemanticEvidence,
        resolution: PumpSession.WriteResolution?,
        evidenceHash: String,
        detail: String,
    ) {
        require(evidenceHash.matches(Regex("[0-9a-f]{64}")))
        require(detail.isNotBlank() && detail.length <= 4096)
        when (semantic) {
            YpsoSemanticEvidence.ACCEPTED -> require(resolution == PumpSession.WriteResolution.ACCEPTED)
            YpsoSemanticEvidence.REJECTED -> require(
                resolution == PumpSession.WriteResolution.REJECTED_COUNTER_CONSUMED ||
                    resolution == PumpSession.WriteResolution.REJECTED_COUNTER_NOT_CONSUMED,
            )
            YpsoSemanticEvidence.UNKNOWN -> require(resolution == null)
        }
    }

    private fun claim(writeId: String): Boolean = synchronized(lock) { claimedWriteIds.add(writeId) }

    private fun releaseClaim(writeId: String) {
        synchronized(lock) { claimedWriteIds.remove(writeId) }
    }

    private fun release(writeId: String) {
        synchronized(lock) {
            pending.remove(writeId)
            claimedWriteIds.remove(writeId)
        }
    }

    private fun notSent(request: Request, layer: YpsoWriteFailure.Layer, detail: String, counter: Long? = null) =
        YpsoWriteOutcome.NotSent(request.writeId, counter, failure(request, layer, detail))

    private fun failure(request: Request, layer: YpsoWriteFailure.Layer, detail: String) =
        YpsoWriteFailure(layer, request.characteristic, request.firmware, detail = detail)

    companion object {
        fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    }
}

internal fun YpsoWriteFailure.isPumpCounterError(): Boolean =
    layer == YpsoWriteFailure.Layer.PUMP_COUNTER && code == 139
