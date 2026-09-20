package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto

/** Monotonic counter accounting for the event-history selector only. */
internal class YpsoHistorySelectorCoordinator(
    private val session: PumpSession,
    crypto: SessionCrypto,
    private val transport: YpsoSerializedWriteTransport,
) {
    data class Owner(val gatt: Any, val connectionId: String, val token: PumpSession.Token)
    private val accounting = YpsoWriteAccounting(session, crypto, transport)
    private val recoveryAccounting = YpsoWriteAccounting(session, crypto, transport) { owner, transaction, intent ->
        session.reserveLowerBoundHistoryRecovery(owner.token, transaction, intent)
    }

    fun select(
        writeId: String,
        owner: Owner,
        index: Int,
        firmware: String?,
        deadlineMs: Long,
        dispatch: (ByteArray) -> Boolean,
        onOutcome: (YpsoWriteOutcome) -> Unit,
    ): Boolean {
        require(index >= 0)
        val plaintext = YpsoGlb.encode(index)
        if (!YpsoWritePolicy.allowsCharacteristic(
                YpsoRemoteWrite.HISTORY_SELECTOR,
                YpsoWritePolicy.EVENT_INDEX_UUID,
                plaintext,
                byteArrayOf(),
                false,
            )
        ) {
            onOutcome(
                YpsoWriteOutcome.NotSent(
                    writeId,
                    null,
                    YpsoWriteFailure(
                        YpsoWriteFailure.Layer.POLICY,
                        YpsoWritePolicy.EVENT_INDEX_UUID,
                        firmware,
                        detail = "event index is outside the production history allowlist",
                    ),
                ),
            )
            return false
        }
        val record = session.snapshot()
        val ready = record?.reboot != null && record.read != null && record.write != null &&
            record.writeBootstrapState == PumpSession.WriteBootstrapState.ESTABLISHED
        if (!ready || transport.hasUnresolvedWrite()) {
            onOutcome(
                YpsoWriteOutcome.NotSent(
                    writeId,
                    null,
                    YpsoWriteFailure(
                        YpsoWriteFailure.Layer.SESSION,
                        YpsoWritePolicy.EVENT_INDEX_UUID,
                        firmware,
                        detail = "durable write high-water mark is unavailable",
                    ),
                ),
            )
            return false
        }
        return accounting.execute(
            YpsoWriteAccounting.Request(
                writeId = writeId,
                owner = YpsoWriteAccounting.Owner(owner.gatt, owner.connectionId, owner.token),
                category = YpsoRemoteWrite.HISTORY_SELECTOR,
                characteristic = YpsoWritePolicy.EVENT_INDEX_UUID,
                plaintext = plaintext,
                firmware = firmware,
                deadlineMs = deadlineMs,
                dispatch = dispatch,
                onOutcome = onOutcome,
            ),
        )
    }

    /** Selector-only search from a durable lower bound. Ordinary writes remain unavailable. */
    fun recoverLowerBound(
        writeId: String,
        owner: Owner,
        index: Int,
        firmware: String?,
        deadlineMs: Long,
        dispatch: (ByteArray) -> Boolean,
        onOutcome: (YpsoWriteOutcome) -> Unit,
    ): Boolean {
        require(index >= 0)
        val plaintext = YpsoGlb.encode(index)
        if (!YpsoWritePolicy.allowsCharacteristic(
                YpsoRemoteWrite.HISTORY_SELECTOR,
                YpsoWritePolicy.EVENT_INDEX_UUID,
                plaintext,
                byteArrayOf(),
                false,
            )
        ) {
            onOutcome(
                YpsoWriteOutcome.NotSent(
                    writeId,
                    null,
                    YpsoWriteFailure(YpsoWriteFailure.Layer.POLICY, YpsoWritePolicy.EVENT_INDEX_UUID, firmware, detail = "recovery index is outside the history allowlist"),
                ),
            )
            return false
        }
        val record = session.snapshot()
        if (record?.reboot == null || record.read == null || record.write == null ||
            record.writeBootstrapState != PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND ||
            transport.hasUnresolvedWrite()
        ) {
            onOutcome(
                YpsoWriteOutcome.NotSent(
                    writeId,
                    null,
                    YpsoWriteFailure(
                        YpsoWriteFailure.Layer.SESSION,
                        YpsoWritePolicy.EVENT_INDEX_UUID,
                        firmware,
                        detail = "durable lower-bound recovery is unavailable",
                    ),
                ),
            )
            return false
        }
        return recoveryAccounting.execute(
            YpsoWriteAccounting.Request(
                writeId = writeId,
                owner = YpsoWriteAccounting.Owner(owner.gatt, owner.connectionId, owner.token),
                category = YpsoRemoteWrite.HISTORY_SELECTOR,
                characteristic = YpsoWritePolicy.EVENT_INDEX_UUID,
                plaintext = plaintext,
                firmware = firmware,
                deadlineMs = deadlineMs,
                dispatch = dispatch,
                onOutcome = onOutcome,
            ),
        )
    }

    fun reconcileAccepted(writeId: String, owner: Owner, evidenceHash: String, detail: String): Boolean =
        accounting.reconcile(
            writeId,
            YpsoWriteAccounting.Owner(owner.gatt, owner.connectionId, owner.token),
            YpsoSemanticEvidence.ACCEPTED,
            PumpSession.WriteResolution.ACCEPTED,
            evidenceHash,
            detail,
        )

    fun reconcileLowerBoundAccepted(writeId: String, owner: Owner, evidenceHash: String, detail: String): Boolean =
        recoveryAccounting.reconcile(
            writeId,
            YpsoWriteAccounting.Owner(owner.gatt, owner.connectionId, owner.token),
            YpsoSemanticEvidence.ACCEPTED,
            PumpSession.WriteResolution.ACCEPTED,
            evidenceHash,
            detail,
        )

    fun ownerDisconnected(gatt: Any, detail: String) {
        accounting.ownerDisconnected(gatt, detail)
        recoveryAccounting.ownerDisconnected(gatt, detail)
    }

    companion object {
        fun sha256(value: ByteArray): String = YpsoWriteAccounting.sha256(value)
    }
}
