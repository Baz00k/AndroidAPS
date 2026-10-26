package app.aaps.plugins.main.general.persistentNotification

import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** A foreground start that Android refuses must stay inside the helper instead of ending the process. */
class DummyServiceHelperTest : TestBase() {

    @Mock lateinit var context: Context

    private lateinit var sut: DummyServiceHelper

    @BeforeEach
    fun setUp() {
        sut = DummyServiceHelper(aapsLogger)
    }

    @Test
    fun `refused background start does not end the process`() {
        // The caller cannot bind, so the helper falls back to a foreground start, which is refused.
        whenever(context.bindService(any<Intent>(), any<ServiceConnection>(), anyInt())).thenThrow(IllegalStateException("cannot bind from this caller"))
        whenever(context.startForegroundService(any<Intent>())).thenThrow(ForegroundServiceStartNotAllowedException("startForegroundService() not allowed"))

        assertDoesNotThrow { sut.startService(context) }
        verify(context).startForegroundService(any<Intent>())
    }

    @Test
    fun `refused start from the bound callback unbinds without throwing`() {
        val connection = ArgumentCaptor.forClass(ServiceConnection::class.java)
        whenever(context.bindService(any<Intent>(), connection.capture(), anyInt())).thenReturn(true)
        whenever(context.startForegroundService(any<Intent>())).thenThrow(ForegroundServiceStartNotAllowedException("startForegroundService() not allowed"))

        sut.startService(context)
        val bound = connection.value
        val binder = DummyService().LocalBinder()
        assertDoesNotThrow { bound.onServiceConnected(ComponentName("app.aaps", "DummyService"), binder) }
        verify(context).unbindService(bound)
    }
}
