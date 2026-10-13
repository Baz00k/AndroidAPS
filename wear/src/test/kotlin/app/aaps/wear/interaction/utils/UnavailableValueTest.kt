package app.aaps.wear.interaction.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnavailableValueTest {
    @Test fun `unavailable values stay unitless double dashes in complications and watchfaces`() {
        for (units in SmallestDoubleString.Units.entries) {
            assertEquals("--", SmallestDoubleString("--", units).minimise(7))
        }
        assertEquals("--", "--".withIobUnits("U"))
        assertEquals("0.00U", "0.00".withIobUnits("U"))
    }
}
