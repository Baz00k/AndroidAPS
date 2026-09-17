package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.shared.tests.TestBaseWithProfile
import androidx.preference.PreferenceGroup
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class YpsoPreferenceScreenTest : TestBaseWithProfile() {

    @Test
    fun `connection setup preference attaches without crashing`() {
        val plugin = YpsoPumpPlugin(
            aapsLogger,
            rh,
            preferences,
            mock<CommandQueue>(),
            YpsoPumpState(),
            mock<YpsoBleManager>(),
            mock<PumpSync>(),
            rxBus,
            mock<UiInteraction>(),
            pumpEnactResultProvider,
            mock<YpsoProvisioningService>()
        )
        val screen = preferenceManager.createPreferenceScreen(context)

        plugin.addPreferenceScreen(preferenceManager, screen, context, null)

        assertThat(screen.preferenceCount).isEqualTo(2)
        assertThat((0 until screen.preferenceCount).map { (screen.getPreference(it) as PreferenceGroup).preferenceCount })
            .containsExactly(1, 2)
    }
}
