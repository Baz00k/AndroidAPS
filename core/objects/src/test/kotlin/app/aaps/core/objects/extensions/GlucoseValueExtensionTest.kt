package app.aaps.core.objects.extensions

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GlucoseValueExtensionTest {

    @Test fun `a sensor reading converts to the user's units for dosing`() {
        val reading = GV(timestamp = 0, value = 180.0, raw = 180.0, noise = null, trendArrow = TrendArrow.NONE, sourceSensor = SourceSensor.LIBRE_3)
        assertThat(reading.valueToUnits(GlucoseUnit.MGDL)).isEqualTo(180.0)
        // 18.0182 mg/dL per mmol/L
        assertThat(reading.valueToUnits(GlucoseUnit.MMOL)).isWithin(0.01).of(9.99)
    }
}
