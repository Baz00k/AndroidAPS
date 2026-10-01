package app.aaps.plugins.main.general.overview.compose

import androidx.compose.runtime.Immutable

/** The primary treatment actions, in bar order (left to right). */
enum class HomeShortcut { CARBS, CALCULATOR, INSULIN }

/** A secondary action in the Home "+" menu. */
@Immutable
sealed interface HomeMenuItem {

    /** A primary action whose bar shortcut the user has hidden. Hidden is not disabled: it lives here instead. */
    data class Shortcut(val shortcut: HomeShortcut) : HomeMenuItem

    /** [status] describes the active target, or is null when none is running. */
    data class TempTarget(val status: String?) : HomeMenuItem

    /** [status] describes the running extended bolus, or is null when none is running. */
    data class ExtendedBolus(val status: String?, val enabled: Boolean = true) : HomeMenuItem

    data object Calibration : HomeMenuItem
}

@Immutable
data class HomeActionLayout(
    val bar: List<HomeShortcut> = HomeShortcut.entries,
    val menu: List<HomeMenuItem> = emptyList()
)

/**
 * Split the treatment actions between the bar and the "+" menu. Every primary action is always
 * reachable: a hidden shortcut moves to the top of the menu, in order of how often it is used.
 * Secondary actions are passed in only when they can currently be offered.
 */
fun homeActionLayout(
    showCalculator: Boolean,
    showCarbs: Boolean,
    showInsulin: Boolean,
    tempTarget: HomeMenuItem.TempTarget?,
    extendedBolus: HomeMenuItem.ExtendedBolus?,
    calibration: Boolean
): HomeActionLayout {
    val visible = mapOf(
        HomeShortcut.CALCULATOR to showCalculator,
        HomeShortcut.CARBS to showCarbs,
        HomeShortcut.INSULIN to showInsulin
    )
    val hiddenByImportance = listOf(HomeShortcut.CALCULATOR, HomeShortcut.CARBS, HomeShortcut.INSULIN).filterNot { visible.getValue(it) }
    return HomeActionLayout(
        bar = HomeShortcut.entries.filter { visible.getValue(it) },
        menu = hiddenByImportance.map { HomeMenuItem.Shortcut(it) } + listOfNotNull(
            tempTarget,
            extendedBolus,
            HomeMenuItem.Calibration.takeIf { calibration }
        )
    )
}
