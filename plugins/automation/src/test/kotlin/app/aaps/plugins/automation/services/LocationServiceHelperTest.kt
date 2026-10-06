package app.aaps.plugins.automation.services

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LocationServiceHelperTest {

    private val context: Context = mock()
    private val sut = LocationServiceHelper(mock())

    private fun backgroundLocation(granted: Boolean) {
        whenever(context.checkPermission(eq(Manifest.permission.ACCESS_BACKGROUND_LOCATION), any(), any()))
            .thenReturn(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED)
    }

    @Test
    fun `location service is not started without background location`() {
        backgroundLocation(granted = false)

        sut.startService(context)

        verify(context, never()).bindService(any<Intent>(), any<ServiceConnection>(), any<Int>())
        verify(context, never()).startForegroundService(any())
    }

    @Test
    fun `location service is started with background location`() {
        backgroundLocation(granted = true)

        sut.startService(context)

        verify(context).bindService(any<Intent>(), any<ServiceConnection>(), eq(Context.BIND_AUTO_CREATE))
    }
}
