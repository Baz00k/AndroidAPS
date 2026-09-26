package app.aaps.ui.activities.history

import app.aaps.core.data.model.TB
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TemporaryBasalHistoryTest {

    private fun basal() = TB(
        id = 7, timestamp = 1_000, type = TB.Type.NORMAL,
        isAbsolute = true, rate = 0.125, duration = 30 * 60_000L
    )

    private fun row(basal: TB) = basal.toHistoryItem(1_000, 2_000, "Today", "12:30")!!

    @Test
    fun `absolute basal preserves rate precision and identity without implying bolus units`() {
        val item = row(basal())
        assertThat(item.value).isEqualTo("0.125 U/h")
        assertThat(item.sub).isEqualTo("Recorded duration: 30 min")
        assertThat(item.timestamp).isEqualTo(1_000L)
        assertThat(item.time).isEqualTo("12:30")
        assertThat(item.dayLabel).isEqualTo("Today")
        assertThat(item.key).isEqualTo("TBR7")
        assertThat(item.key).isNotEqualTo(item.copy(kind = HistoryKind.BOLUS).key)
        assertThat(item.removable).isFalse()
    }

    @Test
    fun `relative rates stay percentages including zero and above one hundred`() {
        for (rate in listOf(0.0, 100.0, 150.0)) {
            assertThat(row(basal().copy(isAbsolute = false, rate = rate)).value).isEqualTo("${rate.toInt()}%")
        }
    }

    @Test
    fun `early stop uses recorded duration including partial minutes`() {
        assertThat(row(basal().copy(duration = 95_000)).sub).isEqualTo("Recorded duration: 1 min 35 s")
        assertThat(row(basal().copy(duration = 20_000)).sub).isEqualTo("Recorded duration: 0 min 20 s")
    }

    @Test
    fun `suspends remain visible as zero rate basal records`() {
        val item = row(basal().copy(type = TB.Type.PUMP_SUSPEND, rate = 0.0))
        assertThat(item.value).isEqualTo("0 U/h")
        assertThat(item.sub).contains("Pump suspend")
        assertThat(item.removable).isFalse()
    }

    @Test
    fun `invalid future old and synthetic records are excluded`() {
        for (basal in listOf(
            basal().copy(isValid = false), basal().copy(timestamp = 999),
            basal().copy(timestamp = 2_001), basal().copy(type = TB.Type.FAKE_EXTENDED)
        )) {
            assertThat(basal.toHistoryItem(1_000, 2_000, "Today", "12:30")).isNull()
        }
        assertThat(row(basal().copy(timestamp = 2_000)).timestamp).isEqualTo(2_000L)
    }

    @Test
    fun `basals appear in all and TBR filters only`() {
        for (filter in HistoryFilter.entries) {
            assertThat(filter.matches(HistoryKind.TBR)).isEqualTo(filter == HistoryFilter.ALL || filter == HistoryFilter.TBR)
        }
        for (kind in HistoryKind.entries.filter { it != HistoryKind.TBR }) {
            assertThat(HistoryFilter.TBR.matches(kind)).isFalse()
            assertThat(row(basal()).copy(kind = kind).removable).isTrue()
        }
    }
}
