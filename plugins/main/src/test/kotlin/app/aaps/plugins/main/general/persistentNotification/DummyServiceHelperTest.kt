package app.aaps.plugins.main.general.persistentNotification

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * Android refuses foreground service starts from the background, and the boot receiver calls this
 * helper. The refusal must stay inside the helper: it used to escape and end the process.
 */
class DummyServiceHelperTest : TestBase() {

    @Mock lateinit var context: Context

    private lateinit var sut: DummyServiceHelper

    @BeforeEach
    fun setUp() {
        sut = DummyServiceHelper(aapsLogger)
    }

    @Test
    fun `refused background start does not end the process`() {
        // A broadcast receiver context cannot bind, and Android refuses the fallback start.
        whenever(context.bindService(any<Intent>(), any<ServiceConnection>(), anyInt())).thenThrow(IllegalStateException("cannot bind from a receiver"))
        whenever(context.startForegroundService(any<Intent>())).thenThrow(IllegalStateException("startForegroundService() not allowed"))

        assertDoesNotThrow { sut.startService(context) }
    }
}
