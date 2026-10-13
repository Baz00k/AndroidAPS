package app.aaps.pump.ypsopump

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Waits on a command thread for one asynchronous pump read. Deadlines use the injected [clock]
 * (elapsed milliseconds), so they do not depend on wall-clock changes and can be driven in tests.
 */
internal class YpsoBlockingRead<T>(private val clock: () -> Long) {
    private val latch = CountDownLatch(1)
    @Volatile private var result: T? = null

    val isComplete: Boolean get() = latch.count == 0L

    /** The value delivered by [complete]; meaningful once [isComplete]. */
    val value: T? get() = result

    /** The read's callback. Only the first call counts. */
    @Synchronized
    fun complete(value: T) {
        if (isComplete) return
        result = value
        latch.countDown()
    }

    /**
     * Polls until the read completes or [timeoutMs] passes. [onPoll] runs after every unanswered poll
     * with whether the deadline has passed, and ends the wait early by returning true. True when the
     * read completed.
     */
    fun await(timeoutMs: Long, onPoll: (expired: Boolean) -> Boolean = { it }): Boolean {
        val deadline = clock() + timeoutMs
        while (!latch.await(POLL_MS, TimeUnit.MILLISECONDS)) {
            if (onPoll(clock() >= deadline)) return isComplete
        }
        return true
    }

    /** Waits up to [graceMs] for a read that can no longer be cancelled; true when it completed. */
    fun awaitCompletion(graceMs: Long): Boolean = latch.await(graceMs, TimeUnit.MILLISECONDS)

    /** Waits for a read whose callback is already running. */
    fun awaitCompletion() = latch.await()

    companion object {
        const val POLL_MS = 100L
    }
}
