package app.aaps.pump.ypsopump.ble

import java.security.MessageDigest

/** Protocol-defined authentication password derivation shared by production and bench artifacts. */
internal object YpsoAuthentication {
    private val salt =
        byteArrayOf(
            0x4F,
            0xC2.toByte(),
            0x45,
            0x4D,
            0x9B.toByte(),
            0x81.toByte(),
            0x59,
            0xA4.toByte(),
            0x93.toByte(),
            0xBB.toByte(),
        )

    fun password(mac: String): ByteArray {
        val bytes =
            mac
                .replace(":", "")
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        return MessageDigest.getInstance("MD5").digest(bytes + salt)
    }
}
