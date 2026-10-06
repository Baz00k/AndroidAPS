package app.aaps.plugins.main.general.actions

import app.aaps.core.data.model.RM
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ActionsProfileStateTest : TestBaseWithProfile() {

    private val pump: Pump = mock()
    private val source: ProfileSource = mock()
    private val loop: Loop = mock()
    private val persistence: PersistenceLayer = mock()
    private lateinit var fragment: ActionsFragment
    private lateinit var description: PumpDescription
    private val customizedName = "Daily (80%, +1h)"

    @BeforeEach fun setupState() {
        description = PumpDescription().apply {
            isTempBasalCapable = false
            isExtendedBolusCapable = false
            isSetBasalProfileCapable = true
        }
        whenever(pump.pumpDescription).thenReturn(description)
        whenever(pump.isInitialized()).thenReturn(true)
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(activePlugin.activeProfileSource).thenReturn(source)
        whenever(source.profile).thenReturn(profileStoreProvider.get())
        whenever(profileFunction.getProfileName()).thenReturn(customizedName)
        whenever(loop.runningMode).thenReturn(RM.Mode.CLOSED_LOOP)
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

    @Test fun `profile switch button displays the active customized profile`() {
        val action = fragment.buildActionsState().therapy.single { it.id == ActionId.PROFILE_SWITCH }
        assertThat(action.sub).isEqualTo(customizedName)
        assertThat(action.enabled).isTrue()
    }

    @ParameterizedTest
    @EnumSource(RM.Mode::class)
    fun `profile switch follows original AAPS loop mode visibility`(mode: RM.Mode) {
        whenever(loop.runningMode).thenReturn(mode)
        val state = fragment.buildActionsState()
        assertThat(state.therapy.any { it.id == ActionId.PROFILE_SWITCH }).isEqualTo(mode != RM.Mode.DISCONNECTED_PUMP)
    }

    @Test fun `profile switch is hidden while pump is suspended`() {
        whenever(pump.isSuspended()).thenReturn(true)
        val state = fragment.buildActionsState()
        assertThat(state.therapy.none { it.id == ActionId.PROFILE_SWITCH }).isTrue()
    }

    @Test fun `profile switch is hidden before pump initialization`() {
        whenever(pump.isInitialized()).thenReturn(false)
        val state = fragment.buildActionsState()
        assertThat(state.therapy.none { it.id == ActionId.PROFILE_SWITCH }).isTrue()
    }

    @Test fun `profile switch is hidden when pump cannot set a basal profile`() {
        description.isSetBasalProfileCapable = false
        val state = fragment.buildActionsState()
        assertThat(state.therapy.none { it.id == ActionId.PROFILE_SWITCH }).isTrue()
    }

    @Test fun `profile switch is hidden when profile source is unavailable`() {
        whenever(source.profile).thenReturn(null)
        val state = fragment.buildActionsState()
        assertThat(state.therapy.none { it.id == ActionId.PROFILE_SWITCH }).isTrue()
    }

    @Test fun `missing active profile is explicit and does not prevent selecting an initial profile`() {
        whenever(profileFunction.getProfileName()).thenReturn("No profile set")
        val state = fragment.buildActionsState()
        val action = state.therapy.single { it.id == ActionId.PROFILE_SWITCH }
        assertThat(action.sub).isEqualTo("No profile set")
        assertThat(action.enabled).isTrue()
    }
}
