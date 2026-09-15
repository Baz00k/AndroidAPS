package app.aaps.plugins.aps.loop.compose

import app.aaps.core.interfaces.aps.Loop

/** One label/value line of the loop's last run. [value] may be empty while nothing has run yet. */
data class LoopStatusRow(val label: String, val value: CharSequence)

/**
 * Presentation state for [LoopStatusScreen], built by `LoopFragment` from `loop.lastRun`.
 *
 * [timing] rows are split out from [detail] so the screen can group "what was asked for and why" apart
 * from "when the pump was actually told" — the same values the legacy table showed, in the same order.
 * [suggestion] is non-blank while an unaccepted open-loop suggestion exists; [accepting] is true from
 * the moment the accept flow starts until it resolves, and disables the accept button meanwhile.
 */
data class LoopStatusState(
    val lastRun: String = "",
    val source: String = "",
    val running: Boolean = false,
    val suggestion: CharSequence = "",
    val accepting: Boolean = false,
    val detail: List<LoopStatusRow> = emptyList(),
    val timing: List<LoopStatusRow> = emptyList()
)

/**
 * Whether an open-loop suggestion is still waiting to be accepted, i.e. the "Accept temp basal"
 * action should be offered. Same condition the legacy Overview accept button used:
 * the suggestion was never accepted, or a new APS run happened after the last acceptance,
 * and the processed result actually requests a change — while the pump is ready,
 * the loop runs in OPEN_LOOP mode and the loop plugin is enabled.
 */
fun openLoopSuggestionPending(lastRun: Loop.LastRun?, pumpInitialized: Boolean, openLoop: Boolean, loopEnabled: Boolean): Boolean =
    lastRun != null &&
        (lastRun.lastOpenModeAccept == 0L || lastRun.lastOpenModeAccept < lastRun.lastAPSRun) &&
        lastRun.constraintsProcessed?.isChangeRequested == true &&
        pumpInitialized &&
        openLoop &&
        loopEnabled
