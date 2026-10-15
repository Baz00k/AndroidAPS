package app.aaps.wear.watchfaces

import app.aaps.wear.data.ComplicationData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CircleGlucoseExpiryTest {
    @Test fun `Circle display getter suppresses stored expired delta for every dataset`() {
        val face = CircleWatchface()
        val initial = ComplicationData()
        val fresh = initial.bgData.copy(timeStamp = System.currentTimeMillis() - 60_000, sgvString = "151", sgv = 151.0, delta = "-15", slopeArrow = "→", deltaMgdl = -15.0)
        val storage = CircleWatchface::class.java.getDeclaredField("complicationData").apply { isAccessible = true }
        val getter = CircleWatchface::class.java.getDeclaredMethod("getSingleBg").apply { isAccessible = true }
        fun deltas(): List<String> = (getter.invoke(face) as Array<*>).map { (it as app.aaps.core.interfaces.rx.weardata.EventData.SingleBg).delta }
        storage.set(face, initial.copy(bgData = fresh, bgData1 = fresh.copy(dataset = 1), bgData2 = fresh.copy(dataset = 2)))
        assertEquals(listOf("-15", "-15", "-15"), deltas())
        // Same stored fields, advanced age; no phone event or stored-payload sanitization.
        val expired = fresh.copy(timeStamp = System.currentTimeMillis() - 540_001)
        storage.set(face, initial.copy(bgData = expired, bgData1 = expired.copy(dataset = 1), bgData2 = expired.copy(dataset = 2)))
        assertEquals(listOf("--", "--", "--"), deltas())
    }
}
