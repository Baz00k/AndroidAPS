package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import java.security.MessageDigest
import java.util.UUID

/**
 * Strict-next production accounting for read-only setting selectors. It exposes no recovery or
 * probe mode: an unknown floor or unresolved predecessor blocks the acquisition until reviewed
 * evidence repairs the durable session outside this path.
 */
internal class YpsoProfileSelectorCoordinator(
    private val session: PumpSession,
    private val crypto: SessionCrypto,
    private val transport: YpsoSerializedWriteTransport,
) {
    data class Owner(val gatt: Any, val connectionId: String, val token: PumpSession.Token)

    private data class Pending(
        val gatt: Any,
        val connectionId: String,
        val generation: String,
        val reservationId: String,
    )

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()

    fun write(
        writeId: String,
        owner: Owner,
        settingId: Int,
        firmware: String?,
        deadlineMs: Long,
        dispatch: (ByteArray) -> Boolean,
        onOutcome: (YpsoWriteOutcome) -> Unit,
    ): Boolean {
        val plaintext = YpsoGlb.encode(settingId)
        val characteristic = YpsoWritePolicy.SETTING_ID_UUID
        if (!YpsoWritePolicy.allowsCharacteristic(
                YpsoArtifactPolicy.DISTRIBUTED_PROFILE_READ,
                YpsoRemoteWrite.SETTINGS_SELECTOR,
                characteristic,
                plaintext,
                byteArrayOf(),
                false,
            )
        ) {
            onOutcome(notSent(writeId, characteristic, firmware, YpsoWriteFailure.Layer.POLICY, "setting is outside the profile-read allowlist"))
            return false
        }
        val record = session.snapshot()
        val ready = record?.reboot != null && record.read != null && record.write != null &&
            record.writeBootstrapState == PumpSession.WriteBootstrapState.ESTABLISHED &&
            (record.reservation == null || record.reservation.phase == PumpSession.Phase.VERIFIED)
        if (!ready || transport.hasUnresolvedWrite()) {
            onOutcome(notSent(writeId, characteristic, firmware, YpsoWriteFailure.Layer.SESSION, "durable strict-next write floor is unavailable"))
            return false
        }

        val transaction = runCatching { session.begin(owner.token) }.getOrElse {
            onOutcome(notSent(writeId, characteristic, firmware, YpsoWriteFailure.Layer.SESSION, it.message ?: "session unavailable"))
            return false
        }
        var transactionOpen = true
        fun finish() {
            if (!transactionOpen) return
            transactionOpen = false
            session.finish(owner.token, transaction)
        }
        val reservation = runCatching {
            session.reserve(
                owner.token,
                transaction,
                PumpSession.WriteIntent(writeId, characteristic.toString(), YpsoRemoteWrite.SETTINGS_SELECTOR.name, sha256(plaintext)),
            )
        }.getOrElse {
            finish()
            onOutcome(notSent(writeId, characteristic, firmware, YpsoWriteFailure.Layer.SESSION, it.message ?: "counter reservation failed"))
            return false
        }
        val encrypted = runCatching { session.encryptReserved(owner.token, transaction, plaintext, crypto) }.getOrElse {
            runCatching { session.markNotSent(owner.token, transaction) }
            finish()
            onOutcome(notSent(writeId, characteristic, firmware, YpsoWriteFailure.Layer.ENCRYPTION, it.message ?: "encryption failed", reservation.counter))
            return false
        }
        val frames = runCatching { YpsoFraming.chunkPayload(encrypted) }.getOrElse {
            encrypted.fill(0)
            runCatching { session.markNotSent(owner.token, transaction) }
            finish()
            onOutcome(notSent(writeId, characteristic, firmware, YpsoWriteFailure.Layer.ENCRYPTION, it.message ?: "framing failed", reservation.counter))
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
        val started = transport.start(
            YpsoSerializedWriteTransport.Request(
                writeId = writeId,
                owner = YpsoSerializedWriteTransport.Owner(owner.gatt, owner.connectionId, owner.token.generation),
                category = YpsoRemoteWrite.SETTINGS_SELECTOR,
                characteristic = characteristic,
                counter = reservation.counter,
                firmware = firmware,
                frames = frames,
                deadlineMs = deadlineMs,
                dispatch = dispatch,
                onOutcome = { outcome ->
                    val delivered = when (outcome) {
                        is YpsoWriteOutcome.NotSent -> runCatching {
                            session.markNotSent(owner.token, transaction)
                            synchronized(lock) { pending.remove(writeId) }
                            outcome
                        }.getOrElse {
                            YpsoWriteOutcome.PossiblyApplied(
                                writeId,
                                reservation.counter,
                                failure(characteristic, firmware, YpsoWriteFailure.Layer.SESSION, it.message ?: "not-sent rollback failed"),
                            )
                        }
                        is YpsoWriteOutcome.AcceptedUnverified -> runCatching {
                            session.advance(owner.token, transaction, PumpSession.Phase.ACKED)
                            outcome
                        }.getOrElse {
                            YpsoWriteOutcome.PossiblyApplied(
                                writeId,
                                reservation.counter,
                                failure(characteristic, firmware, YpsoWriteFailure.Layer.SESSION, it.message ?: "ACK persistence failed"),
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
            onOutcome(notSent(writeId, characteristic, firmware, YpsoWriteFailure.Layer.SESSION, "transport already owns a whole write", reservation.counter))
        }
        return started
    }

    fun reconcileAccepted(writeId: String, owner: Owner, evidenceHash: String, detail: String): Boolean {
        require(evidenceHash.matches(Regex("[0-9a-f]{64}")))
        require(detail.isNotBlank() && detail.length <= 4096)
        val operation = synchronized(lock) { pending[writeId] } ?: return false
        require(operation.gatt === owner.gatt && operation.connectionId == owner.connectionId && operation.generation == owner.token.generation)
        transport.reconcile(writeId, YpsoSemanticEvidence.ACCEPTED, detail) { terminal ->
            check(terminal) { "profile selector callback ownership is ambiguous" }
            session.resolveWrite(owner.token, operation.reservationId, PumpSession.WriteResolution.ACCEPTED, evidenceHash, detail)
            synchronized(lock) { pending.remove(writeId) }
        }
        return true
    }

    fun ownerDisconnected(gatt: Any, detail: String) {
        transport.cancelOwner(gatt, detail)
        transport.releaseOwner(gatt)
        synchronized(lock) { pending.entries.removeAll { it.value.gatt === gatt } }
    }

    private fun notSent(
        writeId: String,
        characteristic: UUID,
        firmware: String?,
        layer: YpsoWriteFailure.Layer,
        detail: String,
        counter: Long? = null,
    ) = YpsoWriteOutcome.NotSent(writeId, counter, failure(characteristic, firmware, layer, detail))

    private fun failure(characteristic: UUID, firmware: String?, layer: YpsoWriteFailure.Layer, detail: String) =
        YpsoWriteFailure(layer, characteristic, firmware, detail = detail)

    companion object {
        fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    }
}
