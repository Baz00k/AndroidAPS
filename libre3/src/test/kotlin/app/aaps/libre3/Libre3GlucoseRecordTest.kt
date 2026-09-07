package app.aaps.libre3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Decoded against a REAL record captured from a live sensor, pulled off the
 * `saveLibre3MinuteL` JNI call.
 *
 * The `lifeCount` field is what pins the layout down: the sensor was activated at 15:35, the
 * capture is at 17:35, and the record says 119 minutes. That is only consistent with one
 * alignment, so the struct offsets are confirmed rather than assumed.
 */
class Libre3GlucoseRecordTest {

    /** 29-byte plaintext, exactly as captured. */
    private val captured = "77005c009eff0000141e640073000b5c0073007d0b460406444a0e0000"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `record is 29 bytes`() {
        assertThat(captured.size).isEqualTo(Libre3GlucoseRecord.SIZE)
    }

    @Test
    fun `lifeCount matches the sensor age at capture time`() {
        val r = Libre3GlucoseRecord.parse(captured)!!
        // activated 15:35, captured 17:35 -> 119 minutes (the +/-1 is the minute boundary)
        assertThat(r.lifeCount).isEqualTo(119)
    }

    @Test
    fun `real-time and uncapped current agree`() {
        val r = Libre3GlucoseRecord.parse(captured)!!
        assertThat(r.readingMgDl).isEqualTo(92)
        assertThat(r.uncappedCurrentMgDl).isEqualTo(r.readingMgDl)
    }

    @Test
    fun `historic and uncapped historic agree`() {
        val r = Libre3GlucoseRecord.parse(captured)!!
        assertThat(r.historicalReading).isEqualTo(115)
        assertThat(r.uncappedHistoricMgDl).isEqualTo(r.historicalReading)
    }

    @Test
    fun `historic lags real-time during a fall`() {
        val r = Libre3GlucoseRecord.parse(captured)!!
        // BG was dropping hard at 17:35. The retained 5-minute value still reads 23 mg/dL
        // (1.3 mmol/L) HIGHER than the sensor's own real-time number — the lag this whole
        // project exists to remove.
        assertThat(r.historicLagsRealtime).isEqualTo(23)
    }

    @Test
    fun `reading is inside Abbott's validity window`() {
        assertThat(Libre3GlucoseRecord.parse(captured)!!.isValid).isTrue()
    }

    @Test
    fun `trend decodes to a rate`() {
        val r = Libre3GlucoseRecord.parse(captured)!!
        assertThat(r.trend).isEqualTo(3)
        assertThat(r.trendMgDlPerMin).isEqualTo(0.0)   // 3 is flat: (3-3)*1.3
    }

    @Test
    fun `trend zero means no trend rather than flat`() {
        val noTrend = captured.copyOf().also { it[14] = 0 }
        assertThat(Libre3GlucoseRecord.parse(noTrend)!!.trendMgDlPerMin).isNull()
    }

    @Test
    fun `wrong length is rejected`() {
        assertThat(Libre3GlucoseRecord.parse(ByteArray(28))).isNull()
        assertThat(Libre3GlucoseRecord.parse(ByteArray(35))).isNull()
    }

    @Test
    fun `out of range reading is not valid`() {
        val low = captured.copyOf().also { it[2] = 10; it[3] = 0 }    // 10 mg/dL
        assertThat(Libre3GlucoseRecord.parse(low)!!.isValid).isFalse()
    }
}
