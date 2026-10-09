package app.aaps.core.objects.utils

import app.aaps.core.objects.crypto.CryptoUtil
import app.aaps.core.utils.toHex
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import java.security.SecureRandom
import java.util.Base64

class CryptoCodecCompatibilityTest : TestBase() {

    private val crypto = CryptoUtil(aapsLogger)
    private val salt = ByteArray(32) { it.toByte() }
    private val password = "synthetic-password"

    // Captured with Spongy Castle 1.58.0.0 before replacement. Synthetic inputs only:
    // PBKDF2-HMAC-SHA1 (50,000 iterations, 256 bits), salt 00..1f, IV a0..ab,
    // AES-GCM (128-bit tag), envelope = IV length byte + IV + ciphertext/tag.
    private val fixtures = listOf(
        "" to "DKChoqOkpaanqKmqq5ssCKMXh7tyuWYhS8Z9UbU=",
        "A" to "DKChoqOkpaanqKmqq26RsMIIAyoCBGuN8BtWhzEo",
        "AB" to "DKChoqOkpaanqKmqq26bEwnhdu/0Bjz5ONCIqAYFvA==",
        "Glucose: 5.5 mmol/L — 糖尿病 💉\n{\"synthetic\":true}" to
            "DKChoqOkpaanqKmqq2i1fq+dLne8Go4mXnOpNR0Iue0wL3OYQogLi9wWat1Q4erdpi8t4KQ3wDlYvBNoPAcLtSkaR9Y/U0DIYxYtpgSlrIoj4C347mY="
    )

    @Test
    fun frozenLegacyEnvelopesDecryptIncludingUnicodeAndEveryPaddingLength() {
        for ((plaintext, envelope) in fixtures) {
            assertThat(crypto.decrypt(password, salt, envelope)).isEqualTo(plaintext)
            assertThat(crypto.lastException).isNull()
        }
    }

    @Test
    fun controlledIvEncryptionPreservesExactEnvelopeAndBase64Bytes() {
        val random = mock<SecureRandom>()
        doAnswer { invocation ->
            val bytes = invocation.arguments[0] as ByteArray
            assertThat(bytes.size).isEqualTo(12)
            ByteArray(12) { (0xa0 + it).toByte() }.copyInto(bytes)
            null
        }.`when`(random).nextBytes(any())
        // Keep deterministic randomness confined to tests; production still owns SecureRandom.
        CryptoUtil::class.java.getDeclaredField("secureRandom").apply { isAccessible = true }.set(crypto, random)

        for ((plaintext, envelope) in fixtures) {
            assertThat(crypto.encrypt(password, salt, plaintext)).isEqualTo(envelope)
            assertThat(crypto.lastException).isNull()
        }
        assertThat(Base64.getDecoder().decode(fixtures.last().second).toHex()).isEqualTo(
            "0ca0a1a2a3a4a5a6a7a8a9aaab68b57eaf9d2e77bc1a8e265e73a9351d08b9ed302f739842880b8bdc166add50e1eadda62f2de0a437c03958bc13683c070bb5291a47d63f5340c863162da604a5ac8a23e02df8ee66"
        )
    }

    @Test
    fun independentEncryptionsUseDifferentRandomIvsAndBothDecrypt() {
        val first = crypto.encrypt(password, salt, "synthetic")!!
        val second = crypto.encrypt(password, salt, "synthetic")!!
        assertThat(first).isNotEqualTo(second)
        assertThat(Base64.getDecoder().decode(first).sliceArray(1..12).contentEquals(Base64.getDecoder().decode(second).sliceArray(1..12))).isFalse()
        assertThat(crypto.decrypt(password, salt, first)).isEqualTo("synthetic")
        assertThat(crypto.decrypt(password, salt, second)).isEqualTo("synthetic")
    }

    @Test
    fun legacyWhitespaceIsAcceptedBeforeFinalQuartetAndAfterIt() {
        val envelope = fixtures.last().second
        val wrapped = " \t\r\n" + envelope.dropLast(4).chunked(5).joinToString(" \t\r\n") + " \t" + envelope.takeLast(4) + " \r\n\t"
        assertThat(crypto.decrypt(password, salt, wrapped)).isEqualTo(fixtures.last().first)
        assertThat(crypto.lastException).isNull()
    }

    @Test
    fun invalidBase64AndEnvelopesReportFailureWithoutPlaintext() {
        val envelope = fixtures.last().second
        val bytes = Base64.getDecoder().decode(envelope)
        val badTag = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val failures = listOf(
            "", " \t\r\n", "!", envelope.dropLast(1), fixtures[2].second.dropLast(2),
            envelope + "=", envelope + "AAAA", envelope + "!", envelope + "\u000c", envelope + "\u00a0",
            envelope.dropLast(3) + " " + envelope.takeLast(3),
            envelope.dropLast(2) + "\t" + envelope.takeLast(2),
            "DA==", // Only an IV-length byte, no IV or tag.
            "AA==", // Zero-length IV.
            "/w==", // Negative signed IV length.
            Base64.getEncoder().encodeToString(bytes.copyOf(20)),
            Base64.getEncoder().encodeToString(badTag)
        )
        for (input in failures) {
            assertThat(crypto.decrypt(password, salt, input)).isNull()
            assertThat(crypto.lastException).isNotNull()
        }
    }

    @Test
    fun wrongPasswordAndSaltFailAndLaterSuccessClearsFailureState() {
        val envelope = fixtures.last().second
        assertThat(crypto.decrypt("wrong-password", salt, envelope)).isNull()
        assertThat(crypto.lastException).isNotNull()
        assertThat(crypto.decrypt(password, ByteArray(32), envelope)).isNull()
        assertThat(crypto.lastException).isNotNull()
        assertThat(crypto.decrypt(password, salt, envelope)).isEqualTo(fixtures.last().first)
        assertThat(crypto.lastException).isNull()
        assertThat(crypto.encrypt(password, byteArrayOf(), "synthetic")).isNull()
        assertThat(crypto.lastException).isNotNull()
        assertThat(crypto.encrypt(password, salt, "synthetic")).isNotNull()
        assertThat(crypto.lastException).isNull()
    }
}
