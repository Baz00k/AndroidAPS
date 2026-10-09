package app.aaps.implementation.queue.commands

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Exercises the real expiry/frequency gate with a mocked pump, never insulin hardware. */
class SerializedSMBDeadlineTest : TestBaseWithProfile() {

    private val persistence = mock<PersistenceLayer>()
    private val pump = mock<Pump>()

    private val commandInjector = HasAndroidInjector {
        AndroidInjector {
            if (it is CommandSMBBolus) {
                it.aapsLogger = aapsLogger
                it.rh = rh
                it.dateUtil = dateUtil
                it.activePlugin = activePlugin
                it.persistenceLayer = persistence
                it.preferences = preferences
                it.pumpEnactResultProvider = pumpEnactResultProvider
            }
        }
    }

    private fun command(timestamp: Long): Pair<CommandSMBBolus, DetailedBolusInfo> {
        val serialized = RT(runningDynamicIsf = false, units = 0.25, deliverAt = timestamp).serialize()
        val result = apsResultProvider.get().with(RT.deserialize(serialized))
        val info = DetailedBolusInfo().apply { insulin = result.smb; deliverAtTheLatest = result.deliverAt }
        assertThat(info.deliverAtTheLatest).isEqualTo(timestamp)
        assertThat(info.insulin).isEqualTo(0.25)
        whenever(activePlugin.activePump).thenReturn(pump)
        return CommandSMBBolus(commandInjector, info, null) to info
    }

    @Test
    fun `persisted stale or absent deadline cannot call pump`() {
        command(System.currentTimeMillis() - 120_000).first.execute()
        command(0).first.execute()
        verify(pump, never()).deliverTreatment(any())
    }

    @Test
    fun `persisted fresh deadline reaches pump once with unchanged dose and time`() {
        whenever(pump.deliverTreatment(any())).thenReturn(pumpEnactResultProvider.get().success(true).enacted(true))
        val (command, info) = command(System.currentTimeMillis())
        command.execute()
        verify(pump).deliverTreatment(info)
    }

    @Test
    fun `recent recorded insulin blocks even a fresh serialized request`() {
        whenever(dateUtil.now()).thenReturn(now)
        whenever(persistence.getNewestBolus()).thenReturn(BS(timestamp = now, amount = 0.25, type = BS.Type.SMB))
        whenever(preferences.get(app.aaps.core.keys.IntKey.ApsMaxSmbFrequency)).thenReturn(3)
        command(System.currentTimeMillis()).first.execute()
        verify(pump, never()).deliverTreatment(any())
    }
}
