package app.aaps.plugins.main.general.actions

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileSource
import app.aaps.core.interfaces.pump.Pump
import app.aaps.plugins.main.general.actions.compose.ActionId
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ActionsTargetStateTest : TestBaseWithProfile() {

    private val persistence: PersistenceLayer = mock()
    private val loop: Loop = mock()
    private lateinit var fragment: ActionsFragment
    private val start = 1_000_000L
    private val tt = TT(timestamp = start, reason = TT.Reason.ACTIVITY, lowTarget = 108.0, highTarget = 144.0, duration = 1_800_000L)

    @BeforeEach fun setupState() {
        val pump: Pump = mock()
        val source: ProfileSource = mock()
        whenever(pump.pumpDescription).thenReturn(PumpDescription())
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(activePlugin.activeProfileSource).thenReturn(source)
        whenever(loop.runningMode).thenReturn(RM.Mode.DISABLED_LOOP)
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
        whenever(dateUtil.now()).thenReturn(start)
        whenever(dateUtil.untilString(tt.end, rh)).thenReturn("30m")
        fragment = ActionsFragment().also {
            it.activePlugin = activePlugin
            it.profileFunction = profileFunction
            it.profileUtil = profileUtil
            it.dateUtil = dateUtil
            it.config = config
            it.loop = loop
            it.persistenceLayer = persistence
            it.rh = rh
        }
    }

    @Test fun `active target is shown without profile and with disabled loop`() {
        whenever(persistence.getTemporaryTargetActiveAt(start)).thenReturn(tt)
        val card = fragment.buildActionsState().therapy.single { it.id == ActionId.TEMP_TARGET }
        assertThat(card.active).isTrue()
        assertThat(card.enabled).isTrue()
        assertThat(card.cancelable).isFalse() // Tap opens the protected dialog; no direct cancellation.
        assertThat(card.sub).isEqualTo("108 - 144 mg/dL · 30m")
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MMOL)
        assertThat(fragment.buildActionsState().therapy.single().sub).isEqualTo("6.0 - 8.0 mmol/L · 30m")
    }

    @Test fun `cancel or expiry clears active card and preserves creation availability`() {
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(loop.runningMode).thenReturn(RM.Mode.CLOSED_LOOP)
        whenever(persistence.getTemporaryTargetActiveAt(start)).thenReturn(tt)
        assertThat(fragment.buildActionsState().therapy.first().active).isTrue()
        whenever(persistence.getTemporaryTargetActiveAt(start)).thenReturn(null)
        val cancelled = fragment.buildActionsState().therapy.first()
        assertThat(cancelled.id).isEqualTo(ActionId.TEMP_TARGET)
        assertThat(cancelled.active).isFalse()
        assertThat(cancelled.sub).isEmpty()
        whenever(dateUtil.now()).thenReturn(tt.end)
        whenever(persistence.getTemporaryTargetActiveAt(tt.end)).thenReturn(tt)
        assertThat(fragment.buildActionsState().therapy.first().active).isFalse()
        whenever(loop.runningMode).thenReturn(RM.Mode.DISABLED_LOOP)
        assertThat(fragment.buildActionsState().therapy.none { it.id == ActionId.TEMP_TARGET }).isTrue()
    }
}
