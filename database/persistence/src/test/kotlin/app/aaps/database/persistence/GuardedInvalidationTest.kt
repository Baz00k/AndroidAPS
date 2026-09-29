package app.aaps.database.persistence

import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.database.AppRepository
import app.aaps.database.entities.ExtendedBolus
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.transactions.InvalidateExtendedBolusTransaction
import app.aaps.database.entities.interfaces.end
import app.aaps.database.transactions.InvalidateTemporaryBasalTransaction
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The guarded removal methods must hand the transaction a clock that reads [DateUtil.now] when the transaction
 * runs, and must report a refusal instead of an invalidation. The transactions' own tests cover the comparison.
 */
class GuardedInvalidationTest {

    private val repository: AppRepository = mock()
    private val dateUtil: DateUtil = mock()
    private val config: Config = mock { on { AAPSCLIENT } doReturn true }
    private val persistence = PersistenceLayerImpl(mock<AAPSLogger>(), repository, dateUtil, config, mock())
    private val audit = listOf<ValueWithUnit>(ValueWithUnit.Timestamp(1_000))

    private val extended = ExtendedBolus(timestamp = 1_000, amount = 1.0, duration = 60_000).also { it.id = 5 }
    private val basal = TemporaryBasal(timestamp = 1_000, rate = 1.0, duration = 60_000, type = TemporaryBasal.Type.NORMAL, isAbsolute = true).also { it.id = 6 }

    private fun stubExtendedTransaction() {
        whenever(repository.runTransactionForResult(any<InvalidateExtendedBolusTransaction>())).thenAnswer { call ->
            val guard = call.getArgument<InvalidateExtendedBolusTransaction>(0).refuseIfActiveAt
            Single.fromCallable {
                InvalidateExtendedBolusTransaction.TransactionResult().also {
                    if (guard == null || extended.end <= guard()) it.invalidated.add(extended) else it.refusedActive.add(extended)
                }
            }
        }
    }

    private fun stubBasalTransaction() {
        whenever(repository.runTransactionForResult(any<InvalidateTemporaryBasalTransaction>())).thenAnswer { call ->
            val guard = call.getArgument<InvalidateTemporaryBasalTransaction>(0).refuseIfActiveAt
            Single.fromCallable {
                InvalidateTemporaryBasalTransaction.TransactionResult().also {
                    if (guard == null || basal.end <= guard()) it.invalidated.add(basal) else it.refusedActive.add(basal)
                }
            }
        }
    }

    @Test
    fun `ended extended bolus is invalidated`() {
        stubExtendedTransaction()
        whenever(dateUtil.now()).thenReturn(61_000)
        val result = persistence.invalidateEndedExtendedBolus(5, Action.EXTENDED_BOLUS_REMOVED, Sources.Treatments, null, audit).blockingGet()
        assertThat(result.invalidated).hasSize(1)
        assertThat(result.refusedActive).isEmpty()
    }

    @Test
    fun `running extended bolus is refused and reported`() {
        stubExtendedTransaction()
        whenever(dateUtil.now()).thenReturn(60_999)
        val result = persistence.invalidateEndedExtendedBolus(5, Action.EXTENDED_BOLUS_REMOVED, Sources.Treatments, null, audit).blockingGet()
        assertThat(result.invalidated).isEmpty()
        assertThat(result.refusedActive).hasSize(1)
    }

    @Test
    fun `running temporary basal is refused and reported`() {
        stubBasalTransaction()
        whenever(dateUtil.now()).thenReturn(60_999)
        val result = persistence.invalidateEndedTemporaryBasal(6, Action.TEMP_BASAL_REMOVED, Sources.Treatments, null, audit).blockingGet()
        assertThat(result.invalidated).isEmpty()
        assertThat(result.refusedActive).hasSize(1)
    }

    @Test
    fun `ended temporary basal is invalidated`() {
        stubBasalTransaction()
        whenever(dateUtil.now()).thenReturn(61_000)
        val result = persistence.invalidateEndedTemporaryBasal(6, Action.TEMP_BASAL_REMOVED, Sources.Treatments, null, audit).blockingGet()
        assertThat(result.invalidated).hasSize(1)
        assertThat(result.refusedActive).isEmpty()
    }

    @Test
    fun `clock is read when the transaction runs not when it is requested`() {
        stubExtendedTransaction()
        whenever(dateUtil.now()).thenReturn(61_001)
        val pending = persistence.invalidateEndedExtendedBolus(5, Action.EXTENDED_BOLUS_REMOVED, Sources.Treatments, null, audit)
        whenever(dateUtil.now()).thenReturn(60_999)
        assertThat(pending.blockingGet().refusedActive).hasSize(1)
    }

    @Test
    fun `unguarded removal still invalidates a running record for sync and pump drivers`() {
        stubExtendedTransaction()
        whenever(dateUtil.now()).thenReturn(1_500)
        val result = persistence.invalidateExtendedBolus(5, Action.EXTENDED_BOLUS_REMOVED, Sources.NSClient, null, audit).blockingGet()
        assertThat(result.invalidated).hasSize(1)
        assertThat(result.refusedActive).isEmpty()
    }
}
