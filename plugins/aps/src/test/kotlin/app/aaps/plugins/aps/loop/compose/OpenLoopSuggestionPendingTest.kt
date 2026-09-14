package app.aaps.plugins.aps.loop.compose

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Loop
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** "Accept temp basal" visibility — the same condition the legacy Overview accept button used. */
class OpenLoopSuggestionPendingTest {

    private val changeRequested: APSResult =
        mock { on { isChangeRequested } doReturn true }

    private fun lastRun(lastOpenModeAccept: Long = 0L, lastAPSRun: Long = 100L): Loop.LastRun =
        Loop.LastRun().also {
            it.lastOpenModeAccept = lastOpenModeAccept
            it.lastAPSRun = lastAPSRun
        }

    private fun pending(lastRun: Loop.LastRun?, changeRequested: Boolean = true, pump: Boolean = true, openLoop: Boolean = true, loopEnabled: Boolean = true) =
        openLoopSuggestionPending(lastRun, if (changeRequested) this.changeRequested else null, pump, openLoop, loopEnabled)

    @Test
    fun `pending when never accepted`() {
        assertThat(pending(lastRun())).isTrue()
    }

    @Test
    fun `pending when a new APS run happened after the last acceptance`() {
        assertThat(pending(lastRun(lastOpenModeAccept = 50L, lastAPSRun = 100L))).isTrue()
    }

    @Test
    fun `not pending after the suggestion was accepted`() {
        assertThat(pending(lastRun(lastOpenModeAccept = 150L, lastAPSRun = 100L))).isFalse()
    }

    @Test
    fun `not pending when nothing was requested`() {
        assertThat(pending(lastRun(), changeRequested = false)).isFalse()
    }

    @Test
    fun `not pending without a run`() {
        assertThat(pending(null)).isFalse()
    }

    @Test
    fun `not pending when the pump is not initialized`() {
        assertThat(pending(lastRun(), pump = false)).isFalse()
    }

    @Test
    fun `not pending outside open loop`() {
        assertThat(pending(lastRun(), openLoop = false)).isFalse()
    }

    @Test
    fun `not pending when the loop plugin is disabled`() {
        assertThat(pending(lastRun(), loopEnabled = false)).isFalse()
    }
}
