package app.aaps.core.objects.ui

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.Profile
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
        fun load(profile: Profile?, calculator: IobCobCalculator, rh: ResourceHelper): IobDisplay {
            if (profile == null) {
                val unavailable = "--"
                return IobDisplay(unavailable, unavailable, unavailable, rh.gs(R.string.iob_no_active_profile))
            }
            val bolus = calculator.calculateIobFromBolus().round().iob
            val basal = calculator.calculateIobFromTempBasalsIncludingConvertedExtended().round().basaliob
            return IobDisplay(
                rh.gs(R.string.format_insulin_units, bolus + basal),
                rh.gs(R.string.format_insulin_units, bolus),
                rh.gs(R.string.format_insulin_units, basal)
            )
        }
    }
}
