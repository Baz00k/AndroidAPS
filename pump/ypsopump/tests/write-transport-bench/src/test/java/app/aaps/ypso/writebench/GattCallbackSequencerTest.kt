package app.aaps.ypso.writebench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GattCallbackSequencerTest {

    @Test
    fun `queued callback runs only after platform callback returns`() {
        val queued = ArrayDeque<Runnable>()
        val sequencer = GattCallbackSequencer { action ->
            queued += action
            true
        }
        val order = mutableListOf<String>()

        sequencer.afterPlatformCallback {
            order += "follow-up"
        }

        assertTrue(order.isEmpty())
        assertEquals(1, queued.size)
        queued.removeFirst().run()
        assertEquals(listOf("follow-up"), order)
    }

    @Test
    fun `synchronous poster is rejected as re-entrant`() {
        val failure = runCatching {
            GattCallbackSequencer { action ->
                action.run()
                true
            }.afterPlatformCallback { }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `post failure is reported`() {
        val failure = runCatching {
            GattCallbackSequencer { false }.afterPlatformCallback { }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }
}
