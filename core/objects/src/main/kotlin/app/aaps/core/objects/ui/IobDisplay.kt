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
    val unavailableReason: String = ""
) {
    companion object {
        fun load(profileFunction: ProfileFunction, calculator: IobCobCalculator, rh: ResourceHelper): IobDisplay {
            fun unavailable(reason: Int): IobDisplay {
                val unavailable = "--"
                return IobDisplay(unavailable, unavailable, unavailable, rh.gs(reason))
            }
            val profile = profileFunction.getProfile() ?: return unavailable(R.string.iob_no_active_profile)
            val bolus = calculator.calculateIobFromBolus().round().iob
            val basal = calculator.calculateIobFromTempBasalsIncludingConvertedExtended().round().basaliob
            // Calculators look up profiles independently. Do not publish their empty/partial totals
            // if a profile was removed or replaced while they ran; a later refresh can recalculate.
            val current = profileFunction.getProfile() ?: return unavailable(R.string.iob_no_active_profile)
            if (current !== profile && !profile.isEqual(current)) return unavailable(R.string.iob_profile_changed)
            return IobDisplay(
                rh.gs(R.string.format_insulin_units, bolus + basal),
                rh.gs(R.string.format_insulin_units, bolus),
                rh.gs(R.string.format_insulin_units, basal)
            )
        }
    }
}
