package app.aaps.libre3

/**
 * Retained 5-minute history — the basis for gap BACKFILL.
 *
 * This is the capability the Juggluco→xDrip path does not have: when the phone is out of range,
 * those readings are simply lost. The sensor keeps them, and asking for them on reconnect fills
 * the hole in both the live loop and the retrospective record.
 *
 * Formats transcribed from Juggluco (`cpp/libre3/bluetooth.cpp`, GPL-3.0):
 * `struct RequestData` / `saveLibre3History` / `struct Patchstatus`.
 */
object Libre3History {

    /** Sensor history is on a 5-minute grid; consecutive values are this far apart. */
    const val INTERVAL_MINUTES = 5

    /** Abbott's validity window, same as for real-time readings. */
    val VALID_RANGE = 39..501

    /**
     * Control command written to PATCH_CONTROL to request retained history.
     *
     * `RequestData { int8 kind[2]; int8 arg; int32 from; }` packed = 7 bytes, little-endian.
     * kind {1,0} selects history; {1,1} would select the clinical stream.
     *
     * @param from lifeCount to start from — pass the last record already stored so the sensor
     *   only resends what is actually missing.
     */
    /** Juggluco never asks below this: `takelast = max(lastHistoricLifeCountReceived, 5)`. */
    const val MIN_FROM_LIFECOUNT = 5

    fun controlHistoryCommand(arg: Int = 1, from: Int): ByteArray = controlHistoryCommandRaw(arg, maxOf(from, MIN_FROM_LIFECOUNT))

    private fun controlHistoryCommandRaw(arg: Int, from: Int): ByteArray = byteArrayOf(
        1, 0,
        (arg and 0xFF).toByte(),
        (from and 0xFF).toByte(),
        ((from shr 8) and 0xFF).toByte(),
        ((from shr 16) and 0xFF).toByte(),
        ((from shr 24) and 0xFF).toByte()
    )

    /** One retained reading. [lifeCount] is minutes since sensor start — its identity. */
    data class Entry(val lifeCount: Int, val mgDl: Int)

    /**
     * Decrypted history payload: `uint16 startLifeCount` followed by uint16 glucose values,
     * each [INTERVAL_MINUTES] later than the previous.
     *
     * Invalid values are DROPPED, not clamped and not zero-filled: a gap the sensor could not
     * measure must stay a gap, or backfill would invent data the loop then trusts.
     */
    fun parseHistory(plain: ByteArray): List<Entry> {
        if (plain.size < 4) return emptyList()
        val count = (plain.size / 2) - 1
        val start = u16(plain, 0)
        val out = ArrayList<Entry>(count)
        for (i in 0 until count) {
            val value = u16(plain, 2 + i * 2)
            val lifeCount = start + i * INTERVAL_MINUTES
            if (value in VALID_RANGE) out.add(Entry(lifeCount, value))
        }
        return out
    }

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}

/**
 * Sensor lifecycle state, from the PATCH_STATUS characteristic (12 bytes packed).
 *
 * This is what lets AAPS show real sensor state — warming up, expiring, failed — instead of
 * inferring it from the absence of readings.
 */
data class Libre3PatchStatus(
    val lifeCount: Int,
    val errorData: Int,
    val eventData: Int,
    val index: Int,
    val patchState: Int,
    val currentLifeCount: Int,
    val stackDisconnectReason: Int,
    val appDisconnectReason: Int
) {
    val totalEvents: Int get() = index + 1

    /** Juggluco offsets the raw field by 4000. */
    val eventCode: Int get() = 4000 + eventData

    companion object {
        const val SIZE = 12

        fun parse(plain: ByteArray): Libre3PatchStatus? {
            if (plain.size != SIZE) return null
            fun s16(o: Int) = ((plain[o].toInt() and 0xFF) or ((plain[o + 1].toInt() and 0xFF) shl 8))
                .let { if (it > 0x7FFF) it - 0x10000 else it }
            return Libre3PatchStatus(
                lifeCount = s16(0),
                errorData = s16(2),
                eventData = s16(4),
                index = plain[6].toInt(),
                patchState = plain[7].toInt() and 0xFF,
                currentLifeCount = s16(8),
                stackDisconnectReason = plain[10].toInt() and 0xFF,
                appDisconnectReason = plain[11].toInt() and 0xFF
            )
        }
    }
}
