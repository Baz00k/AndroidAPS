package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
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
    private val accounting = YpsoQualificationWriteAccounting(session, crypto, transport)
    enum class BenchWriteMode {
        STRICT_NEXT,
        FORWARD_GAP,
        NEW_EPOCH_BOOTSTRAP,
        AMBIGUITY_CONVERGENCE,
        SETTINGS_COUNTER_RECOVERY,
        SETTINGS_COUNTER_JUMP,
        DUPLICATE_COUNTER,
    }

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

    fun writeSelector(
        writeId: String,
        owner: Owner,
        category: YpsoRemoteWrite,
        characteristic: UUID,
        plaintext: ByteArray,
        firmware: String?,
        deadlineMs: Long,
        mode: BenchWriteMode = BenchWriteMode.STRICT_NEXT,
        dispatch: (ByteArray) -> Boolean,
        onOutcome: (YpsoWriteOutcome) -> Unit,
    ): Boolean {
        require(category == YpsoRemoteWrite.HISTORY_SELECTOR || category == YpsoRemoteWrite.SETTINGS_SELECTOR)
        if (!YpsoQualificationWritePolicy.allowsSelector(category, characteristic, plaintext)) {
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
        val historyBinding =
            when (characteristic) {
                YpsoWritePolicy.ALARM_INDEX_UUID ->
                    Triple(
                        PumpSession.HistoryFamily.ALARM,
                        checkNotNull(YpsoGlb.decodeExact(plaintext)),
                        YpsoWritePolicy.ALARM_COUNT_UUID.toString(),
                    )
                YpsoWritePolicy.SYSTEM_INDEX_UUID ->
                    Triple(
                        PumpSession.HistoryFamily.SYSTEM,
                        checkNotNull(YpsoGlb.decodeExact(plaintext)),
                        YpsoWritePolicy.SYSTEM_COUNT_UUID.toString(),
                    )
                else -> null
            }
        val record = session.snapshot()
        val bootstrapReady =
            mode == BenchWriteMode.NEW_EPOCH_BOOTSTRAP &&
                record?.writeBootstrapState == PumpSession.WriteBootstrapState.OBSERVED_NEW_EPOCH &&
                !record.benchNewEpochBootstrapAttempted &&
                record.write == null &&
                record.reservation == null &&
                record.benchNewEpochBootstrapReference != null
        val convergenceReady = mode == BenchWriteMode.AMBIGUITY_CONVERGENCE && session.benchAmbiguityConvergenceReady()
        val settingsRecoveryReady = mode == BenchWriteMode.SETTINGS_COUNTER_RECOVERY && session.benchSettingsCounterRecoveryReady() ||
            mode == BenchWriteMode.SETTINGS_COUNTER_JUMP && session.benchSettingsCounterJumpReady()
        val counterCertain =
            record?.reboot != null &&
                record.read != null &&
                (record.write != null || bootstrapReady) &&
                (convergenceReady || settingsRecoveryReady || record.reservation == null || record.reservation.phase == PumpSession.Phase.VERIFIED)
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

        return accounting.executeQualification(
            YpsoWriteAccounting.Request(
                writeId = writeId,
                owner = YpsoWriteAccounting.Owner(owner.gatt, owner.connectionId, owner.token),
                category = category,
                characteristic = characteristic,
                plaintext = plaintext,
                firmware = firmware,
                deadlineMs = deadlineMs,
                dispatch = dispatch,
                onOutcome = onOutcome,
            ),
            reserve = { transaction, intent ->
                when (mode) {
                    BenchWriteMode.NEW_EPOCH_BOOTSTRAP ->
                        session.reserveBenchNewEpochBootstrapCandidate(owner.token, transaction, intent)
                    BenchWriteMode.DUPLICATE_COUNTER ->
                        session.reserveBenchDuplicateCounterCandidate(owner.token, transaction, intent)
                    BenchWriteMode.AMBIGUITY_CONVERGENCE ->
                        session.reserveBenchAmbiguityConvergenceCandidate(owner.token, transaction, intent)
                    BenchWriteMode.SETTINGS_COUNTER_RECOVERY ->
                        session.reserveBenchSettingsCounterRecoveryCandidate(owner.token, transaction, intent)
                    BenchWriteMode.SETTINGS_COUNTER_JUMP ->
                        session.reserveBenchSettingsCounterRecoveryCandidate(owner.token, transaction, intent, jump = true)
                    BenchWriteMode.STRICT_NEXT, BenchWriteMode.FORWARD_GAP ->
                        session.reserveBenchCandidate(
                            owner.token,
                            transaction,
                            intent,
                            forwardGap = if (mode == BenchWriteMode.FORWARD_GAP) 1 else 0,
                            historyBinding?.first,
                            historyBinding?.second,
                            historyBinding?.third,
                        )
                }
            },
        )
    }

    /** No retry is performed. Rejection requires an explicit measured counter-consumption result. */
    fun reconcile(
        writeId: String,
        owner: Owner,
        reconciliation: Reconciliation,
    ): Boolean {
        return accounting.reconcile(
            writeId,
            YpsoWriteAccounting.Owner(owner.gatt, owner.connectionId, owner.token),
            reconciliation.semantic,
            reconciliation.counter,
            reconciliation.evidenceHash,
            reconciliation.detail,
        )
    }

    /** Live ownership is gone after disconnect; the durable reservation remains the recovery source. */
    fun ownerDisconnected(
        gatt: Any,
        detail: String,
    ): Boolean {
        return accounting.ownerDisconnected(gatt, detail)
    }

    /** The acknowledged write remains durable, but the closed GATT must not stay reachable in memory. */
    fun releaseOwner(gatt: Any) {
        accounting.releaseQualificationOwner(gatt)
    }

    /** Recover and reconcile a durable pending selector after process death. */
    fun reconcilePersisted(
        owner: Owner,
        writeId: String,
        reconciliation: Reconciliation,
    ): YpsoWriteOutcome? {
        return accounting.reconcilePersisted(
            YpsoWriteAccounting.Owner(owner.gatt, owner.connectionId, owner.token),
            writeId,
            reconciliation.semantic,
            reconciliation.counter,
            reconciliation.evidenceHash,
            reconciliation.detail,
        )
    }

    data class PendingWrite(
        val writeId: String,
        val counter: Long,
        val characteristic: UUID,
        val purpose: YpsoRemoteWrite,
        val phase: PumpSession.Phase,
        val payloadHash: String,
    )

    fun pendingWrite(): PendingWrite? = accounting.pendingWrite()?.let {
        PendingWrite(
            it.operationId ?: return@let null,
            it.counter,
            UUID.fromString(it.characteristic ?: return@let null),
            YpsoRemoteWrite.valueOf(it.purpose ?: return@let null),
            it.phase,
            it.payloadHash ?: return@let null,
        )
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
