package app.aaps.pump.ypsopump.data

/** Pump software version, separate from the GATT service's protocol version. */
data class YpsoFirmwareVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<YpsoFirmwareVersion> {

    override fun compareTo(other: YpsoFirmwareVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    val meetsMinimum: Boolean get() = this >= MINIMUM

    override fun toString(): String = "V%02d.%02d.%02d".format(java.util.Locale.ROOT, major, minor, patch)

    companion object {
        val MINIMUM = YpsoFirmwareVersion(5, 0, 52)

        fun parse(value: String): YpsoFirmwareVersion? {
            val match = Regex("V([0-9]{2})\\.([0-9]{2})\\.([0-9]{2})").matchEntire(value) ?: return null
            return YpsoFirmwareVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
        }

        /** Target strings are ASCII with exactly one terminating NUL. */
        fun fromWire(bytes: ByteArray): YpsoFirmwareVersion? {
            if (bytes.size != 10 || bytes.last() != 0.toByte()) return null
            if (bytes.dropLast(1).any { it.toInt() !in 32..126 }) return null
            return parse(bytes.dropLast(1).toByteArray().toString(Charsets.US_ASCII))
        }
    }
}
