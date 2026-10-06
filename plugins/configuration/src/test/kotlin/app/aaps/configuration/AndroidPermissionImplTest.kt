package app.aaps.configuration

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.plugins.configuration.AndroidPermissionImpl
import app.aaps.plugins.configuration.activities.DaggerAppCompatActivityWithResult
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AndroidPermissionImplTest {

    private val uiInteraction: UiInteraction = mock()
    private val permissionRequest: ActivityResultLauncher<Array<String>> = mock()
    private val activity: DaggerAppCompatActivityWithResult = mock {
        on { requestMultiplePermissions } doReturn permissionRequest
    }
    private val rh: ResourceHelper = mock {
        on { gs(any()) } doReturn "permission needed"
    }
    private val sut = AndroidPermissionImpl(rh, mock(), mock(), uiInteraction)

    private fun notificationPermission(granted: Boolean) {
        whenever(activity.checkPermission(eq(Manifest.permission.POST_NOTIFICATIONS), any(), any()))
            .thenReturn(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED)
    }

    @Test
    fun `missing notification permission raises an urgent request until it is granted`() {
        notificationPermission(granted = false)

        sut.notifyForNotificationPermission(activity)

        val action = argumentCaptor<Runnable>()
        val stillMissing = argumentCaptor<() -> Boolean>()
        verify(uiInteraction).addNotification(
            eq(Notification.PERMISSION_NOTIFICATIONS), any(), eq(Notification.URGENT), any(), action.capture(), stillMissing.capture()
        )
        action.firstValue.run()
        verify(permissionRequest).launch(argThat { contentEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) })
        assertThat(stillMissing.firstValue()).isTrue()

        notificationPermission(granted = true)
        assertThat(stillMissing.firstValue()).isFalse()
    }

    @Test
    fun `granted notification permission clears the request`() {
        notificationPermission(granted = true)

        sut.notifyForNotificationPermission(activity)

        verify(uiInteraction).dismissNotification(Notification.PERMISSION_NOTIFICATIONS)
        verify(uiInteraction, never()).addNotification(any(), any(), any(), any(), any(), any())
    }
}
