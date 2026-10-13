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
import org.junit.jupiter.api.Assertions.*
import app.aaps.core.interfaces.rx.events.EventSetNotification
import io.reactivex.rxjava3.subjects.PublishSubject
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.mockito.Mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.times

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

    @Test fun `queued replacement cannot be followed by stale self dismissal and escalates text and severity`() {
        val scheduler = TestScheduler()
        val events = PublishSubject.create<EventSetNotification>()
        val subscription = events.observeOn(scheduler).subscribe { store.set(it.id, it.notification) }
        events.onNext(EventSetNotification(103, null))
        events.onNext(EventSetNotification(103, Notification(103, "Reminder", Notification.NORMAL)))
        scheduler.triggerActions()
        assertEquals("Reminder", store.snapshot().single().text)
        events.onNext(EventSetNotification(103, Notification(103, "Expired", Notification.URGENT)))
        scheduler.triggerActions()
        assertEquals("Expired", store.snapshot().single().text)
        assertEquals(Notification.URGENT, store.snapshot().single().level)
        events.onNext(EventSetNotification(103, null))
        events.onNext(EventSetNotification(103, Notification(103, "New key", Notification.NORMAL)))
        scheduler.triggerActions()
        assertEquals("New key", store.snapshot().single().text)
        subscription.dispose()
    }

    @Test fun `identical reconciliation does not cancel or re-alert already visible notification`() {
        store.set(103, Notification(103, "Expired", Notification.URGENT))
        store.set(103, Notification(103, "Expired", Notification.URGENT))
        verify(notificationManager, times(1)).cancel(103)
        assertEquals(1, store.snapshot().size)
    }
}
