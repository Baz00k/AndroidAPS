package app.aaps.plugins.main.general.persistentNotification

import app.aaps.core.interfaces.rx.events.EventProfileAvailabilityChanged
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProfileAvailabilityNotificationTest : TestBaseWithProfile() {
    @Test fun profileAvailabilityRefreshDoesNotNeedAutosensCompletion() {
        val plugin = spy(PersistentNotificationPlugin(aapsLogger, rh, aapsSchedulers, profileFunction, profileUtil,
            fabricPrivacy, activePlugin, iobCobCalculator, processedTbrEbData, rxBus, context, mock(), mock(),
            mock(), mock(), config, decimalFormatter, preferences, mock(), mock(), mock(), dateUtil))
        doNothing().whenever(plugin).triggerNotificationUpdate()
        plugin.onStart()
        try {
            rxBus.send(EventProfileAvailabilityChanged())
            verify(plugin).triggerNotificationUpdate()
        } finally {
            plugin.onStop()
        }
    }
}
