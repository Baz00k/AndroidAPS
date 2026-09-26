package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class HomeAdditionalGraphDataTest {

    private fun sample(time: Long, deviation: Double = -18.0, bgi: Double = -9.0): AutosensData = mock {
        on { this.time } doReturn time
        on { cob } doReturn 23.5
        on { this.deviation } doReturn deviation
        on { this.bgi } doReturn bgi
        on { autosensResult } doReturn AutosensResult(ratio = 1.2)
    }

    @Test
    fun `settings survive round trip including hidden series and graph four`() {
        val settings = AdditionalGraphSettings.decode("")
            .withGraph(AdditionalSeries.IOB, 0)
            .withGraph(AdditionalSeries.BGI, 4)
        val restored = AdditionalGraphSettings.decode(settings.encode())
        AdditionalSeries.entries.forEach { assertEquals(settings.graph(it), restored.graph(it)) }
        assertEquals(0, restored.graph(AdditionalSeries.IOB))
        assertEquals(4, restored.graph(AdditionalSeries.BGI))
        assertEquals(1, restored.graph(AdditionalSeries.COB))
    }

    @Test
    fun `corrupt or future preference entries do not hide known series`() {
        val settings = AdditionalGraphSettings.decode("IOB=99,COB=-1,SENSITIVITY=oops,UNKNOWN=2,BGI=4")
        assertEquals(1, settings.graph(AdditionalSeries.IOB))
        assertEquals(1, settings.graph(AdditionalSeries.COB))
        assertEquals(3, settings.graph(AdditionalSeries.SENSITIVITY))
        assertEquals(4, settings.graph(AdditionalSeries.BGI))
    }

    @Test
    fun `conversion preserves carbs and ratio while converting signed glucose impacts`() {
        val source = sample(300_000)
        val data = AdditionalGraphData.fromAutosens(listOf(source), 0, 600_000, 500_000) { it / 18.0 }
        assertEquals(23.5, data.points.getValue(AdditionalSeries.COB).single().value)
        assertEquals(20.0, data.points.getValue(AdditionalSeries.SENSITIVITY).single().value, 0.0001)
        assertEquals(-1.0, data.points.getValue(AdditionalSeries.DEVIATIONS).single().value)
        assertEquals(0.5, data.points.getValue(AdditionalSeries.BGI).single().value)
        assertEquals(-9.0, source.bgi) // Presentation cannot change the loop's signed BGI.
        val mgdl = AdditionalGraphData.fromAutosens(listOf(source), 0, 600_000, 500_000) { it }
        assertEquals(9.0, mgdl.points.getValue(AdditionalSeries.BGI).single().value)
    }

    @Test
    fun `history excludes future and out of range samples without filling gaps`() {
        val data = AdditionalGraphData.fromAutosens(
            listOf(sample(1_200_000), sample(900_000), sample(300_000), sample(0)),
            300_000, 1_800_000, 1_000_000
        ) { it }
        assertEquals(listOf(300_000L, 900_000L), data.points.getValue(AdditionalSeries.COB).map { it.time })
        assertTrue(data.points.getValue(AdditionalSeries.COB).zipWithNext().single().let { (a, b) -> b.time - a.time > ADDITIONAL_GRAPH_GAP_MS })
        assertTrue(AdditionalGraphData.fromAutosens(emptyList(), 0, 600_000, 500_000) { it }.points.values.all { it.isEmpty() })
    }

    @Test
    fun `nonfinite values are not rendered as zero`() {
        val data = AdditionalGraphData.fromAutosens(listOf(sample(300_000, Double.NaN, Double.POSITIVE_INFINITY)), 0, 600_000, 500_000) { it }
        assertTrue(data.points.getValue(AdditionalSeries.DEVIATIONS).isEmpty())
        assertTrue(data.points.getValue(AdditionalSeries.BGI).isEmpty())
        assertEquals(1, data.points.getValue(AdditionalSeries.COB).size)
    }

    @Test
    fun `scales include negative IOB zero and positive peaks`() {
        val (low, high) = additionalGraphBounds(listOf(GlucosePoint(0, -2.5), GlucosePoint(300_000, 4.0)), 0.2)
        assertTrue(low < -2.5)
        assertTrue(high > 4.0)
        val flat = additionalGraphBounds(listOf(GlucosePoint(0, 0.0)), 0.2)
        assertTrue(flat.first < 0 && flat.second > 0)
        val sensitivity = additionalGraphBounds(emptyList(), 10.0)
        assertEquals(-5.0 to 5.0, sensitivity)
    }
}
