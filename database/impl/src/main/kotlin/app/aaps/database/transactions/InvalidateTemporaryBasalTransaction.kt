package app.aaps.database.transactions

import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.interfaces.end

/**
 * @param refuseIfActiveAt when set, a record that has not ended by this time is left valid and reported in
 * [TransactionResult.refusedActive]. Checked inside the transaction so a stale caller cannot bypass it.
 */
class InvalidateTemporaryBasalTransaction(
    val id: Long,
    private val refuseIfActiveAt: Long? = null
) : Transaction<InvalidateTemporaryBasalTransaction.TransactionResult>() {

    override fun run(): TransactionResult {
        val result = TransactionResult()
        val temporaryBasal = database.temporaryBasalDao.findById(id)
            ?: throw IllegalArgumentException("There is no such Temporary Basal with the specified ID.")
        if (temporaryBasal.isValid) {
            if (refuseIfActiveAt != null && temporaryBasal.end > refuseIfActiveAt) {
                result.refusedActive.add(temporaryBasal)
                return result
            }
            temporaryBasal.isValid = false
            database.temporaryBasalDao.updateExistingEntry(temporaryBasal)
            result.invalidated.add(temporaryBasal)
        }
        return result
    }

    class TransactionResult {

        val invalidated = mutableListOf<TemporaryBasal>()
        val refusedActive = mutableListOf<TemporaryBasal>()
    }
}
