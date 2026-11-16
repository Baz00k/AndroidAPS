package app.aaps.plugins.main.general.actions

import androidx.fragment.app.FragmentActivity
import app.aaps.core.data.model.TB
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.main.R
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class TempBasalActionsTest {

    private val now = 1_000_000L
    private val tb = TB(timestamp = now - 60_000L, type = TB.Type.NORMAL, isAbsolute = false, rate = 50.0, duration = 3_600_000L)
    private val rh: ResourceHelper = mock()
    private val processed: ProcessedTbrEbData = mock()
    private val commandQueue: CommandQueue = mock()
    private val dateUtil: DateUtil = mock()
    private val uel: UserEntryLogger = mock()
    private val activity: FragmentActivity = mock()
    private val dialogs = mutableListOf<Pair<String, Pair<Runnable, Runnable>>>()
    private lateinit var actions: TempBasalActions

    @BeforeEach fun setUp() {
        whenever(dateUtil.now()).thenReturn(now)
        whenever(rh.gs(app.aaps.core.ui.R.string.cancel)).thenReturn("Cancel")
        whenever(rh.gs(app.aaps.core.ui.R.string.tempbasal_label)).thenReturn("Temp basal")
        whenever(rh.gs(app.aaps.core.ui.R.string.formatPercent, 50.0)).thenReturn("50%")
        whenever(rh.gs(R.string.confirm_cancel_temp_basal, "50%")).thenReturn("Stop the active temp basal of 50%?")
        actions = TempBasalActions(rh, processed, commandQueue, dateUtil, uel, mock()).also {
            it.confirm = { _, _, message, ok, cancel -> dialogs += message to (ok to cancel) }
        }
    }

    @Test
    fun `a tap asks once, naming the temp basal, and cancels only on confirmation`() {
        whenever(processed.getTempBasalIncludingConvertedExtended(now)).thenReturn(tb)
        val guard = TempBasalActions.CancelGuard()

        actions.confirmCancel(activity, Sources.Actions, guard)
        actions.confirmCancel(activity, Sources.Actions, guard)

        assertThat(dialogs.map { it.first }).containsExactly("Stop the active temp basal of 50%?")
        verify(commandQueue, never()).cancelTempBasal(any(), any(), anyOrNull())

        dialogs.single().second.first.run()

        verify(uel).log(Action.CANCEL_TEMP_BASAL, Sources.Actions)
        verify(commandQueue).cancelTempBasal(eq(true), eq(false), anyOrNull())
    }

    @Test
    fun `declining cancels nothing and lets the next tap ask again`() {
        whenever(processed.getTempBasalIncludingConvertedExtended(now)).thenReturn(tb)
        val guard = TempBasalActions.CancelGuard()

        actions.confirmCancel(activity, Sources.Actions, guard)
        dialogs.single().second.second.run()
        actions.confirmCancel(activity, Sources.Actions, guard)

        assertThat(dialogs).hasSize(2)
        verify(commandQueue, never()).cancelTempBasal(any(), any(), anyOrNull())
    }

    @Test
    fun `a temp basal that ended while asking is not cancelled`() {
        whenever(processed.getTempBasalIncludingConvertedExtended(now)).thenReturn(tb, null)

        actions.confirmCancel(activity, Sources.Actions, TempBasalActions.CancelGuard())
        dialogs.single().second.first.run()

        verifyNoInteractions(uel)
        verify(commandQueue, never()).cancelTempBasal(any(), any(), anyOrNull())
    }
}
