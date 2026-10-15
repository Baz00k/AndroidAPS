package app.aaps.core.interfaces.rx.weardata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GlucoseDisplayTest {
    private val time = 1_700_000_000_000L

    @Test fun `watch hides trend and delta once the reading is nine minutes old`() {
        val received = EventData.SingleBg(
            dataset = 0, timeStamp = time, sgvString = "151", glucoseUnits = "mg/dl", slopeArrow = "→",
            delta = "-15", deltaDetailed = "-15.0", sgv = 151.0, high = 180.0, low = 70.0, deltaMgdl = -15.0
        )
        // A watch clock slightly behind the phone's still shows the current trend.
        assertEquals("→", received.forGlucoseDisplay(time - 30_000).slopeArrow)
        assertEquals("→", received.forGlucoseDisplay(time + 539_999).slopeArrow)
        val expired = received.forGlucoseDisplay(time + 540_000)
        assertEquals("", expired.slopeArrow)
        assertEquals("--", expired.delta)
        assertNull(expired.deltaMgdl)
        assertEquals("151", expired.sgvString)
    }
}
