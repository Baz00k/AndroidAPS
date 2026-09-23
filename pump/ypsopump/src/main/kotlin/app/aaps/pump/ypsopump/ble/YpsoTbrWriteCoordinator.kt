package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.comm.commands.TbrCommand
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.tbr.YpsoTbrRejectReason
import app.aaps.pump.ypsopump.tbr.YpsoTbrWriteResult

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

    /** A measured final-frame rejection confirmed by status; the counter is treated as consumed. */
    fun reconcileRejected(writeId: String, owner: Owner, evidenceHash: String, detail: String): Boolean =
        accounting.reconcile(
            writeId,
            YpsoWriteAccounting.Owner(owner.gatt, owner.connectionId, owner.token),
            YpsoSemanticEvidence.REJECTED,
            PumpSession.WriteResolution.REJECTED_COUNTER_CONSUMED,
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

        /** Interprets a transport outcome for START_STOP_TBR; only final-frame codes carry pump meaning. */
        fun classify(outcome: YpsoWriteOutcome): YpsoTbrWriteResult = when (outcome) {
            is YpsoWriteOutcome.NotSent -> YpsoTbrWriteResult.NotSent(outcome.failure.detail)
            // Only a pump-confirmed counter rejection produces this; the command was not applied.
            is YpsoWriteOutcome.ProvenRejected -> YpsoTbrWriteResult.NotSent(outcome.failure.detail)
            is YpsoWriteOutcome.AcceptedUnverified, is YpsoWriteOutcome.Verified -> YpsoTbrWriteResult.Acknowledged
            is YpsoWriteOutcome.PossiblyApplied -> {
                val failure = outcome.failure
                val reason = YpsoTbrRejectReason.fromFinalFrameCode(failure.code)
                    .takeIf { failure.layer == YpsoWriteFailure.Layer.GATT_CALLBACK && failure.finalFrame }
                if (reason != null) YpsoTbrWriteResult.Rejected(reason) else YpsoTbrWriteResult.Uncertain(failure.detail)
            }
        }
    }
}
