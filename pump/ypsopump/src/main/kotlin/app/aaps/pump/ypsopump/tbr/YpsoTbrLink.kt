package app.aaps.pump.ypsopump.tbr

import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoTbrWriteCoordinator
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.comm.commands.StatusCommand
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Everything one TBR command proved: the pump's answer and the status read on the same link. */
data class YpsoTbrCommandEvidence(
    val result: YpsoTbrWriteResult,
    /** When every frame was acknowledged; null when that was never observed. */
    val acknowledgedAt: Long?,
    /** When the command may first have reached the pump; null when nothing was sent. */
    val dispatchedAt: Long?,
    /** Fresh status after the command, or null when it could not be read. */
    val after: YpsoTbrObservation?,
)

/** The pump's newest history row, read right after a start was proven by status. */
data class YpsoTbrHeadRow(
    /** PumpSync pump ID of the row: sequence generation and sequence. */
    val pumpId: Long,
    val eventType: Int,
    val percent: Int,
    val minutes: Int,
)

/** Blocking pump operations used by [YpsoTbrController]. */
internal interface YpsoTbrLink {
    /** A fresh status read, or null when the pump cannot be read now. */
    fun status(): YpsoTbrObservation?

    /**
     * The newest history row, read with stable count and head brackets, or null when it cannot be
     * read now. Its identity is continuous with the durable history cursor.
     */
    fun headRow(): YpsoTbrHeadRow?

    /**
     * Sends one START_STOP_TBR command and reads status on the same link. [effective] decides whether
     * that status proves the command took effect; the write is reconciled accordingly.
     * [beforeDispatch] must durably record the command before anything can leave the phone.
     */
    fun command(
        percent: Int,
        durationMinutes: Int,
        effective: (YpsoTbrObservation) -> Boolean,
        beforeDispatch: (dispatchedAt: Long) -> Unit,
    ): YpsoTbrCommandEvidence
}

/** [YpsoTbrLink] over the production BLE manager. */
internal class YpsoTbrBleLink(
    private val bleManager: YpsoBleManager,
    private val readStatus: () -> Boolean,
    private val readHead: () -> YpsoTbrHeadRow?,
    private val now: () -> Long = System::currentTimeMillis,
) : YpsoTbrLink {

    override fun status(): YpsoTbrObservation? {
        if (!readStatus()) return null
        return bleManager.observedTbr()
    }

    override fun headRow(): YpsoTbrHeadRow? = readHead()

    override fun command(
        percent: Int,
        durationMinutes: Int,
        effective: (YpsoTbrObservation) -> Boolean,
        beforeDispatch: (dispatchedAt: Long) -> Unit,
    ): YpsoTbrCommandEvidence {
        val writeId = "tbr-${UUID.randomUUID()}"
        val dispatchedAt = AtomicReference<Long?>(null)
        val latch = CountDownLatch(1)
        val delivered = AtomicReference<Pair<YpsoWriteOutcome, YpsoBleManager.TbrCommandOwner?>?>()
        bleManager.writeTbr(
            writeId,
            percent,
            durationMinutes,
            beforeDispatch = {
                val at = now()
                beforeDispatch(at)
                dispatchedAt.compareAndSet(null, at)
            },
        ) { outcome, owner ->
            delivered.set(outcome to owner)
            latch.countDown()
        }
        if (!latch.await(WRITE_CALLBACK_TIMEOUT_S, TimeUnit.SECONDS)) {
            // The transport deadline normally answers first. A missing callback means the link is
            // wedged: tearing it down releases write ownership and leaves the effect to later status.
            bleManager.disconnect()
            return YpsoTbrCommandEvidence(YpsoTbrWriteResult.Uncertain("no write outcome arrived"), null, dispatchedAt.get(), null)
        }
        val (outcome, owner) = checkNotNull(delivered.get())
        val result = YpsoTbrWriteCoordinator.classify(outcome)
        val acknowledgedAt = if (result == YpsoTbrWriteResult.Acknowledged) now() else null
        if (owner == null) return YpsoTbrCommandEvidence(result, acknowledgedAt, dispatchedAt.get(), null)
        val (status, body) = readOwnedStatus(owner)
        val after = status?.toObservation(now())
        val hash = sha256(body ?: "$writeId:no-status".toByteArray())
        val detail = "same-link status after TBR $percent%/$durationMinutes min: " +
            (after?.let { "running=${it.running} percent=${it.percent} remaining=${it.remainingMinutes}" } ?: "unavailable")
        val reconciled = when {
            after != null && result == YpsoTbrWriteResult.Acknowledged && effective(after) ->
                bleManager.verifyTbrAccepted(owner, writeId, hash, detail)
            after != null && result is YpsoTbrWriteResult.Rejected && !effective(after) ->
                bleManager.verifyTbrRejected(owner, writeId, hash, "${result.reason} code ${result.reason.code}; $detail")
            else -> false
        }
        if (!reconciled) bleManager.recordTbrUnresolved(owner, writeId, hash, detail)
        return YpsoTbrCommandEvidence(result, acknowledgedAt, dispatchedAt.get(), after)
    }

    private fun readOwnedStatus(owner: YpsoBleManager.TbrCommandOwner): Pair<StatusCommand?, ByteArray?> {
        val latch = CountDownLatch(1)
        val value = AtomicReference<Pair<StatusCommand?, ByteArray?>>(null to null)
        bleManager.readTbrStatus(owner) { status, body ->
            value.set(status to body)
            latch.countDown()
        }
        return if (latch.await(STATUS_TIMEOUT_S, TimeUnit.SECONDS)) value.get() else null to null
    }

    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    companion object {
        private const val WRITE_CALLBACK_TIMEOUT_S = 45L
        private const val STATUS_TIMEOUT_S = 15L
    }
}

internal fun StatusCommand.toObservation(at: Long) =
    YpsoTbrObservation(running = !isSuspended, percent = activeTbrPercent, remainingMinutes = tbrRemainingMinutes, observedAt = at)
