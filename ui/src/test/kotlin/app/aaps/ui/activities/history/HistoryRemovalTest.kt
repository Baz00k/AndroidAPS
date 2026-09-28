package app.aaps.ui.activities.history

import app.aaps.core.data.model.TB
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.SingleSubject
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class HistoryRemovalTest {

    private val persistence: PersistenceLayer = mock()
    private fun basal(id: Long = 7, absolute: Boolean = true, rate: Double = 0.125) = TB(
        id = id, timestamp = 1_000, type = TB.Type.NORMAL,
        isAbsolute = absolute, rate = rate, duration = 95_000
    ).toHistoryItem(1_000, 2_000, "Today", "12:30")!!

    @Test
    fun `canceling confirmation does not call persistence`() {
        var shown = false
        confirmHistoryRemoval(listOf(basal()), { message, _ ->
            shown = true
            assertThat(message).contains("increase or decrease calculated IOB")
            assertThat(message).contains("does not cancel a temporary basal on the pump")
            // Dismissing or cancelling the dialog never runs its affirmative callback.
        }) { invalidateHistoryItems(persistence, it).test() }
        assertThat(shown).isTrue()
        verifyNoInteractions(persistence)
    }

    @Test
    fun `confirmed absolute basal uses upstream invalidation and original audit values`() {
        val item = basal()
        whenever(persistence.invalidateTemporaryBasal(eq(item.id), any(), any(), isNull(), any()))
            .thenReturn(Single.just(PersistenceLayer.TransactionResult<TB>()))
        val selection = mutableListOf(item)
        var confirm: Runnable? = null
        var completions = 0
        confirmHistoryRemoval(selection, { _, callback -> confirm = callback }) {
            invalidateHistoryItems(persistence, it).test().assertComplete().assertNoErrors()
            completions++
        }
        verifyNoInteractions(persistence)
        selection.clear()
        confirm!!.run()
        confirm!!.run()
        assertThat(completions).isEqualTo(1)
        verify(persistence).invalidateTemporaryBasal(
            7, Action.TEMP_BASAL_REMOVED, Sources.Treatments, null,
            listOf(ValueWithUnit.Timestamp(1_000), ValueWithUnit.UnitPerHour(0.125), ValueWithUnit.Minute(1))
        )
    }

    @Test
    fun `percentage and suspend records retain their original audit units`() {
        for (item in listOf(basal(7, false, 150.0), basal(8, true, 0.0))) {
            whenever(persistence.invalidateTemporaryBasal(eq(item.id), any(), any(), isNull(), any()))
                .thenReturn(Single.just(PersistenceLayer.TransactionResult<TB>()))
            invalidateHistoryItems(persistence, listOf(item)).test().assertComplete().assertNoErrors()
            verify(persistence).invalidateTemporaryBasal(item.id, Action.TEMP_BASAL_REMOVED, Sources.Treatments, null, item.auditValues)
        }
    }

    @Test
    fun `batch waits for persistence and reports partial failures without losing successes`() {
        val first = basal(7)
        val failed = basal(8)
        val last = basal(9)
        val pending = SingleSubject.create<PersistenceLayer.TransactionResult<TB>>()
        val error = IllegalStateException("Database unavailable")
        whenever(persistence.invalidateTemporaryBasal(eq(7), any(), any(), isNull(), any())).thenReturn(pending)
        whenever(persistence.invalidateTemporaryBasal(eq(8), any(), any(), isNull(), any())).thenReturn(Single.error(error))
        whenever(persistence.invalidateTemporaryBasal(eq(9), any(), any(), isNull(), any()))
            .thenReturn(Single.just(PersistenceLayer.TransactionResult<TB>()))
        val observer = invalidateHistoryItems(persistence, listOf(first, failed, last)).test()
        observer.assertNotComplete().assertNoValues()
        pending.onSuccess(PersistenceLayer.TransactionResult())
        observer.assertComplete().assertNoErrors().assertValue {
            it.removed == listOf(first, last) && it.failures == listOf(failed to error)
        }
    }

    @Test
    fun `synchronous persistence failures are also returned to the UI`() {
        val item = basal()
        val error = IllegalStateException("Cannot start transaction")
        whenever(persistence.invalidateTemporaryBasal(eq(7), any(), any(), isNull(), any())).thenThrow(error)
        invalidateHistoryItems(persistence, listOf(item)).test().assertComplete().assertNoErrors().assertValue {
            it.removed.isEmpty() && it.failures == listOf(item to error)
        }
    }

    @Test
    fun `empty selection does not show a confirmation`() {
        confirmHistoryRemoval(emptyList(), { _, _ -> error("Unexpected dialog") }) { error("Unexpected removal") }
        verifyNoInteractions(persistence)
    }

    @Test
    fun `event only confirmation does not warn about insulin or pump delivery`() {
        val event = basal().copy(kind = HistoryKind.EVENT, title = "Note", value = "")
        confirmHistoryRemoval(listOf(event), { message, _ ->
            assertThat(message).isEqualTo("12:30   Note")
        }) { error("Unconfirmed") }
    }

    @Test
    fun `partial failure keeps only failed rows selected and permits retry`() {
        val removed = basal(7)
        val failed = basal(8)
        val untouched = basal(9)
        val state = HistoryUiState(
            loading = false, items = listOf(removed, failed, untouched),
            selecting = true, removing = true, selected = setOf(removed.key, failed.key)
        )
        val result = HistoryRemovalResult(listOf(removed), listOf(failed to IllegalStateException()))
        val updated = result.applyTo(state)
        assertThat(updated.items).containsExactly(failed, untouched).inOrder()
        assertThat(updated.selected).containsExactly(failed.key)
        assertThat(updated.selecting).isTrue()
        assertThat(updated.removing).isFalse()
    }

    @Test
    fun `successful removal clears selection without removing other kinds with the same id`() {
        val removed = basal(7)
        val carbs = removed.copy(kind = HistoryKind.CARBS)
        val state = HistoryUiState(
            loading = false, items = listOf(removed, carbs),
            selecting = true, removing = true, selected = setOf(removed.key)
        )
        val updated = HistoryRemovalResult(listOf(removed), emptyList()).applyTo(state)
        assertThat(updated.items).containsExactly(carbs)
        assertThat(updated.selected).isEmpty()
        assertThat(updated.selecting).isFalse()
        assertThat(updated.removing).isFalse()
    }

}
