package app.aaps.database.transactions

import app.aaps.database.entities.ExtendedBolus
import app.aaps.database.entities.interfaces.end

/**
 * @param refuseIfActiveAt when set, a record that has not ended by this time is left valid and reported in
 * [TransactionResult.refusedActive]. Checked inside the transaction so a stale caller cannot bypass it.
 */
class InvalidateExtendedBolusTransaction(
    val id: Long,
    private val refuseIfActiveAt: Long? = null
) : Transaction<InvalidateExtendedBolusTransaction.TransactionResult>() {

    override fun run(): TransactionResult {
        val result = TransactionResult()
        val extendedBolus = database.extendedBolusDao.findById(id)
            ?: throw IllegalArgumentException("There is no such Extended Bolus with the specified ID.")
        if (extendedBolus.isValid) {
            if (refuseIfActiveAt != null && extendedBolus.end > refuseIfActiveAt) {
                result.refusedActive.add(extendedBolus)
                return result
            }
            extendedBolus.isValid = false
            database.extendedBolusDao.updateExistingEntry(extendedBolus)
            result.invalidated.add(extendedBolus)
        }
        return result
    }

    class TransactionResult {

        val invalidated = mutableListOf<ExtendedBolus>()
        val refusedActive = mutableListOf<ExtendedBolus>()
    }
}
