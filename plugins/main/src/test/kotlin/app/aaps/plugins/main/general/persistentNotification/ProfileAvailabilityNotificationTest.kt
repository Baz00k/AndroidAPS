package app.aaps.plugins.main.general.persistentNotification

import app.aaps.core.data.iob.CobInfo
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.rx.events.EventProfileAvailabilityChanged
import app.aaps.core.objects.ui.BasalDisplay
import app.aaps.core.objects.ui.TherapyDisplay
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever

class ProfileAvailabilityNotificationTest : TestBaseWithProfile() {
    @Test fun profileAvailabilityRefreshReplacesNumericReadingsWithoutAutosensCompletion() {
        whenever(rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)).thenReturn("--")
        whenever(rh.gs(app.aaps.core.ui.R.string.iob_no_active_profile)).thenReturn("No active profile")
        whenever(rh.gs(app.aaps.core.ui.R.string.format_carbs)).thenReturn("%d g")
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(iobCobCalculator.calculateIobFromBolus()).thenReturn(IobTotal(1).also { it.iob = 0.3 })
        whenever(iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(1))
        whenever(iobCobCalculator.getCobInfo("notification test")).thenReturn(CobInfo(1, 12.0, 0.0))
        fun readings() = TherapyDisplay.load(profileFunction, iobCobCalculator, rh, decimalFormatter,
            "notification test", iobFormat = { "$it" }) { BasalDisplay("0.80 U/h") }
        var published = readings()
        assertEquals("0.3", published.iob.total)
        val plugin = spy(PersistentNotificationPlugin(aapsLogger, rh, aapsSchedulers, profileFunction, profileUtil,
            fabricPrivacy, activePlugin, iobCobCalculator, processedTbrEbData, rxBus, context, mock(), mock(),
            mock(), mock(), config, decimalFormatter, preferences, mock(), mock(), mock(), dateUtil))
        // Isolate Android's NotificationManager; exercise the real plugin's subscription and presenter.
        doAnswer { published = readings(); null }.whenever(plugin).triggerNotificationUpdate()
        plugin.onStart()
        try {
            whenever(profileFunction.getProfile()).thenReturn(null)
            rxBus.send(EventProfileAvailabilityChanged())
            assertEquals("--", published.iob.total)
            assertEquals("--", published.cob.value)
            assertEquals("--", published.basal.rate)
        } finally {
            plugin.onStop()
        }
    }
}
