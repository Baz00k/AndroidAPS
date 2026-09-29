package app.aaps.ui.activities.history

import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class HistoryReloaderTest {

    private val scheduler = TestScheduler()
    private var clock = 0L
    private val applied = mutableListOf<List<HistoryItem>>()
    private var loads = 0
    private var source: () -> List<HistoryItem> = { emptyList() }

    private val reloader = HistoryReloader(
        load = { loads++; source() }, io = scheduler, main = scheduler, now = { clock },
        onLoaded = { applied += it }, onError = { throw it }
    )

    private fun run(ms: Long) {
        scheduler.advanceTimeBy(ms, TimeUnit.MILLISECONDS)
    }

    private fun item(id: Long, end: Long? = null) = HistoryItem(
        id, 0, "Today", "12:00", HistoryKind.EXTENDED, "Extended bolus", "", "1.00 U",
        removable = end == null, runningUntil = end
    )

    @Test
    fun `a record that ends while History is open triggers one reload after its end`() {
        clock = 10_000
        source = { listOf(item(1, end = 70_000)) }
        reloader.reload()
        run(0)
        assertThat(loads).isEqualTo(1)

        clock = 70_000
        source = { listOf(item(1)) }
        run(59_999)
        assertThat(loads).isEqualTo(1)
        run(1_001)
        assertThat(loads).isEqualTo(2)
        assertThat(applied.last().single().removable).isTrue()

        run(3_600_000)
        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun `nothing running schedules no further reload`() {
        source = { listOf(item(1)) }
        reloader.reload()
        run(3_600_000)
        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun `an older load finishing after a newer one cannot replace it`() {
        var snapshot = listOf(item(1), item(2))
        source = { snapshot }
        reloader.reload()              // starts on io, not yet delivered
        snapshot = listOf(item(2))     // row 1 was removed meanwhile
        reloader.reload()
        run(0)
        assertThat(applied).hasSize(1)
        assertThat(applied.single().map { it.id }).containsExactly(2L)
    }

    @Test
    fun `stop cancels a pending load and the timer so nothing refreshes while paused`() {
        source = { listOf(item(1, end = 5_000)) }
        reloader.reload()
        reloader.stop()
        run(3_600_000)
        assertThat(applied).isEmpty()

        reloader.reload()
        run(0)
        assertThat(applied).hasSize(1)
        reloader.stop()
        run(3_600_000)
        assertThat(loads).isEqualTo(1)   // the cancelled first load never ran
    }

    @Test
    fun `reloading replaces the running timer instead of stacking timers`() {
        clock = 0
        source = { listOf(item(1, end = 10_000)) }
        reloader.reload(); run(0)
        reloader.reload(); run(0)
        clock = 10_000
        source = { listOf(item(1)) }
        val before = loads
        run(11_001)
        assertThat(loads - before).isEqualTo(1)
    }

    @Test
    fun `history item ends drive the next refresh time`() {
        val items = listOf(item(1, end = 9_000), item(2, end = 4_000), item(3))
        assertThat(items.nextRunningEnd()).isEqualTo(4_000L)
        assertThat(listOf(item(3)).nextRunningEnd()).isNull()
    }
}
