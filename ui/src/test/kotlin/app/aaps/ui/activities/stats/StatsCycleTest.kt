package app.aaps.ui.activities.stats

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class StatsCycleTest {
    private val newest = LocalDate.of(2026, 10, 13)

    /** Day d back from [newest] (0 = newest) has total 10 + d % 28, so every cycle repeats the same shape. */
    private fun history(days: Int, missing: Set<Int> = emptySet()) =
        (0 until days).filter { it !in missing }.associate { newest.minusDays(it.toLong()) to 10.0 + it % 28 }

    @Test fun `missing days stay on their calendar day and cycles with too few totals are left out`() {
        val pattern = cyclePattern(history(56, missing = setOf(3)), newest, 0)!!
        assertThat(pattern.firstDay).isEqualTo(newest.minusDays(27))
        assertThat(pattern.cycles).hasSize(2)
        // Cycle day 28 is the newest day; day 25 of the newest cycle is the missing one.
        assertThat(pattern.cycles[0][24]).isNull()
        assertThat(pattern.cycles[1][27]).isEqualTo(10.0)
        assertThat(pattern.average[24]).isEqualTo(13.0)
        assertThat(pattern.average[0]).isEqualTo(37.0)
        // A cycle needs 21 of its 28 days, and a pattern needs two cycles.
        assertThat(cyclePattern(history(48), newest, 0)).isNull()
        assertThat(cyclePattern(history(49), newest, 0)!!.cycles).hasSize(2)
    }

    @Test fun `offset moves day 1 earlier and stays within one cycle`() {
        val pattern = cyclePattern(history(CYCLE_HISTORY_DAYS), newest, 5)!!
        assertThat(pattern.firstDay).isEqualTo(newest.minusDays(32))
        assertThat(pattern.cycles).hasSize(6)
        assertThat(pattern.cycles[0][27]).isEqualTo(15.0)
        assertThat(cyclePattern(history(CYCLE_HISTORY_DAYS), newest, 99)!!.firstDay).isEqualTo(newest.minusDays(54))
    }
}
