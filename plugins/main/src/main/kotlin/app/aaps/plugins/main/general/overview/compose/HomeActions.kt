package app.aaps.plugins.main.general.overview.compose

/**
 * Callbacks the Home screen invokes. Each is wired in `OverviewFragment` to the **existing**
 * click handlers (which run `protectionCheck.queryProtection(...)` and open the real dialogs via
 * `uiInteraction`), so the Compose UI never bypasses any confirmation/constraint path.
 */
data class HomeActions(
    val onCalculator: () -> Unit = {},
    val onCarbs: () -> Unit = {},
    val onInsulin: () -> Unit = {},
    val onTempTarget: () -> Unit = {},
    val onExtendedBolus: () -> Unit = {},
    val onCancelExtendedBolus: () -> Unit = {},
    val onCalibration: () -> Unit = {},
    val onLoop: () -> Unit = {},
    val onBasal: () -> Unit = {},
    val onDeleteCarb: (entry: HomeUiState.CarbEntry) -> Unit = {}, // undo a recent carb entry (COB sheet)
    val onDeleteInsulin: (entry: HomeUiState.InsulinEntry) -> Unit = {}, // undo a recent bolus (IOB sheet)
    val onRange: (hours: Int) -> Unit = {},   // graph range segmented control
    val onDismissAlert: (alert: HomeUiState.Alert) -> Unit = {} // snooze/act on a home notification
) {

    fun onShortcut(shortcut: HomeShortcut) = when (shortcut) {
        HomeShortcut.CALCULATOR -> onCalculator()
        HomeShortcut.CARBS      -> onCarbs()
        HomeShortcut.INSULIN    -> onInsulin()
    }
}
