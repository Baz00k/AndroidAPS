package app.aaps.plugins.main.general.actions

import app.aaps.core.data.model.RM
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileSource
import app.aaps.core.interfaces.pump.Pump
import app.aaps.plugins.main.R
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

    enum class Availability { READY, UNSUPPORTED, SOURCE_UNAVAILABLE, UNINITIALIZED, DISCONNECTED, SUSPENDED }

    private val pump: Pump = mock()
    private val source: ProfileSource = mock()
    private val loop: Loop = mock()
    private lateinit var fragment: ActionsFragment
    private val description = PumpDescription().apply {
        isTempBasalCapable = false
        isExtendedBolusCapable = false
        isSetBasalProfileCapable = true
    }

    @BeforeEach fun setupState() {
        whenever(pump.pumpDescription).thenReturn(description)
        whenever(pump.isInitialized()).thenReturn(true)
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(activePlugin.activeProfileSource).thenReturn(source)
        whenever(source.profile).thenReturn(profileStoreProvider.get())
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(profileFunction.getProfileName()).thenReturn("Daily (80%, +1h)")
        whenever(loop.runningMode).thenReturn(RM.Mode.CLOSED_LOOP)
        fragment = ActionsFragment().also {
            it.activePlugin = activePlugin
            it.profileFunction = profileFunction
            it.profileUtil = profileUtil
            it.dateUtil = dateUtil
            it.config = config
            it.loop = loop
            it.persistenceLayer = mock<PersistenceLayer>()
            it.rh = rh
        }
    }

    @ParameterizedTest
    @EnumSource(Availability::class)
    fun `profile stays visible and unavailable switching has a reason`(availability: Availability) {
        val reasonResource = when (availability) {
            Availability.READY -> null
            Availability.UNSUPPORTED -> {
                description.isSetBasalProfileCapable = false
                R.string.actions_profile_switch_unsupported
            }
            Availability.SOURCE_UNAVAILABLE -> {
                whenever(source.profile).thenReturn(null)
                R.string.actions_profile_source_unavailable
            }
            Availability.UNINITIALIZED -> {
                whenever(pump.isInitialized()).thenReturn(false)
                R.string.actions_profile_pump_not_initialized
            }
            Availability.DISCONNECTED -> {
                whenever(loop.runningMode).thenReturn(RM.Mode.DISCONNECTED_PUMP)
                app.aaps.core.ui.R.string.pump_disconnected
            }
            Availability.SUSPENDED -> {
                whenever(pump.isSuspended()).thenReturn(true)
                app.aaps.core.ui.R.string.pump_suspended
            }
        }
        reasonResource?.let { whenever(rh.gs(it)).thenReturn("Unavailable reason") }

        val profile = fragment.buildActionsState().therapy.single { it.id == ActionId.PROFILE_SWITCH }
        assertThat(profile.sub).isEqualTo("Daily (80%, +1h)")
        assertThat(profile.enabled).isEqualTo(availability == Availability.READY)
        assertThat(profile.unavailableReason).isEqualTo(if (availability == Availability.READY) "" else "Unavailable reason")
    }

    @Test fun `missing active profile is explicit and permits an initial switch`() {
        whenever(profileFunction.getProfile()).thenReturn(null)
        whenever(rh.gs(app.aaps.core.ui.R.string.no_profile_set)).thenReturn("No profile set")

        val profile = fragment.buildActionsState().therapy.single { it.id == ActionId.PROFILE_SWITCH }
        assertThat(profile.sub).isEqualTo("No profile set")
        assertThat(profile.enabled).isTrue()
    }
}
