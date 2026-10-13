package app.aaps.core.objects.ui

import app.aaps.core.data.aps.BasalData
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.R

/** Profile-derived display rate, not confirmation of insulin delivery by the pump. */
data class BasalDisplay(val rate: String, val scheduled: String = "") {
    companion object {
        fun from(data: BasalData?, rh: ResourceHelper): BasalDisplay {
            if (data == null) return BasalDisplay(rh.gs(R.string.value_unavailable_short))
            val rate = if (data.isTempBasalRunning) data.tempBasalAbsolute else data.basal
            return BasalDisplay(
                rh.gs(R.string.pump_base_basal_rate, rate),
                if (data.isTempBasalRunning && data.basal > 0) rh.gs(R.string.pump_base_basal_rate, data.basal) else ""
            )
        }
    }
}
