package app.aaps.implementations

import android.app.NotificationManager
import android.content.Context
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.plugins.main.general.overview.notifications.NotificationWithAction
import app.aaps.shared.tests.TestBase
import app.aaps.ui.services.AlarmSoundServiceHelper
import io.reactivex.rxjava3.disposables.Disposable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import javax.inject.Provider

class UiInteractionImplTest : TestBase() {

    @Mock lateinit var context: Context
    @Mock lateinit var alarmSoundServiceHelper: AlarmSoundServiceHelper
    @Mock lateinit var notificationManager: NotificationManager

    private lateinit var uiInteraction: UiInteractionImpl

    @BeforeEach
    fun prepare() {
        whenever(context.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(notificationManager)
        uiInteraction = UiInteractionImpl(
            context,
            rxBus,
            alarmSoundServiceHelper,
            Provider<NotificationWithAction> { mock() },
        )
    }

    @Test
    fun `dismissal synchronously cancels Android notification and publishes in-app removal`() {
        var dismissed: EventDismissNotification? = null
        val subscription: Disposable = rxBus.toObservable(EventDismissNotification::class.java).subscribe { dismissed = it }

        uiInteraction.dismissNotification(97)

        verify(notificationManager).cancel(97)
        assertEquals(97, dismissed?.id)
        subscription.dispose()
    }
}
