package app.aaps.database.transactions

import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.interfaces.end

/**
 * Updates a provisional basal, merging an independently imported record only when the driver
 * supplies both identities. No delivery is requested here; removals and known end events survive.
 */
class SyncTemporaryBasalWithTempIdTransaction(
    private val temporaryBasal: TemporaryBasal,
    private val newType: TemporaryBasal.Type?
) : Transaction<SyncTemporaryBasalWithTempIdTransaction.TransactionResult>() {

    override fun run(): TransactionResult {
        val ids = temporaryBasal.interfaceIDs
        val temporaryId = checkNotNull(ids.temporaryId) { "Temporary ID is null" }
        val pumpType = checkNotNull(ids.pumpType) { "Pump type is null" }
        val pumpSerial = checkNotNull(ids.pumpSerial) { "Pump serial is null" }
        val result = TransactionResult()
        val current = database.temporaryBasalDao.findByPumpTempIds(temporaryId, pumpType, pumpSerial) ?: return result
        check(ids.pumpId == null || current.interfaceIDs.pumpId == null || current.interfaceIDs.pumpId == ids.pumpId) {
            "temporary basal is already bound to another pump identity"
        }
        val imported = ids.pumpId?.let { database.temporaryBasalDao.findByPumpIds(it, pumpType, pumpSerial) }
        check(imported == null || imported.id == current.id || imported.interfaceIDs.temporaryId == null || imported.interfaceIDs.temporaryId == temporaryId) {
            "pump basal is already bound to another temporary identity"
        }
        val target = imported?.takeIf { it.id != current.id } ?: current
        val updated = target.copy(interfaceIDs_backing = target.interfaceIDs.copy())
        updated.timestamp = temporaryBasal.timestamp
        updated.rate = temporaryBasal.rate
        updated.duration = temporaryBasal.duration
        updated.isAbsolute = temporaryBasal.isAbsolute
        updated.type = newType ?: current.type
        updated.interfaceIDs.pumpId = ids.pumpId ?: target.interfaceIDs.pumpId
        updated.interfaceIDs.temporaryId = temporaryId
        updated.isValid = target.isValid && current.isValid

        // A newer basal/cancel fixes the physical end. Importing this very row may also have cut
        // its provisional copy at the row's slightly later timestamp; that is not a real stop.
        val ended = listOf(current, target).filter {
            it.interfaceIDs.endId != null && it.interfaceIDs.endId != ids.pumpId
        }.minByOrNull { it.end }
        updated.interfaceIDs.endId = ended?.interfaceIDs?.endId
        if (ended != null) {
            updated.duration = minOf(updated.duration, (ended.end - updated.timestamp).coerceAtLeast(1L))
        }
        if (target.id != current.id) {
            val retired = current.copy(isValid = false, interfaceIDs_backing = current.interfaceIDs.copy(temporaryId = null, pumpId = null, endId = null))
            database.temporaryBasalDao.updateExistingEntry(retired)
            result.updated.add(current to retired)
        }
        database.temporaryBasalDao.updateExistingEntry(updated)
        result.updated.add(target to updated)
        return result
    }

    class TransactionResult {

        val updated = mutableListOf<Pair<TemporaryBasal, TemporaryBasal>>()
    }
}
