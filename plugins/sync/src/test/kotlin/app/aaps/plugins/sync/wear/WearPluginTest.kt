package app.aaps.plugins.sync.wear

import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.sync.wear.wearintegration.DataHandlerMobile
import app.aaps.plugins.sync.wear.wearintegration.DataLayerListenerServiceMobileHelper
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.verify
import app.aaps.core.interfaces.rx.events.EventProfileAvailabilityChanged
import app.aaps.core.interfaces.rx.events.EventNewBG
import org.mockito.kotlin.never

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

    @Test fun profileAvailabilityRefreshDoesNotNeedCalculationCompletion() {
        wearPlugin.onStart()
        try {
            rxBus.send(EventProfileAvailabilityChanged())
            verify(dataHandlerMobile).resendData("EventProfileAvailabilityChanged")
        } finally {
            wearPlugin.onStop()
        }
    }

    @Test fun eachSensorReadingRefreshesGlucoseWithoutWaitingForLoopOrResendingTherapy() {
        wearPlugin.onStart()
        try {
            rxBus.send(EventNewBG(1_700_000_000_000L))
            verify(dataHandlerMobile).sendGlucose()
            verify(dataHandlerMobile, never()).resendData(org.mockito.kotlin.any())
        } finally {
            wearPlugin.onStop()
        }
        org.mockito.kotlin.clearInvocations(dataHandlerMobile)
        rxBus.send(EventNewBG(null))
        verify(dataHandlerMobile, never()).sendGlucose()
    }
}
