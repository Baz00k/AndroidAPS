package app.aaps.core.objects.ui

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.objects.extensions.round
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
            fun unavailable(reason: Int): TherapyDisplay {
                val value = rh.gs(R.string.value_unavailable_short)
                return TherapyDisplay(IobDisplay(value, value, value, rh.gs(reason)), CobDisplay(value), BasalDisplay(value))
            }
            val profile = profileFunction.getProfile() ?: return unavailable(R.string.iob_no_active_profile)
            val bolusIob = calculator.calculateIobFromBolus().round()
            val basalIob = calculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
            val cob = CobDisplay.from(calculator.getCobInfo(reason), rh, formatter, compact)
            val basalDisplay = basal(profile)
            // Calculators look up profiles independently. Discard every field if the profile
            // disappeared or changed during the reads, including cached COB and basal values.
            val current = profileFunction.getProfile()
            if (current == null) return unavailable(R.string.iob_no_active_profile)
            if (current !== profile && !profile.isEqual(current)) return unavailable(R.string.iob_profile_changed)
            val iob = IobDisplay(
                iobFormat(bolusIob.iob + basalIob.basaliob), iobFormat(bolusIob.iob), iobFormat(basalIob.basaliob),
                activity = bolusIob.activity + basalIob.activity
            )
            return TherapyDisplay(iob, cob, basalDisplay)
        }
    }
}
