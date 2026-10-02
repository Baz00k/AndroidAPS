package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.lifecycle.AppLifecycle
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.pump.PumpEnactResultObject
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.shared.tests.AAPSLoggerTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * The provisioning service publishes availability while holding its own monitor, and the plugin's
 * notification publisher reads the service. Neither side may wait for the other's monitor while
 * holding its own, or a BLE failure racing a lifecycle callback freezes both threads.
 */
class YpsoAvailabilityLockOrderTest {

    private class GatedStore : PumpSession.Store {
        @Volatile var saved = PumpSession.State()
        @Volatile var armed = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun load() = saved
        override fun commit(state: PumpSession.State) {
            saved = state
            if (!armed) return
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `failure publication and notification publication cannot deadlock`() {
        val rh: ResourceHelper = mock {
            on { gs(any()) } doReturn "localized message"
            on { gs(any(), anyVararg()) } doReturn "localized message"
        }
        val store = GatedStore()
        val state = YpsoPumpState()
        val provisioning = YpsoProvisioningService(PumpSession(store), state)
        val plugin = YpsoPumpPlugin(
            AAPSLoggerTest(), rh, mock<Preferences>(), mock<CommandQueue>(), state, mock<YpsoBleManager>(), mock<PumpSync>(), mock<RxBus>(),
            mock<UiInteraction>(), Provider { PumpEnactResultObject(rh).success(true).enacted(true) }, provisioning, mock<ProfileFunction>(),
            mock<ConstraintsChecker>(), mock<AppLifecycle>(),
        )
        store.armed = true

        // A BLE failure: records availability under the service monitor, then notifies the plugin.
        val failure = thread(isDaemon = true) {
            provisioning.recordUnavailable(setOf(PumpSession.AvailabilityCause.TRANSPORT), operation = "status")
        }
        assertTrue(store.entered.await(5, TimeUnit.SECONDS), "failure never reached the commit")
        // A lifecycle callback: publishes the notification from the plugin.
        val lifecycle = thread(isDaemon = true) { plugin.publishAvailabilityNotification() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (lifecycle.isAlive && lifecycle.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(10)
        store.release.countDown()

        failure.join(3_000)
        lifecycle.join(3_000)
        assertFalse(
            failure.isAlive || lifecycle.isAlive,
            "monitor cycle between service and plugin: failure=${failure.state}, lifecycle=${lifecycle.state}",
        )
    }
}
