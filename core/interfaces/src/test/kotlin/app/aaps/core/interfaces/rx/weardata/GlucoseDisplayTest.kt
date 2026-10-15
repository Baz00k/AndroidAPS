package app.aaps.core.interfaces.rx.weardata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GlucoseDisplayTest {
    private val time = 1_700_000_000_000L
    private fun payload() = EventData.SingleBg(
        dataset = 0, timeStamp = time, sgvString = "151", glucoseUnits = "mg/dl", slopeArrow = "→",
        delta = "-15", deltaDetailed = "-15.0", avgDelta = "--", avgDeltaDetailed = "--",
        sgvLevel = 0, sgv = 151.0, high = 180.0, low = 70.0, color = 0, deltaMgdl = -15.0
    )

    @Test fun `custom watchface numeric inputs reject missing glucose placeholders`() {
        for (sentinel in listOf("--", "---")) {
            assertNull(payload().copy(timeStamp = 0, sgvString = sentinel, sgv = 0.0).glucoseValueOrNull())
            assertNull(payload().copy(sgvString = sentinel).glucoseValueOrNull())
        }
        assertEquals(151.0, payload().glucoseValueOrNull())
    }

    @Test fun `disconnected watch loses trend at freshness boundary without receiving another payload`() {
        val received = payload()
        assertEquals("→", received.forGlucoseDisplay(time + 539_999).slopeArrow)
        val expired = received.forGlucoseDisplay(time + 540_000)
        assertEquals("", expired.slopeArrow)
        assertEquals("--", expired.delta)
        assertNull(expired.deltaMgdl)
        assertEquals(time, expired.timeStamp)
        assertEquals("151", expired.sgvString)
        assertEquals("", received.forGlucoseDisplay(time - 1).slopeArrow)
    }
}
