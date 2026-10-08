package app.aaps.pump.ypsopump

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import com.sun.jna.NativeLibrary
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated native integration fixtures; no pump, BLE connection or therapy records. */
@RunWith(AndroidJUnit4::class)
class SessionCryptoNativeTest {

    @Test
    fun nativeDispatcherUsesSystemPageSize() {
        // Exercise JNA loading and an actual libffi call, independently of bundled libsodium.
        val pageSize = NativeLibrary.getInstance("c").getFunction("getpagesize").invokeInt(emptyArray())
        assertEquals(Os.sysconf(OsConstants._SC_PAGESIZE), pageSize.toLong())
    }

    @Test
    fun sessionCipherPreservesIndependentVectorAndRejectsUnauthenticatedData() {
        val crypto = SessionCrypto()
        val key = ByteArray(SessionCrypto.KEY_SIZE) { it.toByte() }
        // Same independently generated XChaCha20-Poly1305 fixture as the host codec test.
        val vector = hex("873858d502cde7d78f6906561187572ee28bc6380dd1d0e092b58d09876aea28292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
        val decoded = crypto.decrypt(vector, key)
        assertArrayEquals(hex("aabbcc"), decoded.body)
        assertEquals(8, decoded.reboot)
        assertEquals(2743L, decoded.counter)

        val wrongKey = key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(SessionCrypto.AuthenticationFailedException::class.java) { crypto.decrypt(vector, wrongKey) }
        for (index in listOf(0, vector.size - SessionCrypto.NONCE_SIZE - 1, vector.lastIndex)) {
            val tampered = vector.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertThrows(SessionCrypto.AuthenticationFailedException::class.java) { crypto.decrypt(tampered, key) }
        }

        val first = crypto.encrypt(decoded.body, key, decoded.reboot, decoded.counter)
        val second = crypto.encrypt(decoded.body, key, decoded.reboot, decoded.counter)
        assertEquals(vector.size, first.size)
        assertFalse(first.takeLast(SessionCrypto.NONCE_SIZE) == second.takeLast(SessionCrypto.NONCE_SIZE))
        // A fresh codec instance can decrypt the existing wire format; no JNA-specific state is saved.
        val restored = SessionCrypto().decrypt(first, key)
        assertArrayEquals(decoded.body, restored.body)
        assertEquals(decoded.reboot, restored.reboot)
        assertEquals(decoded.counter, restored.counter)
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
