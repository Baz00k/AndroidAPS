package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.comm.commands.TbrCommand
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto

/** Production counter/transport owner for one START_STOP_TBR command. */
internal class YpsoTbrWriteCoordinator(
    private val session: PumpSession,
    crypto: SessionCrypto,
    transport: YpsoSerializedWriteTransport,
) {
    data class Owner(val gatt: Any, val connectionId: String, val token: PumpSession.Token)
    private val accounting = YpsoWriteAccounting(session, crypto, transport)

    fun write(
        writeId: String,
        owner: Owner,
        percent: Int,
        durationMinutes: Int,
        firmware: String?,
        deadlineMs: Long,
        beforeDispatch: (PumpSession.Reservation) -> Unit,
        dispatch: (ByteArray) -> Boolean,
        onOutcome: (YpsoWriteOutcome) -> Unit,
    ): Boolean {
        val record = session.snapshot()
        if (record?.reboot == null || record.read == null) {
            onOutcome(
                YpsoWriteOutcome.NotSent(
                    writeId,
                    null,
                    YpsoWriteFailure(
                        YpsoWriteFailure.Layer.SESSION,
                        YpsoWritePolicy.TBR_START_STOP_UUID,
                        firmware,
                        detail = "durable pump session counters are unavailable",
                    ),
                ),
            )
            return false
        }
        return accounting.execute(
            YpsoWriteAccounting.Request(
                writeId = writeId,
                owner = YpsoWriteAccounting.Owner(owner.gatt, owner.connectionId, owner.token),
                category = YpsoRemoteWrite.THERAPY_COMMAND,
                characteristic = YpsoWritePolicy.TBR_START_STOP_UUID,
                plaintext = payload(percent, durationMinutes),
                firmware = firmware,
                deadlineMs = deadlineMs,
                beforeDispatch = beforeDispatch,
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

    fun recordUnresolved(writeId: String, owner: Owner, evidenceHash: String, detail: String): Boolean =
        accounting.reconcile(
            writeId,
            YpsoWriteAccounting.Owner(owner.gatt, owner.connectionId, owner.token),
            YpsoSemanticEvidence.UNKNOWN,
            null,
            evidenceHash,
            detail,
        )

    fun ownerDisconnected(gatt: Any, detail: String): Boolean = accounting.ownerDisconnected(gatt, detail)

    companion object {
        fun payload(percent: Int, durationMinutes: Int): ByteArray = TbrCommand(percent, durationMinutes).encode()
    }
}
