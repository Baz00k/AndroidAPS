package app.aaps.implementation.lifecycle

import androidx.lifecycle.LifecycleOwner
import app.aaps.core.interfaces.protection.ProtectionCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class ProcessLifecycleListenerTest {

    @Test
    fun `process lifecycle publishes foreground transitions to registered listeners`() {
        val listener = ProcessLifecycleListener(mock<ProtectionCheck>())
        val owner: LifecycleOwner = mock()
        val transitions = mutableListOf<Boolean>()
        val observer: (Boolean) -> Unit = transitions::add
        listener.addVisibilityListener(observer)

        listener.onStart(owner)
        listener.onStop(owner)
        listener.removeVisibilityListener(observer)
        listener.onStart(owner)

        assertEquals(listOf(true, false), transitions)
        assertTrue(listener.uiVisible)
    }

    @Test
    fun `visibility state remains queryable before listeners register`() {
        val listener = ProcessLifecycleListener(mock<ProtectionCheck>())
        val owner: LifecycleOwner = mock()
        assertFalse(listener.uiVisible)

        listener.onStart(owner)

        assertTrue(listener.uiVisible)
    }
}
