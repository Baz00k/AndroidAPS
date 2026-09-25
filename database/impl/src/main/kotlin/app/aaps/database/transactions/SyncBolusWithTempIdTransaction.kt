package app.aaps.database.transactions

import app.aaps.database.entities.Bolus

/**
 * Creates or updates the Bolus from pump synchronization
 *
 * @param requireValid for a link the driver only inferred rather than proved: bind nothing when either
 * record was removed or the pump record already carries another temporary id, since a removal is only
 * meaningful to carry over between two records known to be the same dose. [TransactionResult.refused]
 * reports it.
 */
class SyncBolusWithTempIdTransaction(
    private val bolus: Bolus,
    private val newType: Bolus.Type?,
    private val requireValid: Boolean = false,
) : Transaction<SyncBolusWithTempIdTransaction.TransactionResult>() {

    override fun run(): TransactionResult {
        bolus.interfaceIDs.temporaryId ?: bolus.interfaceIDs.pumpType ?: bolus.interfaceIDs.pumpSerial ?: throw IllegalStateException("Some pump ID is null")
        val result = TransactionResult()
        val current = database.bolusDao.findByPumpTempIds(bolus.interfaceIDs.temporaryId!!, bolus.interfaceIDs.pumpType!!, bolus.interfaceIDs.pumpSerial!!)
        result.found = current != null
        if (current == null && requireValid) {
            // No provisional record: report whether the pump record stands on its own, unowned and valid.
            val imported = bolus.interfaceIDs.pumpId?.let {
                database.bolusDao.findByPumpIds(it, bolus.interfaceIDs.pumpType!!, bolus.interfaceIDs.pumpSerial!!)
            }
            if (imported != null && (!imported.isValid ||
                    imported.interfaceIDs.temporaryId != null && imported.interfaceIDs.temporaryId != bolus.interfaceIDs.temporaryId)) {
                result.refused = true
            }
            result.pumpRecordOnly = imported != null && !result.refused
            return result
        }
        if (current != null) {
            val imported = bolus.interfaceIDs.pumpId?.let {
                database.bolusDao.findByPumpIds(it, bolus.interfaceIDs.pumpType!!, bolus.interfaceIDs.pumpSerial!!)
            }
            if (requireValid && (!current.isValid || imported != null && imported.id != current.id &&
                    (!imported.isValid || imported.interfaceIDs.temporaryId != null && imported.interfaceIDs.temporaryId != bolus.interfaceIDs.temporaryId))) {
                result.refused = true
                return result
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
                // Removal of either representation must survive merging their proven identities.
                imported.isValid = imported.isValid && current.isValid
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
        /** A record with the temporary id existed, valid or not. */
        var found = false
        /** [requireValid] refused to bind. */
        var refused = false
        /** [requireValid] found no provisional record, but a valid pump record owned by nothing else. */
        var pumpRecordOnly = false
    }
}
