package app.aaps.plugins.main.general.persistentNotification

import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventProfileAvailabilityChanged
import app.aaps.shared.tests.TestBaseWithProfile
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.TimeUnit

class PersistentNotificationPluginTest : TestBaseWithProfile() {
    private val timer = TestScheduler()
    private val schedulers = object : AapsSchedulers by aapsSchedulers { override val io = timer }

    private fun startedPlugin() = spy(PersistentNotificationPlugin(aapsLogger, rh, schedulers, profileFunction, profileUtil,
        fabricPrivacy, activePlugin, iobCobCalculator, processedTbrEbData, rxBus, context, mock(), mock(),
        mock(), mock(), config, decimalFormatter, preferences, mock(), dateUtil)).also {
        doNothing().whenever(it).triggerNotificationUpdate()
        doNothing().whenever(it).updateNotification()
        it.onStart()
    }

    @Test fun profileAvailabilityRefreshDoesNotNeedAutosensCompletion() {
        val plugin = startedPlugin()
        try {
            rxBus.send(EventProfileAvailabilityChanged())
            timer.triggerActions()
            verify(plugin).triggerNotificationUpdate()
        } finally {
            plugin.onStop()
        }
    }

    @Test fun newReadingRefreshesWithoutAutosensAndAgeTimerOnlyRedraws() {
        val plugin = startedPlugin()
        try {
            rxBus.send(EventNewBG(1_700_000_000_000L))
            timer.advanceTimeBy(10, TimeUnit.MINUTES)
            verify(plugin).triggerNotificationUpdate()
            verify(plugin, times(10)).updateNotification()
        } finally {
            plugin.onStop()
        }
    }
}
