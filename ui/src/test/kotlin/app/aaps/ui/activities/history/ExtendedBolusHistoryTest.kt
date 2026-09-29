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
}
