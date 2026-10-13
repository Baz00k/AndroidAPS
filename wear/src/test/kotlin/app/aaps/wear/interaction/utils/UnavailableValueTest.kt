package app.aaps.wear.interaction.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnavailableValueTest {
    @Test fun `unavailable IOB and COB survive complication minimisation with or without units`() {
        for (units in SmallestDoubleString.Units.entries) {
            assertEquals("--", SmallestDoubleString("--", units).minimise(7))
            assertEquals("--", SmallestDoubleString("--", units).toString())
        }
    }

    @Test fun `watchfaces omit units only for unavailable insulin`() {
        assertEquals("--", "--".withIobUnits("U"))
        assertEquals("--", "".withIobUnits("U"))
        assertEquals("0.00U", "0.00".withIobUnits("U"))
        assertEquals("1.25U", "1.25".withIobUnits("U"))
    }
}
