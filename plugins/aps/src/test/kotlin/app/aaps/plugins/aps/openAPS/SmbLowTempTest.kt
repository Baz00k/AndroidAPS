package app.aaps.plugins.aps.openAPS

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SmbLowTempTest {

    private fun assertLowTemp(durationReq: Int, expectedDuration: Int, expectedRate: Double) {
        val (duration, rate) = smbLowTemp(durationReq, basal = 0.9)
        assertThat(duration).isEqualTo(expectedDuration)
        assertThat(rate).isWithin(1e-9).of(expectedRate)
    }

    @Test
    fun `no withholding sets no temp`() {
        assertLowTemp(durationReq = -5, expectedDuration = 0, expectedRate = 0.0)
        assertLowTemp(durationReq = 0, expectedDuration = 0, expectedRate = 0.0)
    }

    @Test
    fun `short withholding delivers the remaining basal over 30 minutes`() {
        // Withholding 1, 15 or 29 of 30 minutes at 0.9 U/h leaves 0.435, 0.225 or 0.015 U for the 30-minute temp.
        assertLowTemp(durationReq = 1, expectedDuration = 30, expectedRate = 0.87)
        assertLowTemp(durationReq = 15, expectedDuration = 30, expectedRate = 0.45)
        assertLowTemp(durationReq = 29, expectedDuration = 30, expectedRate = 0.03)
    }

    @Test
    fun `withholding 30 minutes or more sets a zero temp of at most 60 minutes`() {
        assertLowTemp(durationReq = 30, expectedDuration = 30, expectedRate = 0.0)
        assertLowTemp(durationReq = 44, expectedDuration = 30, expectedRate = 0.0)
        assertLowTemp(durationReq = 45, expectedDuration = 60, expectedRate = 0.0)
        assertLowTemp(durationReq = 200, expectedDuration = 60, expectedRate = 0.0)
    }
}
