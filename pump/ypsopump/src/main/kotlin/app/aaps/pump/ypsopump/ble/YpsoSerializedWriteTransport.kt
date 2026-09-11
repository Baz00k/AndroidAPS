package app.aaps.pump.ypsopump.ble

import java.util.UUID

/**
 * Failure provenance for a complete logical pump write. A numeric status is not meaningful without
 * the layer and characteristic that produced it.
 */
internal data class YpsoWriteFailure(
    val layer: Layer,
    val characteristic: UUID,
    val firmware: String?,
    val code: Int? = null,
    val frame: Int? = null,
    val detail: String,
) {
    enum class Layer { POLICY, READINESS, SESSION, ENCRYPTION, DISPATCH, GATT_CALLBACK, DEADLINE, RECONCILIATION }
}

/** Transport and reconciliation states. Only [Verified] closes pump-side uncertainty. */
internal sealed interface YpsoWriteOutcome {
    val writeId: String
    val counter: Long?

    data class NotSent(
        override val writeId: String,
        override val counter: Long?,
        val failure: YpsoWriteFailure,
    ) : YpsoWriteOutcome

    data class ProvenRejected(
        override val writeId: String,
        override val counter: Long?,
        val failure: YpsoWriteFailure,
    ) : YpsoWriteOutcome

    data class PossiblyApplied(
        override val writeId: String,
        override val counter: Long?,
        val failure: YpsoWriteFailure,
    ) : YpsoWriteOutcome

    /** Every expected callback arrived, but callback ordering cannot prove semantic acceptance. */
    data class AcceptedUnverified(
        override val writeId: String,
        override val counter: Long?,
    ) : YpsoWriteOutcome

    data class Verified(
        override val writeId: String,
        override val counter: Long?,
        val evidence: String,
    ) : YpsoWriteOutcome
}

internal enum class YpsoSemanticEvidence { ACCEPTED, REJECTED, UNKNOWN }

internal fun interface YpsoWriteBehaviorRecorder {
    fun record(event: YpsoWriteBehavior)

    companion object {
        val NONE = YpsoWriteBehaviorRecorder { }
    }
}

/** Deliberately records callback-observable facts, never an operation ID Android does not provide. */
internal sealed interface YpsoWriteBehavior {
    val writeId: String

    data class Started(
        override val writeId: String,
        val connectionId: String,
        val generation: String,
        val category: YpsoRemoteWrite,
        val characteristic: UUID,
        val counter: Long,
        val frameCount: Int,
    ) : YpsoWriteBehavior

    data class FrameDispatch(
        override val writeId: String,
        val frame: Int,
        val acceptedLocally: Boolean,
    ) : YpsoWriteBehavior

    data class Callback(
        override val writeId: String,
        val characteristic: UUID,
        val status: Int,
        val frameExpected: Int,
    ) : YpsoWriteBehavior

    data class IgnoredCallback(
        override val writeId: String,
        val reason: String,
        val characteristic: UUID,
        val status: Int,
    ) : YpsoWriteBehavior

    data class Outcome(
        override val writeId: String,
        val value: YpsoWriteOutcome,
    ) : YpsoWriteBehavior

    data class Reconciled(
        override val writeId: String,
        val evidence: YpsoSemanticEvidence,
        val detail: String,
    ) : YpsoWriteBehavior
}

/**
 * Owns one whole fragmented write from its first frame until semantic reconciliation.
 *
 * Android's characteristic-write callback contains only GATT, characteristic and status. It does
 * not identify the submitted frame. Consequently callback success can advance transport, but can
 * never produce [YpsoWriteOutcome.Verified]. A duplicate same-UUID callback after transport ACK
 * moves the operation to durable uncertainty unless semantic evidence resolves it.
 */
internal class YpsoSerializedWriteTransport(
    private val scheduleDeadline: (Runnable, Long) -> Unit,
    private val cancelDeadline: (Runnable) -> Unit,
    private val recorder: YpsoWriteBehaviorRecorder = YpsoWriteBehaviorRecorder.NONE,
) {
    data class Owner(
        val gatt: Any,
        val connectionId: String,
        val generation: String,
    )

    data class Request(
        val writeId: String,
        val owner: Owner,
        val category: YpsoRemoteWrite,
        val characteristic: UUID,
        val counter: Long,
        val firmware: String?,
        val frames: List<ByteArray>,
        val deadlineMs: Long,
        val dispatch: (ByteArray) -> Boolean,
        val onOutcome: (YpsoWriteOutcome) -> Unit,
    )

    private data class Active(
        val request: Request,
        var frame: Int = 0,
        var successfulCallbacks: Int = 0,
        var dispatchInProgress: Boolean = false,
        var dispatchAttempted: Boolean = false,
        var awaitingReconciliation: Boolean = false,
        var ambiguousCallback: Boolean = false,
        var uncertaintyReported: Boolean = false,
        var deadline: Runnable?,
    )

    private val lock = Any()
    private var active: Active? = null

    fun start(request: Request): Boolean {
        require(request.writeId.isNotBlank() && request.owner.connectionId.isNotBlank() && request.owner.generation.isNotBlank())
        require(request.category == YpsoRemoteWrite.HISTORY_SELECTOR || request.category == YpsoRemoteWrite.SETTINGS_SELECTOR)
        require(request.counter > 0 && request.frames.isNotEmpty() && request.frames.all { it.isNotEmpty() })
        require(request.deadlineMs > 0)
        val deadline = Runnable { onDeadline(request.writeId) }
        synchronized(lock) {
            if (active != null) return false
            active = Active(request = request.copy(frames = request.frames.map(ByteArray::copyOf)), deadline = deadline)
        }
        recorder.record(
            YpsoWriteBehavior.Started(
                request.writeId,
                request.owner.connectionId,
                request.owner.generation,
                request.category,
                request.characteristic,
                request.counter,
                request.frames.size,
            ),
        )
        scheduleDeadline(deadline, request.deadlineMs)
        dispatchCurrent(request.writeId)
        return true
    }

    /** Callback adapter intentionally accepts no submitted operation/frame identifier. */
    fun onCharacteristicWrite(
        gatt: Any,
        characteristic: UUID,
        status: Int,
    ) {
        var nextDispatch: String? = null
        var outcome: YpsoWriteOutcome? = null
        var ignored: YpsoWriteBehavior.IgnoredCallback? = null
        synchronized(lock) {
            val current = active
            if (current == null) return
            val request = current.request
            when {
                request.owner.gatt !== gatt ->
                    ignored =
                        YpsoWriteBehavior.IgnoredCallback(
                            request.writeId,
                            "stale-gatt",
                            characteristic,
                            status,
                        )
                request.characteristic != characteristic ->
                    ignored =
                        YpsoWriteBehavior.IgnoredCallback(
                            request.writeId,
                            "different-characteristic",
                            characteristic,
                            status,
                        )
                current.awaitingReconciliation -> {
                    current.ambiguousCallback = true
                    ignored =
                        YpsoWriteBehavior.IgnoredCallback(
                            request.writeId,
                            "duplicate-same-uuid-callback-awaiting-reconciliation",
                            characteristic,
                            status,
                        )
                    if (!current.uncertaintyReported) {
                        current.uncertaintyReported = true
                        outcome =
                            possiblyApplied(
                                current,
                                YpsoWriteFailure.Layer.GATT_CALLBACK,
                                status,
                                "callback cannot be assigned to a submitted frame",
                            )
                    }
                }
                current.dispatchInProgress -> {
                    current.ambiguousCallback = true
                    ignored =
                        YpsoWriteBehavior.IgnoredCallback(
                            request.writeId,
                            "same-uuid-callback-before-platform-dispatch-returned",
                            characteristic,
                            status,
                        )
                    if (!current.uncertaintyReported) {
                        current.uncertaintyReported = true
                        outcome =
                            possiblyApplied(
                                current,
                                YpsoWriteFailure.Layer.GATT_CALLBACK,
                                status,
                                "callback arrived before platform dispatch acceptance was known",
                            )
                    }
                    holdForReconciliationLocked(current, uncertaintyReported = true)
                }
                !current.dispatchAttempted -> {
                    current.ambiguousCallback = true
                    ignored =
                        YpsoWriteBehavior.IgnoredCallback(
                            request.writeId,
                            "same-uuid-callback-before-pending-frame-dispatch",
                            characteristic,
                            status,
                        )
                    if (!current.uncertaintyReported) {
                        current.uncertaintyReported = true
                        outcome =
                            possiblyApplied(
                                current,
                                YpsoWriteFailure.Layer.GATT_CALLBACK,
                                status,
                                "callback arrived when no submitted frame was awaiting acknowledgement",
                            )
                    }
                    holdForReconciliationLocked(current, uncertaintyReported = true)
                }
                else -> {
                    recorder.record(
                        YpsoWriteBehavior.Callback(request.writeId, characteristic, status, current.frame + 1),
                    )
                    if (status != 0) {
                        outcome =
                            possiblyApplied(
                                current,
                                YpsoWriteFailure.Layer.GATT_CALLBACK,
                                status,
                                "numeric callback status has no measured semantic classification",
                            )
                        holdForReconciliationLocked(current, uncertaintyReported = true)
                    } else {
                        current.successfulCallbacks++
                        if (current.frame + 1 == request.frames.size) {
                            current.awaitingReconciliation = true
                            outcome = YpsoWriteOutcome.AcceptedUnverified(request.writeId, request.counter)
                        } else {
                            current.frame++
                            current.dispatchAttempted = false
                            nextDispatch = request.writeId
                        }
                    }
                }
            }
        }
        ignored?.let(recorder::record)
        outcome?.let(::publish)
        nextDispatch?.let(::dispatchCurrent)
    }

    fun reconcile(
        writeId: String,
        evidence: YpsoSemanticEvidence,
        detail: String,
        commit: (terminal: Boolean) -> Unit = {},
    ) {
        val delivery =
            synchronized(lock) {
                val current = active ?: return
                if (current.request.writeId != writeId || !current.awaitingReconciliation) return
                recorder.record(YpsoWriteBehavior.Reconciled(writeId, evidence, detail))
                val terminal = evidence != YpsoSemanticEvidence.UNKNOWN && !current.ambiguousCallback
                commit(terminal)
                val result =
                    if (current.ambiguousCallback && evidence != YpsoSemanticEvidence.UNKNOWN) {
                        possiblyApplied(
                            current,
                            YpsoWriteFailure.Layer.RECONCILIATION,
                            null,
                            "callback ownership remained ambiguous; $detail",
                        )
                    } else {
                        when (evidence) {
                            YpsoSemanticEvidence.ACCEPTED -> {
                                YpsoWriteOutcome.Verified(writeId, current.request.counter, detail)
                            }
                            YpsoSemanticEvidence.REJECTED ->
                                YpsoWriteOutcome.ProvenRejected(
                                    writeId,
                                    current.request.counter,
                                    failure(current, YpsoWriteFailure.Layer.RECONCILIATION, null, detail),
                                )
                            YpsoSemanticEvidence.UNKNOWN ->
                                possiblyApplied(
                                    current,
                                    YpsoWriteFailure.Layer.RECONCILIATION,
                                    null,
                                    detail,
                                )
                        }
                    }
                if (!terminal) {
                    holdForReconciliationLocked(current, uncertaintyReported = true)
                } else {
                    finishLocked(current)
                }
                result to current.request.onOutcome
            }
        publish(delivery.first, delivery.second)
    }

    /** Connection/session teardown resolves an undispatched request as not-sent and all progress as uncertain. */
    fun cancelOwner(
        gatt: Any,
        detail: String,
    ) {
        val delivery =
            synchronized(lock) {
                val current = active ?: return
                if (current.request.owner.gatt !== gatt) return
                if (current.uncertaintyReported) {
                    holdForReconciliationLocked(current, uncertaintyReported = true)
                    return@synchronized null
                }
                val result =
                    if (!current.dispatchInProgress && !current.dispatchAttempted && current.successfulCallbacks == 0) {
                        YpsoWriteOutcome.NotSent(
                            current.request.writeId,
                            current.request.counter,
                            failure(current, YpsoWriteFailure.Layer.DISPATCH, null, detail),
                        )
                    } else {
                        possiblyApplied(current, YpsoWriteFailure.Layer.RECONCILIATION, null, detail)
                    }
                if (result is YpsoWriteOutcome.NotSent) {
                    finishLocked(
                        current,
                    )
                } else {
                    holdForReconciliationLocked(current, uncertaintyReported = true)
                }
                result to current.request.onOutcome
            } ?: return
        publish(delivery.first, delivery.second)
    }

    /** Drop only the in-memory callback owner; durable uncertainty remains in [PumpSession]. */
    fun releaseOwner(gatt: Any) {
        synchronized(lock) {
            val current = active ?: return
            if (current.request.owner.gatt !== gatt) return
            current.deadline?.let(cancelDeadline)
            current.deadline = null
            active = null
        }
    }

    internal fun hasUnresolvedWrite(): Boolean = synchronized(lock) { active != null }

    internal fun ownsGatt(gatt: Any): Boolean =
        synchronized(lock) {
            active?.request?.owner?.gatt === gatt
        }

    internal fun owns(
        gatt: Any,
        characteristic: UUID,
        category: YpsoRemoteWrite,
    ): Boolean =
        synchronized(lock) {
            val request = active?.request ?: return@synchronized false
            request.owner.gatt === gatt &&
                request.characteristic == characteristic &&
                request.category == category
        }

    private fun dispatchCurrent(writeId: String) {
        val frame: ByteArray
        val dispatch: (ByteArray) -> Boolean
        val frameNumber: Int
        synchronized(lock) {
            val current = active ?: return
            if (current.request.writeId != writeId || current.awaitingReconciliation) return
            current.dispatchInProgress = true
            current.dispatchAttempted = false
            frame = current.request.frames[current.frame].copyOf()
            dispatch = current.request.dispatch
            frameNumber = current.frame + 1
        }
        val accepted = runCatching { dispatch(frame) }.getOrDefault(false)
        recorder.record(YpsoWriteBehavior.FrameDispatch(writeId, frameNumber, accepted))
        if (accepted) {
            synchronized(lock) {
                val current = active ?: return
                if (current.request.writeId != writeId || current.frame + 1 != frameNumber) return
                current.dispatchInProgress = false
                if (!current.awaitingReconciliation) current.dispatchAttempted = true
            }
            return
        }
        val delivery =
            synchronized(lock) {
                val current = active ?: return
                if (current.request.writeId != writeId || current.frame + 1 != frameNumber) return
                current.dispatchInProgress = false
                if (current.awaitingReconciliation) return
                val result =
                    if (current.successfulCallbacks == 0 && current.frame == 0) {
                        YpsoWriteOutcome.NotSent(
                            writeId,
                            current.request.counter,
                            failure(current, YpsoWriteFailure.Layer.DISPATCH, null, "first frame was not dispatched"),
                        )
                    } else {
                        possiblyApplied(current, YpsoWriteFailure.Layer.DISPATCH, null, "a later frame was not dispatched")
                    }
                if (result is YpsoWriteOutcome.NotSent) {
                    finishLocked(
                        current,
                    )
                } else {
                    holdForReconciliationLocked(current, uncertaintyReported = true)
                }
                result to current.request.onOutcome
            }
        publish(delivery.first, delivery.second)
    }

    private fun onDeadline(writeId: String) {
        val delivery =
            synchronized(lock) {
                val current = active ?: return
                if (current.request.writeId != writeId) return
                val result =
                    if (!current.dispatchInProgress && !current.dispatchAttempted && current.successfulCallbacks == 0) {
                        YpsoWriteOutcome.NotSent(
                            writeId,
                            current.request.counter,
                            failure(current, YpsoWriteFailure.Layer.DEADLINE, null, "deadline expired before dispatch"),
                        )
                    } else {
                        possiblyApplied(current, YpsoWriteFailure.Layer.DEADLINE, null, "whole-write deadline expired")
                    }
                if (result is YpsoWriteOutcome.NotSent) {
                    finishLocked(
                        current,
                    )
                } else {
                    holdForReconciliationLocked(current, uncertaintyReported = true)
                }
                result to current.request.onOutcome
            }
        publish(delivery.first, delivery.second)
    }

    private fun failure(
        active: Active,
        layer: YpsoWriteFailure.Layer,
        code: Int?,
        detail: String,
    ) = YpsoWriteFailure(
        layer = layer,
        characteristic = active.request.characteristic,
        firmware = active.request.firmware,
        code = code,
        frame = active.frame + 1,
        detail = detail,
    )

    private fun possiblyApplied(
        active: Active,
        layer: YpsoWriteFailure.Layer,
        code: Int?,
        detail: String,
    ) = YpsoWriteOutcome.PossiblyApplied(
        active.request.writeId,
        active.request.counter,
        failure(active, layer, code, detail),
    )

    private fun finishLocked(current: Active) {
        if (active !== current) return
        active = null
        current.deadline?.let { runCatching { cancelDeadline(it) } }
        current.deadline = null
    }

    private fun holdForReconciliationLocked(
        current: Active,
        uncertaintyReported: Boolean = current.uncertaintyReported,
    ) {
        if (active !== current) return
        current.awaitingReconciliation = true
        current.uncertaintyReported = uncertaintyReported
        current.deadline?.let { runCatching { cancelDeadline(it) } }
        current.deadline = null
    }

    private fun publish(
        outcome: YpsoWriteOutcome,
        callback: ((YpsoWriteOutcome) -> Unit)? = null,
    ) {
        recorder.record(YpsoWriteBehavior.Outcome(outcome.writeId, outcome))
        runCatching {
            val listener =
                callback ?: synchronized(lock) {
                    active?.request?.takeIf { it.writeId == outcome.writeId }?.onOutcome
                }
            listener?.invoke(outcome)
        }
    }
}
