package app.aaps.pump.ypsopump.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** Stateless AEAD codec. Only [PumpSession] may accept counters or reserve writes. */
@Singleton
class SessionCrypto @Inject constructor() {

    private val lazySodium = LazySodiumAndroid(SodiumAndroid())
    data class Message(val body: ByteArray, val reboot: Int, val counter: Long)

    /** The key did not authenticate the pump's ciphertext; the key is wrong or no longer valid. */
    class AuthenticationFailedException : SecurityException("Invalid key or tampered data")

    fun encrypt(commandData: ByteArray, key: ByteArray, reboot: Int, counter: Long): ByteArray {
        require(key.size == KEY_SIZE && reboot >= 0 && counter >= 0)
        val plaintext = commandData + ByteBuffer.allocate(COUNTER_DATA_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(reboot).putLong(counter).array()
        val nonce = ByteArray(NONCE_SIZE)
        lazySodium.sodium.randombytes_buf(nonce, NONCE_SIZE)
        val ciphertext = ByteArray(plaintext.size + TAG_SIZE)
        check(lazySodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            ciphertext, longArrayOf(0), plaintext, plaintext.size.toLong(), null, 0, null, nonce, key
        )) { "Encryption failed" }
        return ciphertext + nonce
    }

    fun decrypt(blePayload: ByteArray, key: ByteArray): Message {
        require(key.size == KEY_SIZE)
        require(blePayload.size >= NONCE_SIZE + TAG_SIZE + COUNTER_DATA_SIZE) { "Missing mandatory counter tail" }
        val nonce = blePayload.copyOfRange(blePayload.size - NONCE_SIZE, blePayload.size)
        val ciphertext = blePayload.copyOfRange(0, blePayload.size - NONCE_SIZE)
        val plaintext = ByteArray(ciphertext.size - TAG_SIZE)
        val length = longArrayOf(0)
        if (!lazySodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
                plaintext, length, null, ciphertext, ciphertext.size.toLong(), null, 0, nonce, key
            )) throw AuthenticationFailedException()
        check(length[0] == plaintext.size.toLong())
        val counters = ByteBuffer.wrap(plaintext, plaintext.size - COUNTER_DATA_SIZE, COUNTER_DATA_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        return Message(plaintext.copyOfRange(0, plaintext.size - COUNTER_DATA_SIZE), counters.int, counters.long)
    }

    companion object {
        const val KEY_SIZE = 32
        const val NONCE_SIZE = 24
        const val TAG_SIZE = 16
        const val COUNTER_DATA_SIZE = 12
    }
}
