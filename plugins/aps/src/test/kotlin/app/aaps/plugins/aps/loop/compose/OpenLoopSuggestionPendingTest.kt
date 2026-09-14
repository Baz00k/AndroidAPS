package app.aaps.plugins.aps.loop.compose

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Loop
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** "Accept temp basal" visibility — the same condition the legacy Overview accept button used. */
class OpenLoopSuggestionPendingTest : TestBase() {

    private fun lastRun(constraintsProcessed: APSResult? = null, lastOpenModeAccept: Long = 0L, lastAPSRun: Long = 100L): Loop.LastRun =
        Loop.LastRun().also {
            it.constraintsProcessed = constraintsProcessed
            it.lastOpenModeAccept = lastOpenModeAccept
            it.lastAPSRun = lastAPSRun
        }

    private fun changeRequested(): APSResult = mock { on { isChangeRequested } doReturn true }

    private fun pending(lastRun: Loop.LastRun?, pump: Boolean = true, openLoop: Boolean = true, loopEnabled: Boolean = true) =
        openLoopSuggestionPending(lastRun, pump, openLoop, loopEnabled)

    @Test
    fun `pending when never accepted`() {
        assertThat(pending(lastRun(constraintsProcessed = changeRequested()))).isTrue()
    }

    @Test
    fun `pending when a new APS run happened after the last acceptance`() {
        assertThat(pending(lastRun(constraintsProcessed = changeRequested(), lastOpenModeAccept = 50L))).isTrue()
    }

    @Test
    fun `not pending after the suggestion was accepted`() {
        assertThat(pending(lastRun(constraintsProcessed = changeRequested(), lastOpenModeAccept = 150L))).isFalse()
    }

    @Test
    fun `not pending when nothing was requested`() {
        assertThat(pending(lastRun())).isFalse()
    }

    @Test
    fun `not pending without a run`() {
        assertThat(pending(null)).isFalse()
    }

    @Test
    fun `not pending when the pump is not initialized`() {
        assertThat(pending(lastRun(constraintsProcessed = changeRequested()), pump = false)).isFalse()
    }

    @Test
    fun `not pending outside open loop`() {
        assertThat(pending(lastRun(constraintsProcessed = changeRequested()), openLoop = false)).isFalse()
    }

    @Test
    fun `not pending when the loop plugin is disabled`() {
        assertThat(pending(lastRun(constraintsProcessed = changeRequested()), loopEnabled = false)).isFalse()
    }
}
