package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.SessionCrypto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionCryptoTest {
    private val crypto = SessionCrypto()
    private val key = ByteArray(32) { it.toByte() }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `independent little endian vector parses immutable metadata`() {
        val message = crypto.decrypt(hex("873858d502cde7d78f6906561187572ee28bc6380dd1d0e092b58d09876aea28292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"), key)
        assertArrayEquals(hex("aabbcc"), message.body)
        assertEquals(8, message.reboot)
        assertEquals(2743L, message.counter)
    }

    @Test
    fun `authenticated incomplete tails reject`() {
        listOf(
            "2d351a65cd1abe591f22dc1269c82d90000102030405060708090a0b0c0d0e0f1011121314151617",
            "9e4f6209a02022f5a5e517bcb5daf711a1000102030405060708090a0b0c0d0e0f1011121314151617",
            "9ec30d7c94d78ba93b4d2c49882442b8275339baa43d9e8874d247000102030405060708090a0b0c0d0e0f1011121314151617"
        ).forEach { assertThrows(IllegalArgumentException::class.java) { crypto.decrypt(hex(it), key) } }
    }

    @Test
    fun `explicit counter roundtrip nonce randomness and tamper rejection`() {
        val first = crypto.encrypt(hex("010203"), key, 8, 42)
        val second = crypto.encrypt(hex("010203"), key, 8, 42)
        assertEquals(55, first.size)
        assertFalse(first.takeLast(24) == second.takeLast(24))
        val message = crypto.decrypt(first, key)
        assertEquals(42L, message.counter)
        assertArrayEquals(hex("010203"), message.body)
        first[0] = (first[0].toInt() xor 1).toByte()
        assertThrows(SecurityException::class.java) { crypto.decrypt(first, key) }
    }
}
