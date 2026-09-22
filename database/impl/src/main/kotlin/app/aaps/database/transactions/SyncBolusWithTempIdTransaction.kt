package app.aaps.database.transactions

import app.aaps.database.entities.Bolus

/**
 * Creates or updates the Bolus from pump synchronization
 */
class SyncBolusWithTempIdTransaction(
    private val bolus: Bolus,
    private val newType: Bolus.Type?
) : Transaction<SyncBolusWithTempIdTransaction.TransactionResult>() {

    override fun run(): TransactionResult {
        bolus.interfaceIDs.temporaryId ?: bolus.interfaceIDs.pumpType ?: bolus.interfaceIDs.pumpSerial ?: throw IllegalStateException("Some pump ID is null")
        val result = TransactionResult()
        val current = database.bolusDao.findByPumpTempIds(bolus.interfaceIDs.temporaryId!!, bolus.interfaceIDs.pumpType!!, bolus.interfaceIDs.pumpSerial!!)
        if (current != null) {
            val imported = bolus.interfaceIDs.pumpId?.let {
                database.bolusDao.findByPumpIds(it, bolus.interfaceIDs.pumpType!!, bolus.interfaceIDs.pumpSerial!!)
            }
            if (imported != null && imported.id != current.id) {
                // Both identifiers were explicitly supplied by the driver for one physical dose.
                // Preserve the imported pump record and atomically invalidate the provisional copy.
                check(imported.interfaceIDs.temporaryId == null || imported.interfaceIDs.temporaryId == bolus.interfaceIDs.temporaryId) {
                    "pump bolus is already bound to another temporary identity"
                }
                imported.timestamp = bolus.timestamp
                imported.amount = bolus.amount
                imported.type = newType ?: current.type
                current.isValid = false
                current.interfaceIDs.temporaryId = null
                database.bolusDao.updateExistingEntry(current)
                imported.interfaceIDs.temporaryId = bolus.interfaceIDs.temporaryId
                database.bolusDao.updateExistingEntry(imported)
                result.updated.add(current)
                result.updated.add(imported)
                return result
            }
            current.timestamp = bolus.timestamp
            current.amount = bolus.amount
            current.type = newType ?: current.type
            current.interfaceIDs.pumpId = bolus.interfaceIDs.pumpId
            database.bolusDao.updateExistingEntry(current)
            result.updated.add(current)
        }
        return result
    }

    class TransactionResult {

        val updated = mutableListOf<Bolus>()
    }
}
