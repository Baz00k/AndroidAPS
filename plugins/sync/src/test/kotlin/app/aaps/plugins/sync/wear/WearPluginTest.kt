package app.aaps.plugins.sync.wear

import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.sync.wear.wearintegration.DataHandlerMobile
import app.aaps.plugins.sync.wear.wearintegration.DataLayerListenerServiceMobileHelper
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import app.aaps.core.interfaces.rx.events.EventProfileAvailabilityChanged
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.data.iob.CobInfo
import app.aaps.core.objects.ui.TherapyDisplay
import app.aaps.core.objects.ui.BasalDisplay

class WearPluginTest : TestBaseWithProfile() {

    @Mock lateinit var dataHandlerMobile: DataHandlerMobile
    @Mock lateinit var dataLayerListenerServiceMobileHelper: DataLayerListenerServiceMobileHelper

    private lateinit var wearPlugin: WearPlugin

    init {
        addInjector {
            if (it is AdaptiveSwitchPreference) {
                it.preferences = preferences
                it.config = config
            }
        }
    }

    @BeforeEach fun prepare() {
        wearPlugin = WearPlugin(aapsLogger, rh, aapsSchedulers, preferences, fabricPrivacy, rxBus, context, dataHandlerMobile, dataLayerListenerServiceMobileHelper, config)
    }

    @Test
    fun preferenceScreenTest() {
        val screen = preferenceManager.createPreferenceScreen(context)
        wearPlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }

    @Test fun profileAvailabilityRefreshReplacesNumericStatusWithoutCalculationCompletion() {
        whenever(rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)).thenReturn("--")
        whenever(rh.gs(app.aaps.core.ui.R.string.iob_no_active_profile)).thenReturn("No active profile")
        whenever(rh.gs(app.aaps.core.ui.R.string.format_carbs)).thenReturn("%d g")
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(iobCobCalculator.calculateIobFromBolus()).thenReturn(IobTotal(1).also { it.iob = 0.3 })
        whenever(iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(1))
        whenever(iobCobCalculator.getCobInfo("wear test")).thenReturn(CobInfo(1, 12.0, 0.0))
        var published = TherapyDisplay.load(profileFunction, iobCobCalculator, rh, decimalFormatter, "wear test", iobFormat = { "$it" }) { BasalDisplay("0.80 U/h") }
        assertThat(published.iob.total).isEqualTo("0.3")
        doAnswer {
            published = TherapyDisplay.load(profileFunction, iobCobCalculator, rh, decimalFormatter, "wear test", iobFormat = { "$it" }) { BasalDisplay("0.80 U/h") }
            null
        }.whenever(dataHandlerMobile).resendData("EventProfileAvailabilityChanged")
        wearPlugin.onStart()
        try {
            whenever(profileFunction.getProfile()).thenReturn(null)
            rxBus.send(EventProfileAvailabilityChanged())
            assertThat(published.iob.total).isEqualTo("--")
            assertThat(published.cob.value).isEqualTo("--")
            assertThat(published.basal.rate).isEqualTo("--")
        } finally {
            wearPlugin.onStop()
        }
    }
}
