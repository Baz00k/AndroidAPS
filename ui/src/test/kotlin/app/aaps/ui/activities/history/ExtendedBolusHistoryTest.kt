package app.aaps.ui.activities.history

import app.aaps.core.data.model.EB
import app.aaps.core.data.ue.ValueWithUnit
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ExtendedBolusHistoryTest {

    private val start = 1_000L
    private val duration = 90 * 60_000L
    private fun extended() = EB(id = 11, timestamp = start, amount = 1.5, duration = duration)
    private fun row(eb: EB, now: Long = start + duration) = eb.toHistoryItem(start, now, "Today", "12:30")!!

    @Test
    fun `ended extended bolus shows amount duration and rate and is removable`() {
        val item = row(extended())
        assertThat(item.kind).isEqualTo(HistoryKind.EXTENDED)
        assertThat(item.title).isEqualTo("Extended bolus")
        assertThat(item.value).isEqualTo("1.50 U")
        assertThat(item.sub).isEqualTo("90 min · 1.00 U/h")
        assertThat(item.key).isEqualTo("EXTENDED11")
        assertThat(item.removable).isTrue()
        assertThat(item.auditValues).containsExactly(
            ValueWithUnit.Timestamp(start), ValueWithUnit.Insulin(1.5), ValueWithUnit.UnitPerHour(1.0), ValueWithUnit.Minute(90)
        ).inOrder()
    }

    @Test
    fun `recorded extended bolus precision survives a cancelled partial dose`() {
        val item = row(extended().copy(amount = 0.025))
        assertThat(item.value.replace(',', '.')).isEqualTo("0.025 U")
        assertThat(item.auditValues).contains(ValueWithUnit.Insulin(0.025))
    }

    @Test
    fun `running extended bolus is listed but not removable`() {
        val item = row(extended(), now = start + duration - 1)
        assertThat(item.removable).isFalse()
        assertThat(item.sub).contains("Running, cancel before removing")
    }

    @Test
    fun `cancelled extended bolus shows the recorded delivered amount and shortened duration`() {
        val item = row(extended().copy(amount = 0.5, duration = 30 * 60_000L + 20_000L))
        assertThat(item.value).isEqualTo("0.50 U")
        assertThat(item.sub).startsWith("30 min 20 s · ")
        assertThat(item.auditValues).contains(ValueWithUnit.Minute(30))
    }

    @Test
    fun `emulated temporary basal records are labelled`() {
        assertThat(row(extended().copy(isEmulatingTempBasal = true)).sub).contains("Emulated temporary basal")
    }

    @Test
    fun `invalid old and future records are excluded`() {
        for (eb in listOf(extended().copy(isValid = false), extended().copy(timestamp = start - 1), extended().copy(timestamp = start + duration + 1))) {
            assertThat(eb.toHistoryItem(start, start + duration, "Today", "12:30")).isNull()
        }
    }

    @Test
    fun `extended boluses appear in all and bolus filters only`() {
        for (filter in HistoryFilter.entries) {
            assertThat(filter.matches(HistoryKind.EXTENDED)).isEqualTo(filter == HistoryFilter.ALL || filter == HistoryFilter.BOLUS)
        }
    }

    @Test
    fun `refresh unlocks a record that finished and keeps only still removable rows selected`() {
        val running = row(extended(), now = start + duration - 1)
        val other = row(extended().copy(id = 12, timestamp = start + 1, duration = 60_000L))
        val state = HistoryUiState(loading = false, items = listOf(running, other), selecting = true, selected = setOf(other.key))
        assertThat(state.nextRunningEnd()).isEqualTo(start + duration)

        val finished = row(extended(), now = start + duration)
        val refreshed = state.refreshedWith(listOf(finished, other))
        assertThat(refreshed.items.first { it.id == 11L }.removable).isTrue()
        assertThat(refreshed.nextRunningEnd()).isNull()
        assertThat(refreshed.selected).containsExactly(other.key)
    }

    @Test
    fun `refresh drops a selected row that is running again`() {
        val ended = row(extended())
        val state = HistoryUiState(loading = false, items = listOf(ended), selecting = true, selected = setOf(ended.key))
        val nowRunning = row(extended().copy(duration = duration + 60_000L), now = start + duration)
        assertThat(state.refreshedWith(listOf(nowRunning)).selected).isEmpty()
    }
}
