package app.aaps.plugins.main.general.persistentNotification

import app.aaps.core.interfaces.rx.events.EventProfileAvailabilityChanged
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import app.aaps.core.interfaces.rx.AapsSchedulers
import io.reactivex.rxjava3.schedulers.TestScheduler
import java.util.concurrent.TimeUnit

class ProfileAvailabilityNotificationTest : TestBaseWithProfile() {
    private val timer = TestScheduler()
    private val schedulers = object : AapsSchedulers by aapsSchedulers { override val io = timer }
    @Test fun sensorRefreshDoesNotNeedAutosensCompletion() {
        val plugin = spy(PersistentNotificationPlugin(aapsLogger, rh, schedulers, profileFunction, profileUtil,
            fabricPrivacy, activePlugin, iobCobCalculator, processedTbrEbData, rxBus, context, mock(), mock(),
            mock(), config, decimalFormatter, preferences, mock(), dateUtil))
        doNothing().whenever(plugin).triggerNotificationUpdate()
        plugin.onStart()
        try {
            rxBus.send(EventNewBG(1_700_000_000_000L))
            timer.triggerActions()
            verify(plugin).triggerNotificationUpdate()
            timer.advanceTimeBy(10, TimeUnit.MINUTES)
            verify(plugin, org.mockito.kotlin.times(11)).triggerNotificationUpdate()
        } finally {
            plugin.onStop()
        }
        org.mockito.kotlin.clearInvocations(plugin)
        timer.advanceTimeBy(10, TimeUnit.MINUTES)
        verify(plugin, org.mockito.kotlin.never()).triggerNotificationUpdate()
    }

    @Test fun profileAvailabilityRefreshDoesNotNeedAutosensCompletion() {
        val plugin = spy(PersistentNotificationPlugin(aapsLogger, rh, schedulers, profileFunction, profileUtil,
            fabricPrivacy, activePlugin, iobCobCalculator, processedTbrEbData, rxBus, context, mock(), mock(),
            mock(), config, decimalFormatter, preferences, mock(), dateUtil))
        doNothing().whenever(plugin).triggerNotificationUpdate()
        plugin.onStart()
        try {
            rxBus.send(EventProfileAvailabilityChanged())
            timer.triggerActions()
            verify(plugin).triggerNotificationUpdate()
        } finally {
            plugin.onStop()
        }
    }
}
