package app.aaps.core.objects.ui

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.objects.extensions.round
import app.aaps.core.ui.R

/** Presentation only: an empty calculator total without a profile is not measured zero IOB. */
data class IobDisplay(
    val total: String,
    val bolus: String,
    val basal: String,
    val unavailableReason: String = "",
    /** Same profile-checked sample as the displayed IOB; null while unavailable. For status BGI. */
    val activity: Double? = null
) {
    val isAvailable: Boolean get() = unavailableReason.isBlank()

    companion object {
        fun load(
            profileFunction: ProfileFunction, calculator: IobCobCalculator, rh: ResourceHelper,
            format: (Double) -> String = { rh.gs(R.string.format_insulin_units, it) }
        ): IobDisplay {
            fun unavailable(reason: Int): IobDisplay {
                val unavailable = rh.gs(R.string.value_unavailable_short)
                return IobDisplay(unavailable, unavailable, unavailable, rh.gs(reason))
            }
            val profile = profileFunction.getProfile() ?: return unavailable(R.string.iob_no_active_profile)
            val bolus = calculator.calculateIobFromBolus().round()
            val basal = calculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
            // Calculators look up profiles independently. Do not publish their empty/partial totals
            // if a profile was removed or replaced while they ran; a later refresh can recalculate.
            val current = profileFunction.getProfile() ?: return unavailable(R.string.iob_no_active_profile)
            if (current !== profile && !profile.isEqual(current)) return unavailable(R.string.iob_profile_changed)
            return IobDisplay(
                format(bolus.iob + basal.basaliob), format(bolus.iob), format(basal.basaliob),
                activity = bolus.activity + basal.activity
            )
        }
    }
}
