package app.aaps.core.interfaces.queue

/**
 * What a queued command asks the pump to do, with the amounts a person needs to see, so a screen
 * can word it for the user instead of showing the queue's internal [Command.status] text.
 */
sealed interface CommandAction {

    /** Insulin only: the queue records carbs separately, after the bolus is delivered. */
    data class Bolus(val insulin: Double) : CommandAction
    data class AutomaticBolus(val insulin: Double) : CommandAction
    data class TempBasalPercent(val percent: Int, val durationInMinutes: Int) : CommandAction
    data class TempBasalAbsolute(val unitsPerHour: Double, val durationInMinutes: Int) : CommandAction
    data object CancelTempBasal : CommandAction
    data class ExtendedBolus(val insulin: Double, val durationInMinutes: Int) : CommandAction
    data object CancelExtendedBolus : CommandAction

    /**
     * No structured description; [Command.commandType] says what kind of command it is. This does not
     * mean delivery is unaffected: stopping the pump or setting a profile also use it.
     */
    data object Other : CommandAction
}
