package app.aaps.core.interfaces.queue

/**
 * The running command and the commands waiting behind it, read together so they describe one
 * moment: a command is never both running and waiting, and a command removed from the queue is
 * never listed after its replacement.
 */
data class QueueSnapshot(val performing: Command?, val queued: List<Command>)
