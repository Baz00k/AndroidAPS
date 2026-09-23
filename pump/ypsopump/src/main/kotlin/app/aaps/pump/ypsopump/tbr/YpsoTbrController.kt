package app.aaps.pump.ypsopump.tbr

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The AAPS records the controller maintains for its own starts. Every write is verified by reading
 * the record back, and none sets an end-event identity that would stop history from correcting it.
 */
internal interface YpsoTbrRecords {
    enum class Saved { SAVED, NOT_SAVED, DELETED }

    /** Saves (idempotently, by temporary ID) a percent TBR starting at [timestamp] and reads it back. */
    fun saveStart(attempt: YpsoTbrAttempt, timestamp: Long): Saved

    /** Shortens this start's record so it ends at [end]; true once read back. */
    fun shortenStart(attempt: YpsoTbrAttempt, end: Long): Boolean

    /**
     * Fresh status contradicts every YpsoPump record still active at [observation]: shortens them by
     * duration only, so pump history can still move each end to the pump's own time.
     */
    fun reconcileWith(observation: YpsoTbrObservation)
}

/**
 * Enacts TBR requests with only measured pump semantics: a running TBR cannot be replaced, so a
 * change is a confirmed stop followed by a start.
 *
 * Every command is journalled before it can leave the phone and resolved only by pump status. AAPS
 * records only what status proved. A TBR the controller did not start is never cut locally: pump
 * history ends it at the pump's own time. The result reports success only when the pump is proven to
 * run exactly what was asked, because the loop delivers a paired SMB on any success.
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

        /** The pump runs no TBR. [enacted] when this call stopped one. */
        data class Stopped(val enacted: Boolean) : Result

        /** The request was not fulfilled. [pumpChanged] when this call still changed the pump. */
        data class Failed(val reason: Reason, val pumpChanged: Boolean) : Result
    }

    enum class Reason {
        BUSY,
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
    val isBusy: Boolean get() = active.get()

    /** Whether any command or record is still unproven; drives the persistent warning. */
    fun hasUnresolved(): Boolean = journal.all().any { it.unresolved }

    fun start(request: YpsoTbrRequest, type: String): Result = exclusive { changed ->
        val before = link.status() ?: return@exclusive Result.Failed(Reason.PUMP_UNREADABLE, false)
        resolve(before)
        if (!before.running) return@exclusive Result.Failed(Reason.PUMP_STOPPED, false)
        val serial = serialNumber().takeIf(String::isNotBlank) ?: return@exclusive Result.Failed(Reason.NOT_SET_UP, false)
        val baseline = historyBaseline() ?: return@exclusive Result.Failed(Reason.HISTORY_NOT_READY, false)
        if (!before.idle) {
            when (val stop = stop(serial)) {
                StopOutcome.CONFIRMED -> changed()
                StopOutcome.NOT_SENT -> return@exclusive Result.Failed(Reason.COMMAND_NOT_SENT, false)
                StopOutcome.UNKNOWN -> return@exclusive Result.Failed(Reason.STOP_NOT_CONFIRMED, true)
            }
        }
        val attempt = newAttempt(YpsoTbrAttempt.Kind.START, serial, request.percent, request.durationMinutes, type, baseline)
        val evidence = send(attempt) { it.justStarted(request) }
        val current = checkNotNull(journal.find(attempt.id))
        when {
            current.state == YpsoTbrAttempt.State.NO_EFFECT ->
                Result.Failed(if (current.dispatchedAt == null) Reason.COMMAND_NOT_SENT else Reason.START_REJECTED, changed.value)
            current.state != YpsoTbrAttempt.State.EFFECTIVE -> Result.Failed(Reason.START_NOT_CONFIRMED, true)
            !account(current) -> Result.Failed(Reason.NOT_SAVED, true)
            evidence.after?.justStarted(request) == true -> Result.Started(request)
            else -> Result.Failed(Reason.START_NOT_CONFIRMED, true)
        }
    }

    fun cancel(): Result = exclusive { _ ->
        val before = link.status() ?: return@exclusive Result.Failed(Reason.PUMP_UNREADABLE, false)
        resolve(before)
        // Nothing runs on the pump. Any AAPS record of a TBR it did not start is ended by history.
        if (!before.running || before.idle) return@exclusive Result.Stopped(enacted = false)
        val serial = serialNumber().takeIf(String::isNotBlank) ?: return@exclusive Result.Failed(Reason.NOT_SET_UP, false)
        when (stop(serial)) {
            StopOutcome.CONFIRMED -> Result.Stopped(enacted = true)
            StopOutcome.NOT_SENT -> Result.Failed(Reason.COMMAND_NOT_SENT, false)
            StopOutcome.UNKNOWN -> Result.Failed(Reason.STOP_NOT_CONFIRMED, true)
        }
    }

    /**
     * Resolves journalled commands from a fresh routine status read and replays unsaved accounting.
     * Skipped while a command runs; that command resolves its own attempts from same-link status.
     */
    fun onStatus(observation: YpsoTbrObservation) {
        if (!active.compareAndSet(false, true)) return
        try {
            resolve(observation)
        } finally {
            active.set(false)
        }
    }

    private fun resolve(observation: YpsoTbrObservation) {
        for (attempt in journal.all()) {
            if (attempt.awaitsStatus && attempt.createdAt < observation.observedAt) resolveFromStatus(attempt, observation)
        }
        for (attempt in journal.all()) if (attempt.awaitsAccounting) account(attempt)
        records.reconcileWith(observation)
    }

    /** A command whose same-link status never arrived. Its effect can only still be visible now. */
    private fun resolveFromStatus(attempt: YpsoTbrAttempt, observation: YpsoTbrObservation) {
        val dispatchedAt = attempt.dispatchedAt
            ?: return run { journal.noEffect(attempt.id, "no dispatch was recorded") }
        when (attempt.kind) {
            YpsoTbrAttempt.Kind.START -> {
                val elapsed = ((observation.observedAt - dispatchedAt) / MINUTE).toInt()
                val runningThis = observation.running && observation.percent == attempt.percent &&
                    observation.remainingMinutes in (attempt.durationMinutes - elapsed - 1)..attempt.durationMinutes
                if (runningThis) {
                    // It began after dispatch and before the minutes it had already run by this read.
                    val by = observation.observedAt - (attempt.durationMinutes - observation.remainingMinutes - 1).coerceAtLeast(0) * MINUTE
                    journal.effective(attempt.id, dispatchedAt, by)
                }
                // It may have run briefly and ended; history then imports it on its own evidence.
                else journal.noEffect(attempt.id, "later status: percent=${observation.percent} remaining=${observation.remainingMinutes}")
            }
            // Idle now does not prove this stop ended anything; history carries the real end time.
            YpsoTbrAttempt.Kind.STOP -> journal.noEffect(attempt.id, "stop outcome resolved by history")
        }
    }

    /** Saves a proven start and applies any AAPS stop that already ended it. Safe to repeat. */
    private fun account(attempt: YpsoTbrAttempt): Boolean {
        if (!attempt.awaitsAccounting) return true
        val start = checkNotNull(attempt.effectiveAt)
        when (records.saveStart(attempt, start)) {
            YpsoTbrRecords.Saved.SAVED -> Unit
            YpsoTbrRecords.Saved.NOT_SAVED -> {
                journal.detail(attempt.id, "AAPS did not save the started TBR")
                return false
            }
            YpsoTbrRecords.Saved.DELETED -> {
                journal.abandonAccounting(attempt.id, "the TBR record was removed in AAPS")
                return true
            }
        }
        if (attempt.stoppedAt != null && !records.shortenStart(attempt, attempt.stoppedAt)) {
            journal.detail(attempt.id, "AAPS did not save the TBR end")
            return false
        }
        journal.accounted(attempt.id)
        return true
    }

    private enum class StopOutcome { CONFIRMED, NOT_SENT, UNKNOWN }

    private var pendingCuts: List<YpsoTbrAttempt> = emptyList()

    private fun stop(serial: String): StopOutcome {
        val attempt = newAttempt(YpsoTbrAttempt.Kind.STOP, serial, YpsoTbrRequest.STOP_PERCENT, YpsoTbrRequest.STOP_DURATION_MINUTES, "", null)
        pendingCuts = emptyList()
        send(attempt) { it.idle }
        val current = checkNotNull(journal.find(attempt.id))
        return when {
            current.state == YpsoTbrAttempt.State.EFFECTIVE -> {
                // AAPS's own starts are ended at the pump-confirmed stop time and replayed until saved.
                // Any other record is corrected by the next idle status and, exactly, by history.
                for (ended in pendingCuts) account(ended)
                StopOutcome.CONFIRMED
            }
            current.state == YpsoTbrAttempt.State.NO_EFFECT && current.dispatchedAt == null -> StopOutcome.NOT_SENT
            else -> StopOutcome.UNKNOWN
        }
    }

    /** Sends one journalled command and resolves it from same-link status where that status proves it. */
    private fun send(attempt: YpsoTbrAttempt, effective: (YpsoTbrObservation) -> Boolean): YpsoTbrCommandEvidence {
        journal.prepare(attempt)
        val evidence = link.command(
            attempt.percent,
            attempt.durationMinutes,
            effective,
            beforeDispatch = { at -> journal.dispatched(attempt.id, at) },
        )
        val dispatchedAt = journal.find(attempt.id)?.dispatchedAt
        val after = evidence.after
        when {
            dispatchedAt == null -> journal.noEffect(attempt.id, "command was not sent: ${evidence.result}")
            after != null && effective(after) && evidence.result !is YpsoTbrWriteResult.Rejected -> {
                // Acknowledgement is the latest moment the command can have taken effect; without it
                // the dispatch time is used, bounded by the status that proved the effect.
                val at = evidence.acknowledgedAt ?: dispatchedAt
                if (attempt.kind == YpsoTbrAttempt.Kind.STOP) pendingCuts = journal.stopEffective(attempt.id, at)
                else journal.effective(attempt.id, at, evidence.acknowledgedAt ?: after.observedAt)
            }
            after != null && !effective(after) && evidence.result is YpsoTbrWriteResult.Rejected ->
                journal.noEffect(attempt.id, "${evidence.result.reason} confirmed by status")
            after != null && !effective(after) && attempt.kind == YpsoTbrAttempt.Kind.START && (after.idle || !after.running) ->
                journal.noEffect(attempt.id, "status after start: percent=${after.percent} remaining=${after.remainingMinutes}")
            else -> Unit // Stays DISPATCHED: the next status read resolves it.
        }
        return evidence
    }

    private fun newAttempt(kind: YpsoTbrAttempt.Kind, serial: String, percent: Int, minutes: Int, type: String, baseline: Long?) =
        YpsoTbrAttempt(
            id = "tbr-${UUID.randomUUID()}",
            kind = kind,
            pumpSerial = serial,
            percent = percent,
            durationMinutes = minutes,
            type = type,
            temporaryId = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE,
            baselinePumpId = baseline,
            createdAt = now(),
        )

    /** Tracks whether this call already changed the pump, so a later failure reports it truthfully. */
    private class Changed {
        var value = false
        operator fun invoke() { value = true }
    }

    private inline fun exclusive(block: (Changed) -> Result): Result {
        if (!active.compareAndSet(false, true)) return Result.Failed(Reason.BUSY, false)
        val changed = Changed()
        return try {
            block(changed)
        } catch (error: Exception) {
            // Whatever was dispatched stays journalled; the next status read resolves it.
            Result.Failed(Reason.INTERNAL_ERROR, true)
        } finally {
            active.set(false)
        }
    }

    companion object {
        private const val MINUTE = 60_000L
    }
}
