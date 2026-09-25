package app.aaps.ui.dialogs.compose

/** The first overview increment sets the wizard step; all three configure quick increments. */
data class WizardCarbControls(val step: Int, val quickIncrements: List<Int>) {

    fun decrease(carbs: Int): Int = (carbs - step).coerceAtLeast(0)

    fun increase(carbs: Int): Int = carbs + step

    fun addIncrement(carbs: Int, increment: Int): Int = (carbs + increment).coerceAtLeast(0)

    companion object {

        fun fromOverviewIncrements(increments: List<Int>): WizardCarbControls {
            require(increments.size == 3)
            // Overview allows zero and negative increments for carb corrections. Keep the step
            // usable and clamp the wizard's carb total to zero when a negative chip is pressed.
            return WizardCarbControls(
                step = increments.first().let { if (it == 0) 1 else kotlin.math.abs(it) },
                quickIncrements = increments
            )
        }
    }
}
