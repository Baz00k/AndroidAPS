package app.aaps.ui.dialogs.compose

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FillSheetTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun `a picked change keeps its moment and whole seconds past the picker window`() {
        val picked = now - 30 * minute - 400
        val stored = now - 30 * minute - 1000

        assertThat(fillRecordTime(now, picked)).isEqualTo(stored)
        assertThat(fillRecordTime(now + 25 * 60 * minute, picked)).isEqualTo(stored)
    }

    @Test
    fun `a change is recorded now when none was picked and never in the future`() {
        assertThat(fillRecordTime(now, null)).isEqualTo(now)
        assertThat(fillRecordTime(now, now + 5 * minute)).isEqualTo(now)
    }
}
