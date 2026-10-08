package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.queue.Command
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
 * Housekeeping commands get plain wording that says what the pump is doing. Commands that move
 * insulin keep their own description, which carries the dose and its units.
 */
private fun describe(command: Command, rh: ResourceHelper): String = when (command.commandType) {
    Command.CommandType.READSTATUS   -> rh.gs(
        when ((command.callback as? YpsoPumpPlugin.ConfigurationReadCallback)?.reason) {
            YpsoPumpPlugin.PROFILE_READ_REASON  -> R.string.ypsopump_queue_read_profiles
            YpsoPumpPlugin.ACTIVE_PROGRAM_REASON -> R.string.ypsopump_queue_check_program
            else                                 -> R.string.ypsopump_queue_read_status
        }
    )

    Command.CommandType.BASAL_PROFILE -> rh.gs(R.string.ypsopump_queue_check_profile)
    else                              -> command.status()
}
