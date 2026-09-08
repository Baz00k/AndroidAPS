package app.aaps.libre3

/**
 * NFC activation / takeover command assembly.
 *
 * Ported to Kotlin from Juggluco's `dp_activation.hpp` (GPL-3.0), which is self-contained — no
 * vendored C is needed for this part, so the one irreversible operation in the system is written
 * in a language where it can be unit tested against known vectors.
 *
 * Wire format of the NfcV command:
 * ```
 *   02  A0|A8  7A  ‖  activationData(10)
 *        ^^^^^ A0 = ACTIVATE (one shot, burns a fresh sensor)
 *              A8 = TAKEOVER (adopt a sensor already running under the same account)
 * ```
 * The selector comes from byte 17 of the patchInfo read in the first NFC exchange.
 */
object Libre3Nfc {

    private const val CMD_PREFIX: Byte = 0x02
    const val OP_ACTIVATE: Byte = 0xA0.toByte()
    const val OP_TAKEOVER: Byte = 0xA8.toByte()
    private const val CMD_SUFFIX: Byte = 0x7A

    const val ACTIVATION_DATA_LEN = 10

    /**
     * CRC-16: poly 0x1021, init 0xFFFF, **refin=true, refout=false**, xorout 0.
     *
     * That asymmetric refin/refout is why this is bit-by-bit rather than table-driven — a
     * byte-at-a-time table only folds correctly when refin == refout. For 8 bytes the cost is
     * irrelevant and the correctness is not.
     */
    fun crc16(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (bitrev8(data[i].toInt() and 0xFF) shl 8)
            crc = crc and 0xFFFF
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    private fun bitrev8(input: Int): Int {
        var b = input and 0xFF
        b = ((b and 0xF0) ushr 4) or ((b and 0x0F) shl 4)
        b = ((b and 0xCC) ushr 2) or ((b and 0x33) shl 2)
        b = ((b and 0xAA) ushr 1) or ((b and 0x55) shl 1)
        return b and 0xFF
    }

    /**
     * The 10-byte activation payload: `uint32LE(startTimeSeconds - 1) ‖ uint32LE(accountId) ‖
     * uint16LE(crc)`.
     *
     * Note the `- 1` on the time. It is not an off-by-one to be tidied away — Juggluco's
     * `DPGetActivationCommandData` does exactly this and the sensor validates the result.
     */
    fun activationData(startTimeSeconds: Long, accountId: Long): ByteArray {
        val out = ByteArray(ACTIVATION_DATA_LEN)
        putLe32(out, 0, (startTimeSeconds - 1L))
        putLe32(out, 4, accountId)
        val crc = crc16(out, 8)
        out[8] = (crc and 0xFF).toByte()
        out[9] = ((crc ushr 8) and 0xFF).toByte()
        return out
    }

    private fun putLe32(dst: ByteArray, offset: Int, value: Long) {
        val v = value.toInt()           // deliberate 32-bit truncation, as the C does
        dst[offset] = (v and 0xFF).toByte()
        dst[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        dst[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        dst[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /**
     * Full 13-byte NfcV command.
     *
     * @param operation [OP_ACTIVATE] or [OP_TAKEOVER] — NOT interchangeable. Activate on a sensor
     *   that is already running destroys it.
     */
    fun command(operation: Byte, startTimeSeconds: Long, accountId: Long): ByteArray {
        require(operation == OP_ACTIVATE || operation == OP_TAKEOVER) {
            "operation must be ACTIVATE (0xA0) or TAKEOVER (0xA8)"
        }
        val data = activationData(startTimeSeconds, accountId)
        return byteArrayOf(CMD_PREFIX, operation, CMD_SUFFIX) + data
    }

    /**
     * Which operation a patch is asking for, from the first NFC read.
     * Juggluco: `nfc1[17] == 1 ? 0xA0 : 0xA8`.
     */
    fun operationFor(patchInfo: ByteArray): Byte? {
        if (patchInfo.size <= 17) return null
        return if (patchInfo[17].toInt() == 1) OP_ACTIVATE else OP_TAKEOVER
    }
}
