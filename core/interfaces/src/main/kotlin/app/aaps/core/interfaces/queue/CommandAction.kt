package app.aaps.core.interfaces.queue

/**
 * What a queued command asks the pump to do, with the amounts a person needs to see, so a screen
 * can word it for the user instead of showing the queue's internal [Command.status] text.
 */
sealed interface CommandAction {

    data class Bolus(val insulin: Double, val carbs: Int) : CommandAction
    data class AutomaticBolus(val insulin: Double) : CommandAction
    data class TempBasalPercent(val percent: Int, val durationInMinutes: Int) : CommandAction
    data class TempBasalAbsolute(val unitsPerHour: Double, val durationInMinutes: Int) : CommandAction
    data object CancelTempBasal : CommandAction
    data class ExtendedBolus(val insulin: Double, val durationInMinutes: Int) : CommandAction
    data object CancelExtendedBolus : CommandAction

    /** Does not change insulin delivery; [Command.commandType] says what kind of command it is. */
    data object Other : CommandAction
}
