package app.aaps.libre3

/**
 * The guarded new/replace-sensor sequence (Tier 3 of report/libre3-ui-plan.md).
 *
 * Four steps, exactly ONE of them irreversible:
 * ```
 *   1 CONFIRM    state the actual consequence, not "are you sure?"
 *   2 SCAN       NFC — irreversible, gated behind step 1
 *   3 WARM_UP    the OLD sensor keeps feeding the loop throughout
 *   4 HANDOVER   automatic at the new sensor's first valid reading
 * ```
 *
 * The design point is OVERLAP. Only one receiver may hold a given sensor, but two sensors are two
 * independent connections — so starting the new one while the old still streams removes the
 * 60-minute no-CGM window entirely. Neither Juggluco nor xDrip does this because neither is a
 * closed loop; for AAPS that window is an hour of dosing blind.
 *
 * No Android types: the sequencing and its guards are the safety-critical part and are unit tested.
 */
class Libre3ScanFlow(private val warmupMinutes: Int = 60) {

    /**
     * NFC command selector. These are NOT interchangeable and the UI must never leave the user
     * guessing which is about to happen: [ACTIVATE] burns a fresh sensor, [TAKEOVER] adopts one
     * that is already running.
     */
    enum class Operation {
        /** `02 A0 7A` — first activation of a new sensor. One shot, irreversible. */
        ACTIVATE,
        /** `02 A8 7A` — adopt a sensor already activated under the same account. */
        TAKEOVER
    }

    /** What the currently-running sensor (if any) means for the decision. */
    sealed interface Current {
        data object None : Current
        data class Running(val remainingMinutes: Int) : Current
    }

    sealed interface Step {
        /**
         * Step 1. [consequence] is deliberately a statement of what will happen, not a yes/no
         * prompt — "are you sure?" carries no information the user did not already have.
         */
        data class Confirm(
            val operation: Operation,
            val consequence: String,
            val overlapping: Boolean
        ) : Step

        /** Step 2. The irreversible one. */
        data class Scan(val operation: Operation) : Step

        /** Step 3. [minutesRemaining] counts down; the old sensor still drives the loop. */
        data class WarmUp(val minutesRemaining: Int, val oldSensorStillFeeding: Boolean) : Step

        /** Step 4. */
        data object Handover : Step

        data class Failed(val reason: String) : Step
    }

    var step: Step? = null
        private set

    /**
     * Begin. With a sensor still running this is the recommended path; without one it warns
     * plainly that the loop will have no CGM for the warm-up.
     */
    fun begin(current: Current, operation: Operation = Operation.ACTIVATE): Step {
        val overlapping = current is Current.Running
        val consequence = when (current) {
            is Current.None ->
                "No sensor is running. The loop will have no CGM data for $warmupMinutes minutes " +
                    "and will dose on insulin on board alone."
            is Current.Running -> {
                val h = current.remainingMinutes / 60
                val m = current.remainingMinutes % 60
                "Your current sensor has ${if (h > 0) "${h}h ${m}m" else "${m}m"} left. Both will " +
                    "run until it expires, so there is no gap. Recommended."
            }
        }
        return Step.Confirm(operation, consequence, overlapping).also { step = it }
    }

    /** Step 1 → 2. Only reachable from [Step.Confirm]: the scan cannot be entered directly. */
    fun confirm(): Step {
        val s = step
        if (s !is Step.Confirm) return Step.Failed("confirm() called from ${s?.javaClass?.simpleName}").also { step = it }
        return Step.Scan(s.operation).also { step = it }
    }

    /** Step 2 → 3. [oldSensorStillFeeding] is what makes the gap zero. */
    fun scanned(oldSensorStillFeeding: Boolean): Step {
        val s = step
        if (s !is Step.Scan) return Step.Failed("scanned() called from ${s?.javaClass?.simpleName}").also { step = it }
        return Step.WarmUp(warmupMinutes, oldSensorStillFeeding).also { step = it }
    }

    /** Progress the warm-up. Resumable: the flow survives the app being closed and reopened. */
    fun warmUpTick(minutesRemaining: Int): Step {
        val s = step
        if (s !is Step.WarmUp) return Step.Failed("warmUpTick() called from ${s?.javaClass?.simpleName}").also { step = it }
        return if (minutesRemaining <= 0) Step.Handover.also { step = it }
        else Step.WarmUp(minutesRemaining, s.oldSensorStillFeeding).also { step = it }
    }

    /** Step 3 → 4, triggered by the new sensor's first valid reading rather than by the clock. */
    fun firstReadingReceived(): Step {
        val s = step
        if (s !is Step.WarmUp) return Step.Failed("firstReadingReceived() from ${s?.javaClass?.simpleName}").also { step = it }
        return Step.Handover.also { step = it }
    }

    fun fail(reason: String): Step = Step.Failed(reason).also { step = it }

    fun reset() {
        step = null
    }

    /** True only in the one state where an irreversible NFC write may be issued. */
    val mayScan: Boolean get() = step is Step.Scan
}
