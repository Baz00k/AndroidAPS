package app.aaps.nativecompatibility

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable emulator only. Loads shipped JNI without BLE, credentials or pump commands. */
@RunWith(AndroidJUnit4::class)
class NativeCompatibilityTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val expectedPageSize = InstrumentationRegistry.getArguments().getString("expectedPageSize")!!.toLong()
    private val packageName = "info.nightscout.androidaps"

    @Test
    fun fullLoopStartsOn4kbEmulator() {
        assertEquals(expectedPageSize, Os.sysconf(OsConstants._SC_PAGESIZE))
        // Strict 16 KB startup fails in JNA during YpsoPump injection (#164/#165).
        // Do not accept a compat-mode launch as evidence of native compatibility.
        assumeTrue("16 KB startup remains unverified until #164/#165 are fixed", expectedPageSize == 4096L)
        device.executeShellCommand("am force-stop $packageName")
        device.executeShellCommand("am start -W -n $packageName/app.aaps.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.text("Welcome")), 10000))
        assertTrue(device.executeShellCommand("pidof $packageName").trim().isNotEmpty())
    }

    @Test
    fun shippedLibre3DecryptsAuthenticatedGlucose() {
        assertEquals(expectedPageSize, Os.sysconf(OsConstants._SC_PAGESIZE))
        if (expectedPageSize == 16384L) {
            assertEquals("false", device.executeShellCommand("getprop bionic.linker.16kb.app_compat.enabled").trim())
            assertTrue(device.executeShellCommand("dumpsys package app.aaps.nativecompatibility").contains("pageSizeCompat=0"))
        }
        // The test APK has no JNI. Load the installed FullLoop APK's classes and native directory
        // in this non-compat process, avoiding a second build or a copied test-native library.
        val target = instrumentation.context.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val native = target.classLoader.loadClass("app.aaps.libre3.Libre3Native")
        val instance = native.getField("INSTANCE").get(null)
        native.getMethod("ensureLoaded").invoke(instance)
        fun call(name: String, vararg args: Any?): Any? {
            val method = native.methods.single { it.name == name }
            return method.invoke(instance, *args)
        }
        val security = call("beginSecurityHandshake", 0L) as Long
        assertNotEquals(0L, security)
        assertEquals(1, call("selectAppKeyAndSavedAuthorization", security, 1, null))
        val handle = call("initSessionCipher", 0L, ByteArray(16) { it.toByte() }, ByteArray(8) { (it + 16).toByte() }) as Long
        assertNotEquals(0L, handle)
        try {
            // Independently generated with Python cryptography AESCCM: key=00..0f, iv=10..17,
            // nonce=0100000f00||iv, tag_length=4, no AAD. No real session keys.
            val encrypted = hex("c9bdca5e63238bddc47f2f8961efb92ad5019c665eefa21e6626eb0b3a2b3882120100")
            val plain = call("sessionDecrypt", handle, 3, encrypted) as ByteArray
            assertArrayEquals(hex("77005c009eff0000141e640073000b5c0073007d0b460406444a0e0000"), plain)
            val recordClass = target.classLoader.loadClass("app.aaps.libre3.Libre3GlucoseRecord")
            val companion = recordClass.getField("Companion").get(null)
            val record = companion.javaClass.getMethod("parse", ByteArray::class.java).invoke(companion, plain)
            assertEquals(119, recordClass.getMethod("getLifeCount").invoke(record))
            assertEquals(92, recordClass.getMethod("getReadingMgDl").invoke(record))
            assertEquals(115, recordClass.getMethod("getHistoricalReading").invoke(record))
            assertEquals(true, recordClass.getMethod("isValid").invoke(record))
            val tampered = encrypted.copyOf().also { it[29] = (it[29].toInt() xor 1).toByte() }
            assertNull(call("sessionDecrypt", handle, 3, tampered))
            assertNull(call("sessionDecrypt", handle, 2, encrypted))
            assertNull(call("sessionDecrypt", handle, 3, encrypted.copyOf(6)))
        } finally {
            call("freeSessionCipher", handle)
            call("freeSecurityContext", security)
        }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
