package app.aaps.plugins.main.general.overview.compose

import androidx.compose.ui.graphics.compositeOver
import app.aaps.core.compose.theme.AapsSkins
import app.aaps.core.compose.theme.SkinValidation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeChartColorsTest {

    private val grounds = listOf(AapsSkins.Default.light, AapsSkins.Default.dark, AapsSkins.Midnight.dark)

    @Test fun `trace is red below, theme ink within and yellow above the inclusive display range`() {
        grounds.forEach { colors ->
            listOf(70.0, 100.0, 180.0).forEach { assertEquals(colors.chartInRange, stateColor(it, 70.0, 180.0, colors)) }
            assertEquals(colors.chartLow, stateColor(69.9, 70.0, 180.0, colors))
            assertEquals(colors.chartHigh, stateColor(180.1, 70.0, 180.0, colors))
        }
    }

    @Test fun `every trace ink keeps 3 to 1 over the range band and trace fill`() {
        grounds.forEach { colors ->
            val ground = colors.chartInRange.copy(alpha = TRACE_FILL_ALPHA)
                .compositeOver(colors.inRange.copy(alpha = RANGE_BAND_ALPHA).compositeOver(colors.surface))
            listOf(colors.chartLow, colors.chartInRange, colors.chartHigh).forEach { ink ->
                val ratio = SkinValidation.contrast(ink, ground)
                assertTrue(ratio >= SkinValidation.MIN_GRAPHIC_CONTRAST, "$ink on $ground is $ratio")
            }
        }
    }
}
