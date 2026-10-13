package app.aaps.pump.ypsopump

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoBlockingReadTest {
    private var now = 1_000L
    private val read = YpsoBlockingRead<String>({ now })

    @Test
    fun `the deadline follows the injected clock and not the wall clock`() {
        val expiries = mutableListOf<Boolean>()

        val completed = read.await(timeoutMs = 3_000) { expired ->
            expiries += expired
            now += 1_000
            expired
        }

        assertFalse(completed)
        assertEquals(listOf(false, false, false, true), expiries)
    }

    @Test
    fun `a poll can end the wait before the deadline`() {
        var polls = 0

        assertFalse(read.await(timeoutMs = 60_000) { ++polls == 2 })
        assertEquals(2, polls)
    }

    @Test
    fun `a read completed during the final poll is reported as completed`() {
        assertTrue(read.await(timeoutMs = 0) { read.complete("late"); true })
        assertEquals("late", read.value)
    }

    @Test
    fun `only the first callback is kept`() {
        read.complete("first")
        read.complete("second")

        assertTrue(read.await(timeoutMs = 0) { error("a completed read is not polled") })
        assertEquals("first", read.value)
    }

    @Test
    fun `a callback from another thread ends the wait`() {
        val callback = Thread { Thread.sleep(150); read.complete("answer") }
        callback.start()

        assertTrue(read.await(timeoutMs = 60_000) { false })
        assertEquals("answer", read.value)
        callback.join()
    }

    @Test
    fun `completion grace waits only for the read`() {
        assertFalse(read.awaitCompletion(graceMs = 1))
        read.complete("done")
        assertTrue(read.awaitCompletion(graceMs = 1))
    }
}
