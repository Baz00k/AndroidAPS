package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandAction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.ypsopump.compose.QueueItem

/**
 * What the pump tab says the command queue is doing: the running command first, then every waiting
 * command in execution order. Empty means idle.
 */
internal fun pumpActivityItems(commandQueue: CommandQueue, rh: ResourceHelper): List<QueueItem> {
    // One snapshot, not two reads: a cancel that replaces a waiting command must never be shown
    // next to the command it superseded.
    val (running, waiting) = commandQueue.snapshot()
    return buildList {
        if (running != null) add(QueueItem(describe(running, rh), true, rh.gs(R.string.ypsopump_queue_running)))
        waiting.forEach { add(QueueItem(describe(it, rh), false, rh.gs(R.string.ypsopump_queue_waiting))) }
    }
}

/**
 * Every row names one task, worded the same way whether it moves insulin or not, and never as an
 * instruction to the user. Doses keep their units. An absolute temporary basal is shown as a request:
 * Ypso converts it to a percentage of the scheduled rate when it runs, which may round, cap or cancel.
 */
private fun describe(command: Command, rh: ResourceHelper): String = when (val action = command.action) {
    is CommandAction.Bolus             -> rh.gs(R.string.ypsopump_queue_bolus, action.insulin)
    is CommandAction.AutomaticBolus    -> rh.gs(R.string.ypsopump_queue_automatic_bolus, action.insulin)
    is CommandAction.TempBasalPercent  -> rh.gs(R.string.ypsopump_queue_temp_basal_percent, action.percent, duration(action.durationInMinutes, rh))
    is CommandAction.TempBasalAbsolute -> rh.gs(R.string.ypsopump_queue_temp_basal_absolute, action.unitsPerHour, duration(action.durationInMinutes, rh))
    CommandAction.CancelTempBasal      -> rh.gs(R.string.ypsopump_queue_cancel_temp_basal)
    is CommandAction.ExtendedBolus     -> rh.gs(R.string.ypsopump_queue_extended_bolus, action.insulin, duration(action.durationInMinutes, rh))
    CommandAction.CancelExtendedBolus  -> rh.gs(R.string.ypsopump_queue_cancel_extended_bolus)
    CommandAction.Other                -> rh.gs(
        when (command.commandType) {
            Command.CommandType.READSTATUS    -> when ((command.callback as? YpsoPumpPlugin.ConfigurationReadCallback)?.reason) {
                YpsoPumpPlugin.PROFILE_READ_REASON   -> R.string.ypsopump_queue_read_profiles
                YpsoPumpPlugin.ACTIVE_PROGRAM_REASON -> R.string.ypsopump_queue_check_program
                else                                 -> R.string.ypsopump_queue_read_status
            }

            Command.CommandType.BASAL_PROFILE -> R.string.ypsopump_queue_check_profile
            Command.CommandType.LOAD_HISTORY,
            Command.CommandType.LOAD_EVENTS,
            Command.CommandType.LOAD_TDD      -> R.string.ypsopump_queue_read_history

            else                              -> R.string.ypsopump_queue_other
        }
    )
}

private fun duration(minutes: Int, rh: ResourceHelper): String = when {
    minutes < 60      -> rh.gs(R.string.ypsopump_queue_minutes, minutes)
    minutes % 60 == 0 -> rh.gs(R.string.ypsopump_queue_hours, minutes / 60)
    else              -> rh.gs(R.string.ypsopump_queue_hours_minutes, minutes / 60, minutes % 60)
}
