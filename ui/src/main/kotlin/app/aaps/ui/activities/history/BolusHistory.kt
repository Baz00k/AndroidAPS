package app.aaps.ui.activities.history

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.formatBolus

internal fun BS.toHistoryItem(dayLabel: String, time: String, rh: ResourceHelper): HistoryItem? {
    if (!isValid || type == BS.Type.PRIMING) return null
    val smb = type == BS.Type.SMB
    return HistoryItem(
        id, timestamp, dayLabel, time, if (smb) HistoryKind.SMB else HistoryKind.BOLUS,
        if (smb) "SMB" else "Bolus", notes ?: "",
        rh.gs(app.aaps.core.ui.R.string.format_insulin_units_label, formatBolus(amount))
    )
}
