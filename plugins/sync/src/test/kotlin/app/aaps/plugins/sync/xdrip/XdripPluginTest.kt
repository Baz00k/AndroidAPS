package app.aaps.plugins.sync.xdrip

import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verifyNoInteractions

class XdripPluginTest : TestBaseWithProfile() {

    @Mock lateinit var loop: Loop
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider

    private lateinit var xdripPlugin: XdripPlugin

    init {
        addInjector {
            if (it is AdaptiveSwitchPreference) {
                it.preferences = preferences
                it.config = config
            }
            if (it is AdaptiveIntentPreference) {
                it.preferences = preferences
            }
        }
    }

    @BeforeEach fun prepare() {
        xdripPlugin = XdripPlugin(
            aapsLogger, rh, preferences, profileFunction, profileUtil, aapsSchedulers, context, fabricPrivacy, loop, iobCobCalculator, processedTbrEbData, rxBus, uiInteraction, dateUtil, config, decimalFormatter, glucoseStatusProvider
        )
    }

    @Test
    fun preferenceScreenTest() {
        val screen = preferenceManager.createPreferenceScreen(context)
        xdripPlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }

    @Test fun missingProfileStatusLabelsUnavailableValuesInsteadOfClearingTheStatusLine() {
        whenever(rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)).thenReturn("--")
        whenever(rh.gs(app.aaps.core.ui.R.string.iob_no_active_profile)).thenReturn("No active profile")
        assertThat(xdripPlugin.buildStatusLine(null)).isEqualTo("IOB -- · COB -- · No active profile")
        verifyNoInteractions(iobCobCalculator)
    }
}
