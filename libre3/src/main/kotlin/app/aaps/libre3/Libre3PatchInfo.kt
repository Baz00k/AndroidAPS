package app.aaps.libre3

/**
 * The first NFC exchange's response — the patch's identity and lifecycle parameters.
 *
 * Layout from Juggluco's `struct firstnfc` (`cpp/libre3/nfc.cpp`, GPL-3.0), 26 bytes packed:
 * ```
 *  0  uint16  secVersion
 *  2  uint16  localization
 *  4  uint16  puckGeneration      0 = Libre 3, 1 = Libre 3 Plus
 *  6  uint16  wearDuration        minutes
 *  8  uint32  firmwareVersion
 * 12  uint8   productType
 * 13  uint8   warmup              in units of FIVE MINUTES (Juggluco logs "warmup=%d*5")
 * 14  uint8   state
 * 15  char[9] serialNumber
 * 24  uint8[2] crc16
 * ```
 *
 * This is what decides ACTIVATE vs TAKEOVER, so parsing it wrong has a physical cost.
 */
data class Libre3PatchInfo(
    val secVersion: Int,
    val localization: Int,
    val puckGeneration: Int,
    val wearDurationMinutes: Int,
    val firmwareVersion: Long,
    val productType: Int,
    /** Raw field; see [warmupMinutes]. */
    val warmupUnits: Int,
    val state: Int,
    val serialNumber: String
) {

    /** Stored in units of five minutes — 12 means the familiar 60-minute warm-up. */
    val warmupMinutes: Int get() = warmupUnits * 5

    val wearDurationDays: Int get() = wearDurationMinutes / (24 * 60)

    /** Generation 1 is the 15-day Plus; 0 is the original 14-day Libre 3. */
    val isPlus: Boolean get() = puckGeneration == 1

    companion object {
        const val SIZE = 26

        /** Juggluco's `state` values, as used in `interpret3NFC2`'s error branch. */
        const val STATE_MANUFACTURING = 1
        const val STATE_READY_TO_ACTIVATE = 2
        const val STATE_EXPIRED = 6
        const val STATE_TERMINATED = 8

        /**
         * Extract the record from a raw NfcV response.
         *
         * The framing is odd and must be reproduced exactly: skip byte 0, then skip the run of
         * `0xA5` padding, **then skip one further byte** — Juggluco's `while(*iter++ == 0xa5);`
         * post-increments past the first non-padding byte as well. Reading it as "stop at the
         * first non-0xA5" shifts every field by one.
         */
        fun parse(response: ByteArray): Libre3PatchInfo? {
            if (response.size < SIZE + 3) return null
            var i = 1
            while (i < response.size && response[i] == 0xA5.toByte()) i++
            i++                                     // the extra post-increment
            if (i + SIZE > response.size) return null
            return parseRecord(response, i)
        }

        /** Parse the 26-byte record at [offset], padding already skipped. */
        fun parseRecord(b: ByteArray, offset: Int = 0): Libre3PatchInfo? {
            if (offset + SIZE > b.size) return null
            fun u8(o: Int) = b[offset + o].toInt() and 0xFF
            fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
            fun u32(o: Int) = (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)

            val serial = buildString {
                for (k in 15 until 24) {
                    val c = b[offset + k].toInt() and 0xFF
                    if (c == 0) break
                    append(c.toChar())
                }
            }
            return Libre3PatchInfo(
                secVersion = u16(0),
                localization = u16(2),
                puckGeneration = u16(4),
                wearDurationMinutes = u16(6),
                firmwareVersion = u32(8),
                productType = u8(12),
                warmupUnits = u8(13),
                state = u8(14),
                serialNumber = serial
            )
        }
    }
}
