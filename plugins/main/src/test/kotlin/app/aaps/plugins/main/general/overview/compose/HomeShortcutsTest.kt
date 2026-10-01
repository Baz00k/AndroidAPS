package app.aaps.plugins.main.general.overview.compose

import app.aaps.plugins.main.general.overview.compose.HomeMenuItem.Shortcut
import app.aaps.plugins.main.general.overview.compose.HomeShortcut.CALCULATOR
import app.aaps.plugins.main.general.overview.compose.HomeShortcut.CARBS
import app.aaps.plugins.main.general.overview.compose.HomeShortcut.INSULIN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeShortcutsTest {

    private val target = HomeMenuItem.TempTarget(null)
    private val extended = HomeMenuItem.ExtendedBolus(null)

    private fun layout(
        calculator: Boolean = true, carbs: Boolean = true, insulin: Boolean = true,
        tempTarget: HomeMenuItem.TempTarget? = target, extendedBolus: HomeMenuItem.ExtendedBolus? = extended, calibration: Boolean = false
    ) = homeActionLayout(calculator, carbs, insulin, tempTarget, extendedBolus, calibration)

    @Test
    fun `all shortcuts visible keeps the menu to secondary actions`() {
        val l = layout()
        assertEquals(listOf(CARBS, CALCULATOR, INSULIN), l.bar)
        assertEquals(listOf(target, extended), l.menu)
    }

    @Test
    fun `a hidden shortcut moves into the menu ahead of secondary actions`() {
        val l = layout(insulin = false)
        assertEquals(listOf(CARBS, CALCULATOR), l.bar)
        assertEquals(listOf(Shortcut(INSULIN), target, extended), l.menu)
    }

    @Test
    fun `with every shortcut hidden all three stay reachable, most used first`() {
        val l = layout(calculator = false, carbs = false, insulin = false)
        assertEquals(emptyList<HomeShortcut>(), l.bar)
        assertEquals(listOf(Shortcut(CALCULATOR), Shortcut(CARBS), Shortcut(INSULIN), target, extended), l.menu)
    }

    @Test
    fun `secondary actions appear only when they can be offered`() {
        assertEquals(emptyList<HomeMenuItem>(), layout(tempTarget = null, extendedBolus = null).menu)
        assertEquals(listOf(HomeMenuItem.Calibration), layout(tempTarget = null, extendedBolus = null, calibration = true).menu)
    }
}
