package app.aaps.core.interfaces.rx.events

import app.aaps.core.interfaces.R
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.formatBolus
import java.math.BigDecimal
import kotlin.math.min

/**
 * Custom status message and percent
 */
class EventOverviewBolusProgress(status: String, val id: Long? = null, percent: Int? = null, wearStatus: String? = null) : Event() {

    init {
        if (id == BolusProgressData.id || id == null) {
            BolusProgressData.status = status
            percent?.let { BolusProgressData.percent = it }
            BolusProgressData.wearStatus = wearStatus ?: status
        }
    }

    /**
     * Display the reported delivered amount and calculate percent.
     */
    constructor(rh: ResourceHelper, delivered: Double, id: Long? = null, deliveredForDisplay: BigDecimal? = null) :
        this(
            status = rh.gs(R.string.bolus_delivering, deliveredForDisplay?.let { formatBolus(it) } ?: formatBolus(delivered)),
            id = id,
            percent = min((delivered / BolusProgressData.insulin * 100).toInt(), 100),
            wearStatus = rh.gs(R.string.bolus_delivered_so_far, deliveredForDisplay?.let { formatBolus(it) } ?: formatBolus(delivered), formatBolus(BolusProgressData.insulin))
        )

    /**
     * Display completion or the existing percentage-based estimate without rounding the dose label.
     */
    constructor(rh: ResourceHelper, percent: Int, id: Long? = null) :
        this(
            status =
                if (percent == 100) rh.gs(R.string.bolus_delivered_successfully, formatBolus(BolusProgressData.insulin))
                else rh.gs(R.string.bolus_delivering, formatBolus(BolusProgressData.insulin * percent / 100.0)),
            id = id,
            percent = min(percent, 100),
            wearStatus =
                if (percent == 100) rh.gs(R.string.bolus_delivered_successfully, formatBolus(BolusProgressData.insulin))
                else rh.gs(R.string.bolus_delivered_so_far, formatBolus(BolusProgressData.insulin * percent / 100.0), formatBolus(BolusProgressData.insulin))
    )
}
