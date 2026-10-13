package app.aaps.core.objects.ui

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.ui.R

/** Publish profile-dependent readings together, never a mix of invalidated and numeric values. */
data class TherapyDisplay(val iob: IobDisplay, val cob: CobDisplay, val basal: BasalDisplay) {
    companion object {
        fun load(
            profileFunction: ProfileFunction,
            calculator: IobCobCalculator,
            rh: ResourceHelper,
            formatter: DecimalFormatter,
            reason: String,
            compact: Boolean = false,
            iobFormat: (Double) -> String = { rh.gs(R.string.format_insulin_units, it) },
            basal: (Profile) -> BasalDisplay
        ): TherapyDisplay {
            fun unavailable(iob: IobDisplay): TherapyDisplay {
                val value = rh.gs(R.string.value_unavailable_short)
                return TherapyDisplay(iob, CobDisplay(value), BasalDisplay(value))
            }
            val profile = profileFunction.getProfile()
            val iob = IobDisplay.load(profileFunction, calculator, rh, iobFormat)
            if (!iob.isAvailable) return unavailable(iob)
            val cob = CobDisplay.from(calculator.getCobInfo(reason), rh, formatter, compact)
            val basalDisplay = profile?.let(basal) ?: BasalDisplay.from(null, rh)
            val current = profileFunction.getProfile()
            val invalidReason = when {
                current == null -> R.string.iob_no_active_profile
                profile == null || (current !== profile && !profile.isEqual(current)) -> R.string.iob_profile_changed
                else -> null
            }
            if (invalidReason != null) {
                val value = rh.gs(R.string.value_unavailable_short)
                return unavailable(IobDisplay(value, value, value, rh.gs(invalidReason)))
            }
            return TherapyDisplay(iob, cob, basalDisplay)
        }
    }
}
