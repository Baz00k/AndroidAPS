package app.aaps.libre3

/**
 * The 29-byte plaintext of a 1-minute glucose notification.
 *
 * Layout transcribed from Juggluco's `struct oneminute` (`cpp/libre3/bluetooth.cpp`, GPL-3.0) —
 * authoritative, not inferred from samples. All fields little-endian, packed, no alignment.
 *
 * ```
 *  0  uint16  lifeCount              minutes since sensor start
 *  2  uint16  readingMgDl            REAL-TIME glucose
 *  4  int16   rateOfChange
 *  6  uint16  esaDuration
 *  8  uint16  projectedGlucose
 * 10  uint16  historicalLifeCount
 * 12  uint16  historicalReading      the 5-minute retained value — LAGGED
 * 14  uint8   trend:3 | rest:5
 * 15  uint16  uncappedCurrentMgDl
 * 17  uint16  uncappedHistoricMgDl
 * 19  uint16  temperature
 * 21  uint8[8] fastdata
 * ```
 */
data class Libre3GlucoseRecord(
    /** Minutes since sensor activation. Doubles as the record's identity for backfill. */
    val lifeCount: Int,
    /** Real-time glucose, mg/dL. This is the value the loop should run on. */
    val readingMgDl: Int,
    val rateOfChange: Int,
    val esaDuration: Int,
    val projectedGlucose: Int,
    val historicalLifeCount: Int,
    /** The retained 5-minute value. LAGS [readingMgDl] — see [historicLagsRealtime]. */
    val historicalReading: Int,
    val trend: Int,
    val uncappedCurrentMgDl: Int,
    val uncappedHistoricMgDl: Int,
    val temperature: Int
) {

    /** Abbott's own validity window; outside it the reading must not be used. */
    val isValid: Boolean get() = readingMgDl in VALID_RANGE

    /**
     * Trend as mg/dL/min, or null when the sensor reports no trend (`trend == 0`).
     * Juggluco: `rate = (trend - 3.0) * 1.3`, so 3 is flat.
     */
    val trendMgDlPerMin: Double? get() = if (trend == 0) null else (trend - 3.0) * 1.3

    /**
     * How far the retained 5-minute value lags the real-time one. Non-zero whenever glucose is
     * moving, and the direct measure of what a `historicalReading`-based pipeline costs you.
     */
    val historicLagsRealtime: Int get() = historicalReading - readingMgDl

    companion object {
        const val SIZE = 29
        val VALID_RANGE = 39..501     // Juggluco `validglucosevalue`

        private fun u16(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        private fun s16(b: ByteArray, o: Int): Int = u16(b, o).let { if (it > 0x7FFF) it - 0x10000 else it }

        fun parse(plain: ByteArray): Libre3GlucoseRecord? {
            if (plain.size != SIZE) return null
            return Libre3GlucoseRecord(
                lifeCount = u16(plain, 0),
                readingMgDl = u16(plain, 2),
                rateOfChange = s16(plain, 4),
                esaDuration = u16(plain, 6),
                projectedGlucose = u16(plain, 8),
                historicalLifeCount = u16(plain, 10),
                historicalReading = u16(plain, 12),
                trend = plain[14].toInt() and 0x07,
                uncappedCurrentMgDl = u16(plain, 15),
                uncappedHistoricMgDl = u16(plain, 17),
                temperature = u16(plain, 19)
            )
        }
    }
}
