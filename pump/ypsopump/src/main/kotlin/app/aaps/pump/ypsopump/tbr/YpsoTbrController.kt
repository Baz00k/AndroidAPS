package app.aaps.pump.ypsopump.tbr

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AAPS-side temporary basal records that the controller maintains. Each call returns whether AAPS
 * now holds the requested record; a false result is reported as unsaved accounting.
 */
internal interface YpsoTbrRecords {
    /** Records a pump-confirmed percent TBR under [temporaryId] until history supplies its pump identity. */
    fun started(attempt: YpsoTbrAttempt, timestamp: Long): Boolean

    /** Ends whichever TBR AAPS shows as running at [timestamp]; true when none remains running. */
    fun stopped(timestamp: Long): Boolean
}

/**
 * Enacts TBR requests with only measured pump semantics: a running TBR cannot be replaced, so a
 * change is a confirmed stop followed by a start. AAPS records exactly what status proves at each
 * step. A start whose outcome is unknown stays unrecorded until status or history resolves it.
 */
internal class YpsoTbrController(
    private val link: YpsoTbrLink,
    private val journal: YpsoTbrJournal,
    private val records: YpsoTbrRecords,
    private val serialNumber: () -> String,
    private val historyBaseline: () -> Long?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        /** The pump runs [request]; AAPS recorded it. */
        data class Started(val request: YpsoTbrRequest) : Result

        /** No TBR is running; AAPS shows none. [enacted] when this call changed the pump. */
        data class Stopped(val enacted: Boolean) : Result

        /** Nothing about therapy changed. */
        data class NotChanged(val reason: Reason) : Result

        /**
         * The pump may differ from what AAPS shows. [previousStopped] tells the caller whether an
         * earlier TBR was ended first, so the scheduled rate may be running.
         */
        data class Uncertain(val reason: Reason, val previousStopped: Boolean) : Result
    }

    enum class Reason {
        PUMP_UNREADABLE,
        PUMP_STOPPED,
        NOT_SET_UP,
        HISTORY_NOT_READY,
        COMMAND_NOT_SENT,
        STOP_NOT_CONFIRMED,
        START_REJECTED,
        START_NOT_CONFIRMED,
        NOT_SAVED,
        INTERNAL_ERROR,
    }

    private val active = AtomicBoolean(false)
    /** Set once this call proved the previous TBR ended, so a later failure reports it truthfully. */
    @Volatile private var stoppedThisCall = false
    val isBusy: Boolean get() = active.get()

    fun start(request: YpsoTbrRequest, type: String): Result = exclusive {
        val before = link.status() ?: return@exclusive Result.NotChanged(Reason.PUMP_UNREADABLE)
        resolvePending(before)
        if (!before.running) return@exclusive Result.NotChanged(Reason.PUMP_STOPPED)
        val serial = serialNumber().takeIf(String::isNotBlank) ?: return@exclusive Result.NotChanged(Reason.NOT_SET_UP)
        val baseline = historyBaseline() ?: return@exclusive Result.NotChanged(Reason.HISTORY_NOT_READY)

        var previousStopped = false
        if (!before.idle) {
            when (val stop = stopRunning()) {
                is StopOutcome.Confirmed -> previousStopped = true
                is StopOutcome.NotSent -> return@exclusive Result.NotChanged(Reason.COMMAND_NOT_SENT)
                is StopOutcome.Unknown -> return@exclusive Result.Uncertain(stop.reason, previousStopped = false)
            }
        }

        val attempt = YpsoTbrAttempt(
            id = "tbr-${UUID.randomUUID()}",
            pumpSerial = serial,
            percent = request.percent,
            durationMinutes = request.durationMinutes,
            type = type,
            temporaryId = newTemporaryId(),
            baselinePumpId = baseline,
            createdAt = now(),
        )
        journal.prepare(attempt)
        val evidence = link.command(
            request.percent,
            request.durationMinutes,
            effective = { it.justStarted(request) },
            beforeDispatch = { at -> journal.dispatched(attempt.id, at) },
        )
        if (evidence.dispatchedAt == null) {
            journal.notStarted(attempt.id, "command was not sent: ${evidence.result}")
            return@exclusive if (previousStopped) Result.Stopped(enacted = true)
            else Result.NotChanged(Reason.COMMAND_NOT_SENT)
        }
        val after = evidence.after
        when {
            after != null && after.justStarted(request) && evidence.result !is YpsoTbrWriteResult.Rejected -> {
                // Acknowledgement is the latest moment the command can have taken effect; without it
                // the dispatch time is used, which never shortens the recorded TBR.
                val startedAt = evidence.acknowledgedAt ?: evidence.dispatchedAt
                val started = journal.started(attempt.id, startedAt)
                if (!records.started(started, startedAt)) {
                    journal.detail(attempt.id, "AAPS did not save the started TBR")
                    return@exclusive Result.Uncertain(Reason.NOT_SAVED, previousStopped)
                }
                Result.Started(request)
            }
            after != null && !after.justStarted(request) && (after.idle || !after.running) -> {
                journal.notStarted(attempt.id, "status after start: percent=${after.percent} remaining=${after.remainingMinutes}")
                if (previousStopped) Result.Uncertain(Reason.START_REJECTED, previousStopped = true)
                else Result.NotChanged(Reason.START_REJECTED)
            }
            // Unreadable, or a different TBR is running: the pump state is not what anyone requested.
            else -> Result.Uncertain(Reason.START_NOT_CONFIRMED, previousStopped)
        }
    }

    fun cancel(): Result = exclusive {
        val before = link.status() ?: return@exclusive Result.NotChanged(Reason.PUMP_UNREADABLE)
        resolvePending(before)
        if (!before.running || before.idle) {
            // Nothing runs on the pump. Stop mode also ends any TBR, so AAPS must not show one either.
            if (!records.stopped(before.observedAt)) return@exclusive Result.Uncertain(Reason.NOT_SAVED, previousStopped = true)
            journal.stopped(before.observedAt)
            return@exclusive Result.Stopped(enacted = false)
        }
        when (val stop = stopRunning()) {
            is StopOutcome.Confirmed -> Result.Stopped(enacted = true)
            is StopOutcome.NotSent -> Result.NotChanged(Reason.COMMAND_NOT_SENT)
            is StopOutcome.Unknown -> Result.Uncertain(stop.reason, previousStopped = false)
        }
    }

    /**
     * Resolves a start whose status was never read (lost link, crash) from a fresh observation. Called
     * with every status read, so an uncertain start is recorded as soon as the pump proves it.
     */
    fun resolvePending(observation: YpsoTbrObservation) {
        for (attempt in journal.all().filter { it.awaitsStatus }) {
            val dispatchedAt = attempt.dispatchedAt
            if (dispatchedAt == null) {
                journal.notStarted(attempt.id, "no dispatch was recorded")
                continue
            }
            val elapsedMinutes = ((observation.observedAt - dispatchedAt) / 60_000L).toInt()
            val remainingAtMost = attempt.durationMinutes - elapsedMinutes
            val runningThis = observation.running && observation.percent == attempt.percent &&
                observation.remainingMinutes in (remainingAtMost - 1)..attempt.durationMinutes
            when {
                runningThis -> {
                    val started = journal.started(attempt.id, dispatchedAt)
                    if (!records.started(started, dispatchedAt)) journal.detail(attempt.id, "AAPS did not save the recovered TBR")
                }
                // Any other state proves this command is not running now. If it ran briefly and ended,
                // history imports it on its own evidence.
                else -> journal.notStarted(attempt.id, "later status: percent=${observation.percent} remaining=${observation.remainingMinutes}")
            }
        }
    }

    private sealed interface StopOutcome {
        data class Confirmed(val at: Long) : StopOutcome
        data object NotSent : StopOutcome
        data class Unknown(val reason: Reason) : StopOutcome
    }

    private fun stopRunning(): StopOutcome {
        val evidence = link.command(
            YpsoTbrRequest.STOP_PERCENT,
            YpsoTbrRequest.STOP_DURATION_MINUTES,
            effective = { it.idle },
            beforeDispatch = {},
        )
        if (evidence.dispatchedAt == null) return StopOutcome.NotSent
        val after = evidence.after
        if (after == null || !after.idle) return StopOutcome.Unknown(Reason.STOP_NOT_CONFIRMED)
        // The pump ended the TBR no later than acknowledgement, which bounds the record without
        // extending a high TBR past the moment the scheduled rate was proven.
        val stoppedAt = evidence.acknowledgedAt ?: after.observedAt
        stoppedThisCall = true
        if (!records.stopped(stoppedAt)) return StopOutcome.Unknown(Reason.NOT_SAVED)
        journal.stopped(stoppedAt)
        return StopOutcome.Confirmed(stoppedAt)
    }

    private fun newTemporaryId(): Long = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE

    private inline fun exclusive(block: () -> Result): Result {
        if (!active.compareAndSet(false, true)) return Result.NotChanged(Reason.COMMAND_NOT_SENT)
        stoppedThisCall = false
        return try {
            block()
        } catch (error: Exception) {
            // Whatever was dispatched stays journalled; the next status read resolves it.
            Result.Uncertain(Reason.INTERNAL_ERROR, previousStopped = stoppedThisCall)
        } finally {
            active.set(false)
        }
    }
}
