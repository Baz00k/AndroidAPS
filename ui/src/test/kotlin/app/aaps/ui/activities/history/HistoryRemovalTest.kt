package app.aaps.ui.activities.history

import app.aaps.core.data.model.EB
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
import org.mockito.kotlin.anyOrNull
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
    ).toHistoryItem(1_000, 200_000, "Today", "12:30")!!

    @Test
    fun `canceling confirmation does not call persistence`() {
        var shown = false
        confirmHistoryRemoval(listOf(basal()), { message, _ ->
            shown = true
            assertThat(message).contains("increase or decrease calculated IOB")
            assertThat(message).contains("does not change anything on the pump")
            // Dismissing or cancelling the dialog never runs its affirmative callback.
        }) { invalidateHistoryItems(persistence, it).test() }
        assertThat(shown).isTrue()
        verifyNoInteractions(persistence)
    }

    @Test
    fun `confirmed absolute basal uses guarded invalidation and original audit values`() {
        val item = basal()
        whenever(persistence.invalidateEndedTemporaryBasal(eq(item.id), any(), any(), isNull(), any()))
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
        verify(persistence).invalidateEndedTemporaryBasal(
            7, Action.TEMP_BASAL_REMOVED, Sources.Treatments, null,
            listOf(ValueWithUnit.Timestamp(1_000), ValueWithUnit.UnitPerHour(0.125), ValueWithUnit.Minute(1))
        )
    }

    @Test
    fun `percentage and suspend records retain their original audit units`() {
        for (item in listOf(basal(7, false, 150.0), basal(8, true, 0.0))) {
            whenever(persistence.invalidateEndedTemporaryBasal(eq(item.id), any(), any(), isNull(), any()))
                .thenReturn(Single.just(PersistenceLayer.TransactionResult<TB>()))
            invalidateHistoryItems(persistence, listOf(item)).test().assertComplete().assertNoErrors()
            verify(persistence).invalidateEndedTemporaryBasal(item.id, Action.TEMP_BASAL_REMOVED, Sources.Treatments, null, item.auditValues)
        }
    }

    @Test
    fun `batch waits for persistence and reports partial failures without losing successes`() {
        val first = basal(7)
        val failed = basal(8)
        val last = basal(9)
        val pending = SingleSubject.create<PersistenceLayer.TransactionResult<TB>>()
        val error = IllegalStateException("Database unavailable")
        whenever(persistence.invalidateEndedTemporaryBasal(eq(7), any(), any(), isNull(), any())).thenReturn(pending)
        whenever(persistence.invalidateEndedTemporaryBasal(eq(8), any(), any(), isNull(), any())).thenReturn(Single.error(error))
        whenever(persistence.invalidateEndedTemporaryBasal(eq(9), any(), any(), isNull(), any()))
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
        whenever(persistence.invalidateEndedTemporaryBasal(eq(7), any(), any(), isNull(), any())).thenThrow(error)
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

    private fun extended(id: Long = 11) = EB(
        id = id, timestamp = 1_000, amount = 1.5, duration = 30 * 60_000L
    ).toHistoryItem(1_000, 10_000_000, "Today", "12:30")!!

    @Test
    fun `confirmed extended bolus uses guarded invalidation with original audit values`() {
        val item = extended()
        whenever(persistence.invalidateEndedExtendedBolus(eq(item.id), any(), any(), isNull(), any()))
            .thenReturn(Single.just(PersistenceLayer.TransactionResult<EB>().also { it.invalidated.add(EB(timestamp = 1_000, amount = 1.5, duration = 1)) }))
        var message = ""
        confirmHistoryRemoval(listOf(item), { m, confirm -> message = m; confirm.run() }) {
            invalidateHistoryItems(persistence, it).test().assertComplete().assertValue { r -> r.removed == listOf(item) && r.failures.isEmpty() }
        }
        assertThat(message).contains("Removing an extended bolus lowers calculated IOB")
        assertThat(message).contains("does not change anything on the pump")
        verify(persistence).invalidateEndedExtendedBolus(
            11, Action.EXTENDED_BOLUS_REMOVED, Sources.Treatments, null,
            listOf(ValueWithUnit.Timestamp(1_000), ValueWithUnit.Insulin(1.5), ValueWithUnit.UnitPerHour(3.0), ValueWithUnit.Minute(30))
        )
        verify(persistence, org.mockito.kotlin.never()).invalidateExtendedBolus(any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `records refused as still running are failures that cannot be reselected`() {
        val tbr = basal(7)
        val eb = extended(11)
        whenever(persistence.invalidateEndedTemporaryBasal(eq(7), any(), any(), isNull(), any()))
            .thenReturn(Single.just(PersistenceLayer.TransactionResult<TB>().also { it.refusedActive.add(TB(timestamp = 1_000, type = TB.Type.NORMAL, isAbsolute = true, rate = 1.0, duration = 1)) }))
        whenever(persistence.invalidateEndedExtendedBolus(eq(11), any(), any(), isNull(), any()))
            .thenReturn(Single.just(PersistenceLayer.TransactionResult<EB>().also { it.refusedActive.add(EB(timestamp = 1_000, amount = 1.0, duration = 1)) }))
        val result = invalidateHistoryItems(persistence, listOf(tbr, eb)).blockingGet()
        assertThat(result.removed).isEmpty()
        assertThat(result.failures.map { it.first }).containsExactly(tbr, eb).inOrder()
        assertThat(result.failures.all { it.second is StillRunningException }).isTrue()

        val state = HistoryUiState(loading = false, items = listOf(tbr, eb), selecting = true, removing = true, selected = setOf(tbr.key, eb.key))
        val updated = result.applyTo(state)
        assertThat(updated.items.map { it.removable }).containsExactly(false, false)
        assertThat(updated.selected).isEmpty()
        assertThat(updated.selecting).isFalse()
        assertThat(updated.toggled(updated.items[0]).selected).isEmpty()
        assertThat(result.failureMessage()).contains("Still running, cancel before removing")
        assertThat(result.failureMessage()).doesNotContain("you can retry")
    }

    @Test
    fun `mixed failures keep only retryable rows selected`() {
        val running = basal(7)
        val broken = basal(8)
        val result = HistoryRemovalResult(emptyList(), listOf(running to StillRunningException(running), broken to IllegalStateException()))
        val updated = result.applyTo(HistoryUiState(loading = false, items = listOf(running, broken), selecting = true, removing = true, selected = setOf(running.key, broken.key)))
        assertThat(updated.selected).containsExactly(broken.key)
        assertThat(updated.selecting).isTrue()
        assertThat(updated.items.single { it.key == running.key }.removable).isFalse()
        assertThat(updated.items.single { it.key == broken.key }.removable).isTrue()
        assertThat(result.failureMessage()).contains("you can retry")
        assertThat(result.failureMessage()).contains("12:30   Temporary basal")
    }

    @Test
    fun `non removable rows are never selected or confirmed`() {
        val running = basal(7).copy(removable = false)
        val state = HistoryUiState(loading = false, items = listOf(running))
        val selecting = state.startedSelecting(running)
        assertThat(selecting.selecting).isTrue()
        assertThat(selecting.selected).isEmpty()
        assertThat(selecting.toggled(running).selected).isEmpty()
        confirmHistoryRemoval(listOf(running), { _, _ -> error("Unexpected dialog") }) { error("Unexpected removal") }
        verifyNoInteractions(persistence)
    }
}
