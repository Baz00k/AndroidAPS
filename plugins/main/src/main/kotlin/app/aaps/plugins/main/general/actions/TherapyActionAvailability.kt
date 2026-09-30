package app.aaps.plugins.main.general.actions

import app.aaps.core.data.model.RM

/**
 * When the temporary-target and extended-bolus actions can be offered. Shared by the Actions tab and
 * the Home "+" menu so the two surfaces never disagree about what is possible right now.
 */
object TherapyActionAvailability {

    /** An existing target must stay reachable (to edit or cancel it) even while the loop is stopped. */
    fun tempTarget(targetActive: Boolean, hasProfile: Boolean, mode: RM.Mode): Boolean =
        targetActive || (hasProfile && mode.isLoopRunning())

    fun extendedBolus(
        pumpCapable: Boolean,
        pumpInitialized: Boolean,
        pumpSuspended: Boolean,
        mode: RM.Mode,
        fakingTempsByExtendedBoluses: Boolean,
        client: Boolean
    ): Boolean =
        pumpCapable && pumpInitialized && !pumpSuspended && mode != RM.Mode.DISCONNECTED_PUMP &&
            !fakingTempsByExtendedBoluses && !client
}
