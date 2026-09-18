package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.bolus.YpsoBolusBlock
import app.aaps.pump.ypsopump.bolus.YpsoValidatedBolusRequest
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto

/** Production counter/transport owner for one bolus or cancellation command. */
internal class YpsoBolusWriteCoordinator(
    private val session: PumpSession,
    crypto: SessionCrypto,
    transport: YpsoSerializedWriteTransport,
) {
    data class Owner(val gatt: Any, val connectionId: String, val token: PumpSession.Token)
    private val accounting = YpsoWriteAccounting(session, crypto, transport)

    fun start(
        writeId: String,
        owner: Owner,
        request: YpsoValidatedBolusRequest,
        firmware: String?,
        deadlineMs: Long,
        beforeDispatch: (PumpSession.Reservation) -> Unit,
        dispatch: (ByteArray) -> Boolean,
        onOutcome: (YpsoWriteOutcome) -> Unit,
    ): Boolean = write(writeId, owner, YpsoCrc.appendCrc(request.payload()), firmware, deadlineMs, beforeDispatch, dispatch, onOutcome)

    fun cancel(
        writeId: String,
        owner: Owner,
        block: YpsoBolusBlock,
        firmware: String?,
        deadlineMs: Long,
        beforeDispatch: (PumpSession.Reservation) -> Unit,
        dispatch: (ByteArray) -> Boolean,
        onOutcome: (YpsoWriteOutcome) -> Unit,
    ): Boolean = write(
        writeId,
        owner,
        YpsoCrc.appendCrc(BolusCommand.cancelPayload(extended = block == YpsoBolusBlock.SLOW)),
        firmware,
        deadlineMs,
        beforeDispatch,
        dispatch,
        onOutcome,
    )

    private fun write(
        writeId: String,
        owner: Owner,
        plaintext: ByteArray,
        firmware: String?,
        deadlineMs: Long,
        beforeDispatch: (PumpSession.Reservation) -> Unit,
        dispatch: (ByteArray) -> Boolean,
        onOutcome: (YpsoWriteOutcome) -> Unit,
    ): Boolean {
        val record = session.snapshot()
        val ready = record?.reboot != null && record.read != null && record.write != null &&
            record.writeBootstrapState == PumpSession.WriteBootstrapState.ESTABLISHED
        if (!ready) {
            onOutcome(
                YpsoWriteOutcome.NotSent(
                    writeId,
                    null,
                    YpsoWriteFailure(
                        YpsoWriteFailure.Layer.SESSION,
                        YpsoWritePolicy.BOLUS_START_STOP_UUID,
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
                category = YpsoRemoteWrite.THERAPY_COMMAND,
                characteristic = YpsoWritePolicy.BOLUS_START_STOP_UUID,
                plaintext = plaintext,
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
}
