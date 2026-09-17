package app.aaps.pump.ypsopump

import android.content.SharedPreferences
import app.aaps.pump.ypsopump.data.YpsoProfileConfigurationStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class YpsoProfileConfigurationStoreTest {
    @Test
    fun `complete configuration survives process recreation and rejects another pump generation`() {
        val prefs: SharedPreferences = mock()
        val editor: SharedPreferences.Editor = mock()
        var saved: String? = null
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putString(eq("configuration"), any())).thenAnswer {
            saved = it.getArgument(1)
            editor
        }
        whenever(editor.commit()).thenReturn(true)
        whenever(prefs.getString(eq("configuration"), isNull())).thenAnswer { saved }
        val original = YpsoProfileReadbackTest.verified()
        YpsoProfileConfigurationStore(prefs).save(original)
        val restored = YpsoProfileConfigurationStore(prefs).load(original.generation)!!
        assertEquals(original.observedAt, restored.observedAt)
        assertEquals(original.activeProgram, restored.activeProgram)
        for (hour in 0..23) {
            assertEquals(original.profileA.rateAt(hour * 3600), restored.profileA.rateAt(hour * 3600))
            assertEquals(original.profileB.rateAt(hour * 3600), restored.profileB.rateAt(hour * 3600))
        }
        assertNull(YpsoProfileConfigurationStore(prefs).load("replacement-pump"))
        saved = "{broken"
        assertNull(YpsoProfileConfigurationStore(prefs).load(original.generation))
    }
}
