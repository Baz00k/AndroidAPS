package app.aaps.database.transactions

import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.interfaces.end

/**
 * @param refuseIfActiveAt when set, a record that has not ended by the time it returns is left valid and
 * reported in [TransactionResult.refusedActive]. It is called inside the transaction, after the record is read,
 * so neither a stale caller nor a clock moved backwards while the transaction waited can bypass the check.
 */
class InvalidateTemporaryBasalTransaction(
    val id: Long,
    val refuseIfActiveAt: (() -> Long)? = null
) : Transaction<InvalidateTemporaryBasalTransaction.TransactionResult>() {

    override fun run(): TransactionResult {
        val result = TransactionResult()
        val temporaryBasal = database.temporaryBasalDao.findById(id)
            ?: throw IllegalArgumentException("There is no such Temporary Basal with the specified ID.")
        if (temporaryBasal.isValid) {
            if (refuseIfActiveAt != null && temporaryBasal.end > refuseIfActiveAt.invoke()) {
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
