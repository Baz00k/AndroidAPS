package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.bolus.YpsoBolusRequestValidator
import app.aaps.pump.ypsopump.bolus.YpsoBolusShape
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class YpsoBolusRequestValidatorTest {
    @Test
    fun `normal manual and SMB requests share exact validation`() {
        for (treatment in listOf(YpsoBolusTreatment.NORMAL, YpsoBolusTreatment.SMB)) {
            val request = YpsoBolusRequestValidator.validate(1.2, treatment, aapsMaxBolus = 3.0)
            assertEquals(YpsoBolusShape.IMMEDIATE, request.shape)
            assertEquals(120, request.centiUnits)
            val payload = ByteBuffer.wrap(request.payload()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(120, payload.int)
            assertEquals(0, payload.int)
            assertEquals(0, payload.int)
            assertEquals(1, payload.get().toInt())
        }
    }

    @Test
    fun `limits and increment reject instead of silently changing the request`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 0.01, 0.05, 0.09, 1.05, 1.25, 30.1).forEach { units ->
            assertThrows(IllegalArgumentException::class.java, { YpsoBolusRequestValidator.validate(units, YpsoBolusTreatment.NORMAL, 31.0) }, "units=$units")
        }
        assertEquals(10, YpsoBolusRequestValidator.validate(0.1, YpsoBolusTreatment.NORMAL, 30.0).centiUnits)
        assertEquals(3000, YpsoBolusRequestValidator.validate(30.0, YpsoBolusTreatment.NORMAL, 30.0).centiUnits)
        assertThrows(IllegalArgumentException::class.java) {
            YpsoBolusRequestValidator.validate(3.1, YpsoBolusTreatment.NORMAL, aapsMaxBolus = 3.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            YpsoBolusRequestValidator.validate(1.0, YpsoBolusTreatment.NORMAL, aapsMaxBolus = Double.NaN)
        }
    }

    @Test
    fun `extended and combination requests carry exact shape fields`() {
        val extended = YpsoBolusRequestValidator.validateDelivery(0.5, durationMinutes = 15, immediateUnits = 0.0, YpsoBolusTreatment.NORMAL, 30.0)
        assertEquals(YpsoBolusShape.EXTENDED, extended.shape)
        assertEquals(50, extended.centiUnits)
        assertEquals(15, extended.durationMinutes)
        assertEquals(0, extended.immediateCentiUnits)
        val extendedPayload = ByteBuffer.wrap(extended.payload()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(50, extendedPayload.int)
        assertEquals(15, extendedPayload.int)
        assertEquals(0, extendedPayload.int)
        assertEquals(2, extendedPayload.get().toInt())

        val combined = YpsoBolusRequestValidator.validateDelivery(1.0, durationMinutes = 30, immediateUnits = 0.4, YpsoBolusTreatment.NORMAL, 30.0)
        assertEquals(YpsoBolusShape.COMBINED, combined.shape)
        assertEquals(100, combined.centiUnits)
        assertEquals(40, combined.immediateCentiUnits)
        assertEquals(60, combined.extendedCentiUnits)
        val combinedPayload = ByteBuffer.wrap(combined.payload()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(100, combinedPayload.int)
        assertEquals(30, combinedPayload.int)
        assertEquals(40, combinedPayload.int)
        assertEquals(2, combinedPayload.get().toInt())
    }

    @Test
    fun `invalid duration and combination amounts reject instead of rounding or clamping`() {
        assertThrows(IllegalArgumentException::class.java) {
            YpsoBolusRequestValidator.validateDelivery(0.5, 0, 0.1, YpsoBolusTreatment.NORMAL, 30.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            YpsoBolusRequestValidator.validateDelivery(0.5, -1, 0.0, YpsoBolusTreatment.NORMAL, 30.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            YpsoBolusRequestValidator.validateDelivery(0.5, 1441, 0.0, YpsoBolusTreatment.NORMAL, 30.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            YpsoBolusRequestValidator.validateDelivery(0.5, 15, 0.5, YpsoBolusTreatment.NORMAL, 30.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            YpsoBolusRequestValidator.validateDelivery(0.5, 15, 0.45, YpsoBolusTreatment.NORMAL, 30.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            YpsoBolusRequestValidator.validateDelivery(0.5, 15, 0.05, YpsoBolusTreatment.NORMAL, 30.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            YpsoBolusRequestValidator.validateDelivery(0.05, 15, 0.0, YpsoBolusTreatment.NORMAL, 30.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            YpsoBolusRequestValidator.validateDelivery(1.0, 15, 0.4, YpsoBolusTreatment.NORMAL, aapsMaxBolus = 0.5)
        }
    }

    @Test
    fun `fill and priming are explicitly unsupported rather than misclassified as therapy`() {
        assertThrows(IllegalArgumentException::class.java) {
            YpsoBolusRequestValidator.validate(1.0, YpsoBolusTreatment.PRIME, aapsMaxBolus = 3.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            YpsoBolusRequestValidator.validateDelivery(1.0, 15, 0.4, YpsoBolusTreatment.PRIME, 30.0)
        }
    }
}
