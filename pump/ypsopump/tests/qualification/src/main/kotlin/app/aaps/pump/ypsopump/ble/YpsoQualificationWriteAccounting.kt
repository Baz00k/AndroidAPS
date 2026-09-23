package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto

/** Qualification-only reservation adapter around the production whole-write lifecycle. */
internal class YpsoQualificationWriteAccounting(
    session: PumpSession,
    crypto: SessionCrypto,
    private val transport: YpsoSerializedWriteTransport,
) : YpsoWriteAccounting(session, crypto, transport) {
    private var qualificationReservation: ((String, PumpSession.WriteIntent) -> PumpSession.Reservation)? = null

    fun executeQualification(
        request: Request,
        reserve: (String, PumpSession.WriteIntent) -> PumpSession.Reservation,
    ): Boolean {
        check(qualificationReservation == null) { "qualification reservation is already active" }
        qualificationReservation = reserve
        return try {
            execute(request)
        } finally {
            qualificationReservation = null
        }
    }

    override fun reserve(
        owner: Owner,
        transaction: String,
        intent: PumpSession.WriteIntent,
    ): PumpSession.Reservation = checkNotNull(qualificationReservation)(transaction, intent)

    // Historical probe modes deliberately bind their predecessor. Normal runtime coordinators
    // use the production preparation hook and recover automatically instead.
    override fun prepareSession(owner: Owner) = Unit

    override val automaticCounterRecovery: Boolean = false

    fun releaseQualificationOwner(gatt: Any) {
        releaseOwner(gatt)
    }

    fun reconcilePersisted(
        owner: Owner,
        writeId: String,
        semantic: YpsoSemanticEvidence,
        resolution: PumpSession.WriteResolution?,
        evidenceHash: String,
        detail: String,
    ): YpsoWriteOutcome? {
        validateQualificationEvidence(semantic, resolution, evidenceHash, detail)
        val reservation = session.snapshot()?.reservation?.takeIf {
            it.operationId == writeId && it.phase != PumpSession.Phase.VERIFIED
        } ?: return null
        if (reservation.phase == PumpSession.Phase.RESERVED) {
            require(semantic == YpsoSemanticEvidence.REJECTED && resolution == PumpSession.WriteResolution.REJECTED_COUNTER_NOT_CONSUMED) {
                "A pre-dispatch reservation is recoverable only as proven not sent"
            }
            session.recoverReservedNotSent(owner.token, writeId, evidenceHash, detail)
        } else {
            resolution?.let { session.resolveWrite(owner.token, reservation.id, it, evidenceHash, detail) }
                ?: session.recordUnresolvedWriteEvidence(owner.token, reservation.id, evidenceHash, detail)
        }
        val characteristic = java.util.UUID.fromString(checkNotNull(reservation.characteristic))
        return when (semantic) {
            YpsoSemanticEvidence.ACCEPTED -> YpsoWriteOutcome.Verified(writeId, reservation.counter, detail)
            YpsoSemanticEvidence.REJECTED -> YpsoWriteOutcome.ProvenRejected(
                writeId,
                reservation.counter,
                YpsoWriteFailure(YpsoWriteFailure.Layer.RECONCILIATION, characteristic, null, detail = detail),
            )
            YpsoSemanticEvidence.UNKNOWN -> YpsoWriteOutcome.PossiblyApplied(
                writeId,
                reservation.counter,
                YpsoWriteFailure(YpsoWriteFailure.Layer.RECONCILIATION, characteristic, null, detail = detail),
            )
        }
    }

    fun pendingWrite(): PumpSession.Reservation? =
        (session.snapshot() ?: session.activeRecord())?.reservation?.takeIf { it.phase != PumpSession.Phase.VERIFIED }

    private fun validateQualificationEvidence(
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
}
