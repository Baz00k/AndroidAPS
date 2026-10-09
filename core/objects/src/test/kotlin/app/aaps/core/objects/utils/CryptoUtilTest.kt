package app.aaps.core.objects.utils

import app.aaps.core.objects.crypto.CryptoUtil
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.TruthJUnit.assume
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import java.security.SecureRandom
import java.util.Base64

// https://stackoverflow.com/questions/52344522/joseexception-couldnt-create-aes-gcm-nopadding-cipher-illegal-key-size
// https://stackoverflow.com/questions/47708951/can-aes-256-work-on-android-devices-with-api-level-26
// Java prior to Oracle Java 8u161 does not have policy for 256 bit AES - but Android support it
// when test is run in Vanilla JVM without policy - Invalid key size exception is thrown
private fun assumeAES256isSupported(cryptoUtil: CryptoUtil) {
    cryptoUtil.lastException?.message?.let { exceptionMessage ->
        assume().withMessage("Upgrade your testing environment Java (OpenJDK or Java 8u161) and JAVA_HOME - AES 256 is supported by Android so this exception should not happen!")
            .that(exceptionMessage).doesNotContain("key size")
    }
}

@Suppress("SpellCheckingInspection")
class CryptoUtilTest : TestBase() {

    private var cryptoUtil: CryptoUtil = CryptoUtil(aapsLogger)

    @Test
    fun testFixedSaltCrypto() {
        val salt = byteArrayOf(
            -33, -29, 16, -19, 99, -111, -3, 2, 116, 106, 47, 38, -54, 11, -77, 28,
            111, -15, -65, -110, 4, -32, -29, -70, -95, -88, -53, 19, 87, -103, 123, -15
        )

        val password = "thisIsFixedPassword"
        val payload = "FIXED-PAYLOAD"

        val encrypted = cryptoUtil.encrypt(password, salt, payload)
        assumeAES256isSupported(cryptoUtil)
        assertThat(encrypted).isNotNull()

        val second = cryptoUtil.encrypt(password, salt, payload)
        assertThat(second).isNotNull()
        assertThat(second).isNotEqualTo(encrypted)
        assertThat(cryptoUtil.decrypt(password, salt, second!!)).isEqualTo(payload)

        val decrypted = cryptoUtil.decrypt(password, salt, encrypted!!)
        assumeAES256isSupported(cryptoUtil)
        assertThat(decrypted).isEqualTo(payload)
    }

    @Test
    fun testStandardCrypto() {
        val salt = cryptoUtil.mineSalt()

        val password = "topSikret"
        val payload = "{what:payloadYouWantToProtect}"

        val encrypted = cryptoUtil.encrypt(password, salt, payload)
        assumeAES256isSupported(cryptoUtil)
        assertThat(encrypted).isNotNull()

        val decrypted = cryptoUtil.decrypt(password, salt, encrypted!!)
        assumeAES256isSupported(cryptoUtil)
        assertThat(decrypted).isEqualTo(payload)
    }

    private val codecSalt = ByteArray(32) { it.toByte() }
    private val codecPassword = "synthetic-password"
    // Frozen Spongy Castle 1.58.0.0 output: salt 00..1f, IV a0..ab; all padding lengths.
    private val codecFixtures = listOf(
        "" to "DKChoqOkpaanqKmqq5ssCKMXh7tyuWYhS8Z9UbU=",
        "A" to "DKChoqOkpaanqKmqq26RsMIIAyoCBGuN8BtWhzEo",
        "AB" to "DKChoqOkpaanqKmqq26bEwnhdu/0Bjz5ONCIqAYFvA==",
        "Glucose: 5.5 mmol/L — 糖尿病 💉\n{\"synthetic\":true}" to
            "DKChoqOkpaanqKmqq2i1fq+dLne8Go4mXnOpNR0Iue0wL3OYQogLi9wWat1Q4erdpi8t4KQ3wDlYvBNoPAcLtSkaR9Y/U0DIYxYtpgSlrIoj4C347mY="
    )

    @Test
    fun codecFixturesPreserveExactEncryptionAndDecryptWithWhitespace() {
        val random = mock<SecureRandom>()
        doAnswer {
            ByteArray(12) { (0xa0 + it).toByte() }.copyInto(it.getArgument(0))
            null
        }.`when`(random).nextBytes(any())
        CryptoUtil::class.java.getDeclaredField("secureRandom").apply { isAccessible = true }.set(cryptoUtil, random)
        for ((plaintext, envelope) in codecFixtures) {
            assertThat(cryptoUtil.encrypt(codecPassword, codecSalt, plaintext)).isEqualTo(envelope)
            assertThat(cryptoUtil.decrypt(codecPassword, codecSalt, envelope)).isEqualTo(plaintext)
            val wrapped = envelope.dropLast(4).chunked(5).joinToString(" \t\r\n") + " \t" + envelope.takeLast(4) + "\r\n"
            assertThat(cryptoUtil.decrypt(codecPassword, codecSalt, wrapped)).isEqualTo(plaintext)
            assertThat(cryptoUtil.lastException).isNull()
        }
    }

    @Test
    fun invalidEnvelopeOrPasswordReportsFailureAndSuccessClearsIt() {
        val (plaintext, envelope) = codecFixtures.last()
        val bytes = Base64.getDecoder().decode(envelope)
        val badTag = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val invalid = listOf(
            "", "!", envelope.dropLast(1), codecFixtures[2].second.dropLast(2),
            envelope + "=", envelope + "!", envelope + "\u000c", envelope + "\u00a0",
            envelope.dropLast(2) + "\t" + envelope.takeLast(2),
            "DA==", "AA==", "/w==", // Missing, zero-length and negative-length IV.
            Base64.getEncoder().encodeToString(bytes.copyOf(20)), Base64.getEncoder().encodeToString(badTag),
            // Maintainer-approved rejection of the legacy overlapping-quartet decoder defect.
            "DKChoqOkpaanqKmqq1T7eLWcKXrjTtJrSWn9JejEPPCFIJhl3CYe18wWKk="
        )
        for ((password, input) in invalid.map { codecPassword to it } + ("wrong-password" to envelope)) {
            assertThat(cryptoUtil.decrypt(password, codecSalt, input)).isNull()
            assertThat(cryptoUtil.lastException).isNotNull()
        }
        assertThat(cryptoUtil.decrypt(codecPassword, codecSalt, envelope)).isEqualTo(plaintext)
        assertThat(cryptoUtil.lastException).isNull()
    }

    @Test
    fun testHashVector() {
        val payload = "{what:payloadYouWantToProtect}"
        val hash = cryptoUtil.sha256(payload)
        assertThat(hash).isEqualTo("a1aafe3ed6cc127e6d102ddbc40a205147230e9cfd178daf108c83543bbdcd13")
    }

    @Test
    fun testHmac() {
        val payload = "{what:payloadYouWantToProtect}"
        val password = "topSikret"
        val expectedHmac = "ea2213953d0f2e55047cae2d23fb4f0de1b805d55e6271efa70d6b85fb692bea" // generated using other HMAC tool
        val hash = cryptoUtil.hmac256(payload, password)
        assertThat(hash).isEqualTo(expectedHmac)
    }

    @Test
    fun testPlainPasswordCheck() {
        assertThat(cryptoUtil.checkPassword("same", "same")).isTrue()
        assertThat(cryptoUtil.checkPassword("same", "other")).isFalse()
    }

    @Test
    fun testHashedPasswordCheck() {
        assertThat(cryptoUtil.checkPassword("givenSecret", cryptoUtil.hashPassword("givenSecret"))).isTrue()
        assertThat(cryptoUtil.checkPassword("givenSecret", cryptoUtil.hashPassword("otherSecret"))).isFalse()

        assertThat(
            cryptoUtil.checkPassword(
                "givenHashToCheck",
                "hmac:7fe5f9c7b4b97c5d32d5cfad9d07473543a9938dc07af48a46dbbb49f4f68c12:a0c7cee14312bbe31b51359a67f0d2dfdf46813f319180269796f1f617a64be1"
            )
        ).isTrue()
        assertThat(
            cryptoUtil.checkPassword(
                "givenMashToCheck",
                "hmac:7fe5f9c7b4b97c5d32d5cfad9d07473543a9938dc07af48a46dbbb49f4f68c12:a0c7cee14312bbe31b51359a67f0d2dfdf46813f319180269796f1f617a64be1"
            )
        ).isFalse()
        assertThat(
            cryptoUtil.checkPassword(
                "givenHashToCheck",
                "hmac:0fe5f9c7b4b97c5d32d5cfad9d07473543a9938dc07af48a46dbbb49f4f68c12:a0c7cee14312bbe31b51359a67f0d2dfdf46813f319180269796f1f617a64be1"
            )
        ).isFalse()
        assertThat(
            cryptoUtil.checkPassword(
                "givenHashToCheck",
                "hmac:7fe5f9c7b4b97c5d32d5cfad9d07473543a9938dc07af48a46dbbb49f4f68c12:b0c7cee14312bbe31b51359a67f0d2dfdf46813f319180269796f1f617a64be1"
            )
        ).isFalse()
    }

}
