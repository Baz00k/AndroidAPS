package app.aaps.plugins.main.general.overview.notifications

import android.app.NotificationManager
import android.content.Context
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.IconsProvider
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NotificationStoreTest : TestBase() {

    @Mock lateinit var preferences: Preferences
    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var context: Context
    @Mock lateinit var iconsProvider: IconsProvider
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var notificationHolder: NotificationHolder
    @Mock lateinit var activePlugin: ActivePlugin
    @Mock lateinit var notificationManager: NotificationManager

    private lateinit var store: NotificationStore

    @BeforeEach
    fun prepare() {
        whenever(context.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(notificationManager)
        store = NotificationStore(
            aapsLogger,
            preferences,
            rh,
            context,
            iconsProvider,
            uiInteraction,
            dateUtil,
            notificationHolder,
            activePlugin,
        )
    }

    @Test
    fun `dismissal cancels Android notification after process-local store is lost`() {
        store.remove(Notification.YPSOPUMP_UNAVAILABLE)

        verify(notificationManager).cancel(Notification.YPSOPUMP_UNAVAILABLE)
    }

    @Test
    fun `dismissal cancels Android notification and removes local alert`() {
        store.add(Notification(Notification.YPSOPUMP_UNAVAILABLE, "Unavailable", Notification.URGENT))

        store.remove(Notification.YPSOPUMP_UNAVAILABLE)

        verify(notificationManager).cancel(Notification.YPSOPUMP_UNAVAILABLE)
    }
}
