package app.aaps.database.transactions

import app.aaps.database.entities.ExtendedBolus
import app.aaps.database.entities.interfaces.end

/**
 * @param refuseIfActiveAt when set, a record that has not ended by the time it returns is left valid and
 * reported in [TransactionResult.refusedActive]. It is called inside the transaction, after the record is read,
 * so neither a stale caller nor a clock moved backwards while the transaction waited can bypass the check.
 */
class InvalidateExtendedBolusTransaction(
    val id: Long,
    val refuseIfActiveAt: (() -> Long)? = null
) : Transaction<InvalidateExtendedBolusTransaction.TransactionResult>() {

    override fun run(): TransactionResult {
        val result = TransactionResult()
        val extendedBolus = database.extendedBolusDao.findById(id)
            ?: throw IllegalArgumentException("There is no such Extended Bolus with the specified ID.")
        if (extendedBolus.isValid) {
            if (refuseIfActiveAt != null && extendedBolus.end > refuseIfActiveAt.invoke()) {
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
