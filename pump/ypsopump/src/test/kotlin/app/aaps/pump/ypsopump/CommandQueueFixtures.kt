package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.QueueSnapshot
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** A queue with nothing running or waiting. A bare mock would return no snapshot at all. */
internal fun idleCommandQueue(): CommandQueue = mock {
    on { snapshot() } doReturn QueueSnapshot(null, emptyList())
}
