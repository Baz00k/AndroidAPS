package app.aaps.ui.dialogs.compose

/** The first overview increment sets the wizard step; all three configure quick increments. */
data class WizardCarbControls(val step: Int, val quickIncrements: List<Int>, val maxCarbs: Int) {

    fun clamp(carbs: Int): Int = carbs.coerceIn(0, maxCarbs)

    fun decrease(carbs: Int): Int = addIncrement(carbs, -step)

    fun increase(carbs: Int): Int = addIncrement(carbs, step)

    fun addIncrement(carbs: Int, increment: Int): Int =
        (carbs.toLong() + increment).coerceIn(0L, maxCarbs.toLong()).toInt()

    companion object {

        fun fromOverviewIncrements(increments: List<Int>, maxCarbs: Int): WizardCarbControls {
            require(increments.size == 3)
            require(maxCarbs >= 0)
            // Overview allows zero and negative increments for carb corrections. Keep the step
            // usable and constrain every wizard carb adjustment to the effective safety limit.
            return WizardCarbControls(
                step = increments.first().let { if (it == 0) 1 else kotlin.math.abs(it) },
                quickIncrements = increments,
                maxCarbs = maxCarbs
            )
        }
    }
}
