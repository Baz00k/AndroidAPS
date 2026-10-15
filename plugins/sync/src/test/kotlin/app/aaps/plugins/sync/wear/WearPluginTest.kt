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
import app.aaps.core.interfaces.rx.AapsSchedulers
import io.reactivex.rxjava3.schedulers.TestScheduler
import java.util.concurrent.TimeUnit

class WearPluginTest : TestBaseWithProfile() {

    @Mock lateinit var dataHandlerMobile: DataHandlerMobile
    @Mock lateinit var dataLayerListenerServiceMobileHelper: DataLayerListenerServiceMobileHelper

    private lateinit var wearPlugin: WearPlugin
    private val timer = TestScheduler()
    private val schedulers = object : AapsSchedulers by aapsSchedulers { override val io = timer }

    init {
        addInjector {
            if (it is AdaptiveSwitchPreference) {
                it.preferences = preferences
                it.config = config
            }
        }
    }

    @BeforeEach fun prepare() {
        wearPlugin = WearPlugin(aapsLogger, rh, schedulers, preferences, fabricPrivacy, rxBus, context, dataHandlerMobile, dataLayerListenerServiceMobileHelper, config)
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
            timer.triggerActions()
            verify(dataHandlerMobile).resendData("EventProfileAvailabilityChanged")
        } finally {
            wearPlugin.onStop()
        }
    }

    @Test fun eachSensorReadingRefreshesGlucoseWithoutWaitingForLoopOrResendingTherapy() {
        wearPlugin.onStart()
        try {
            rxBus.send(EventNewBG(1_700_000_000_000L))
            timer.triggerActions()
            verify(dataHandlerMobile).sendGlucose()
            timer.advanceTimeBy(10, TimeUnit.MINUTES)
            verify(dataHandlerMobile, org.mockito.kotlin.times(11)).sendGlucose()
            verify(dataHandlerMobile, never()).resendData(org.mockito.kotlin.any())
        } finally {
            wearPlugin.onStop()
        }
        org.mockito.kotlin.clearInvocations(dataHandlerMobile)
        rxBus.send(EventNewBG(null))
        timer.advanceTimeBy(10, TimeUnit.MINUTES)
        verify(dataHandlerMobile, never()).sendGlucose()
        wearPlugin.onStart()
        try {
            timer.advanceTimeBy(1, TimeUnit.MINUTES)
            verify(dataHandlerMobile).sendGlucose()
        } finally {
            wearPlugin.onStop()
        }
    }
}
