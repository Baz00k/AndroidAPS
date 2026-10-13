package app.aaps.core.objects.ui

import app.aaps.core.data.aps.BasalData
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.R
import kotlin.math.roundToInt

/** Profile-derived display rate, not confirmation of insulin delivery by the pump. */
data class BasalDisplay(val rate: String, val detail: String = "") {
    companion object {
        fun from(data: BasalData, rh: ResourceHelper): BasalDisplay {
            val rate = if (data.isTempBasalRunning) data.tempBasalAbsolute else data.basal
            return BasalDisplay(
                rh.gs(R.string.pump_base_basal_rate, rate),
                if (data.isTempBasalRunning && data.basal > 0)
                    "${(rate / data.basal * 100).roundToInt()}% · ${rh.gs(R.string.pump_base_basal_rate, data.basal)} sched"
                else ""
            )
        }
    }
}
