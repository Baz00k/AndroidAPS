package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import java.util.UUID

/**
 * Monotonic counter accounting for read-only setting selectors. Interrupted acquisitions can be
 * restarted after transport teardown, using a counter above every previously allocated value.
 */
internal class YpsoProfileSelectorCoordinator(
    private val session: PumpSession,
    private val crypto: SessionCrypto,
    private val transport: YpsoSerializedWriteTransport,
) {
    data class Owner(val gatt: Any, val connectionId: String, val token: PumpSession.Token)
    private val accounting = YpsoWriteAccounting(session, crypto, transport)

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
        val ready = record?.reboot != null && record.read != null
        if (!ready || transport.hasUnresolvedWrite()) {
            onOutcome(notSent(writeId, characteristic, firmware, YpsoWriteFailure.Layer.SESSION, "durable pump session counters are unavailable"))
            return false
        }

        return accounting.execute(
            YpsoWriteAccounting.Request(
                writeId = writeId,
                owner = YpsoWriteAccounting.Owner(owner.gatt, owner.connectionId, owner.token),
                category = YpsoRemoteWrite.SETTINGS_SELECTOR,
                characteristic = characteristic,
                plaintext = plaintext,
                firmware = firmware,
                deadlineMs = deadlineMs,
                dispatch = dispatch,
                onOutcome = onOutcome,
            ),
        )
    }

    fun reconcileAccepted(writeId: String, owner: Owner, evidenceHash: String, detail: String): Boolean {
        return accounting.reconcile(
            writeId,
            YpsoWriteAccounting.Owner(owner.gatt, owner.connectionId, owner.token),
            YpsoSemanticEvidence.ACCEPTED,
            PumpSession.WriteResolution.ACCEPTED,
            evidenceHash,
            detail,
        )
    }

    fun ownerDisconnected(gatt: Any, detail: String) {
        accounting.ownerDisconnected(gatt, detail)
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
        fun sha256(value: ByteArray): String = YpsoWriteAccounting.sha256(value)
    }
}
