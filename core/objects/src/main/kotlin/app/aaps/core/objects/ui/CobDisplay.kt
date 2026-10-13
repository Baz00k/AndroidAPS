package app.aaps.core.objects.ui

import app.aaps.core.data.iob.CobInfo
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.objects.extensions.displayText
import app.aaps.core.objects.extensions.generateCOBString
import app.aaps.core.ui.R

data class CobDisplay(val value: String, val unavailableReason: String = "") {
    companion object {
        fun from(info: CobInfo?, rh: ResourceHelper, formatter: DecimalFormatter, compact: Boolean = false): CobDisplay =
            if (info?.displayCob == null) CobDisplay(rh.gs(R.string.value_unavailable_short), rh.gs(R.string.cob_unavailable))
            else CobDisplay(if (compact) info.generateCOBString(formatter) else requireNotNull(info.displayText(rh, formatter)))
    }
}
