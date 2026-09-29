package app.aaps.ui.activities.history

import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class HistoryReloaderTest {

    private val io = TestScheduler()
    private val main = TestScheduler()
    private var clock = 0L
    private val applied = mutableListOf<List<HistoryItem>>()
    private var loads = 0
    private var source: () -> List<HistoryItem> = { emptyList() }

    private val reloader = HistoryReloader(
        load = { loads++; source() }, io = io, main = main, now = { clock },
        onLoaded = { applied += it }, onError = { throw it }
    )

    private fun ioRuns(ms: Long = 0) = io.advanceTimeBy(ms, TimeUnit.MILLISECONDS)
    private fun mainRuns(ms: Long = 0) = main.advanceTimeBy(ms, TimeUnit.MILLISECONDS)

    /** Advance both schedulers, letting a load run on io and deliver on main, in either order of arrival. */
    private fun advance(ms: Long) {
        repeat(3) { ioRuns(ms / 3); mainRuns(ms / 3) }
        ioRuns(ms % 3); mainRuns(ms % 3)
    }

    private fun item(id: Long, end: Long? = null) = HistoryItem(
        id, 0, "Today", "12:00", HistoryKind.EXTENDED, "Extended bolus", "", "1.00 U",
        removable = end == null, runningUntil = end
    )

    @Test
    fun `a record that ends while History is open triggers one reload after its end`() {
        clock = 10_000
        source = { listOf(item(1, end = 70_000)) }
        reloader.resume(); advance(0)
        assertThat(loads).isEqualTo(1)

        clock = 70_000
        source = { listOf(item(1)) }
        advance(59_999)
        assertThat(loads).isEqualTo(1)
        advance(1_002)
        assertThat(loads).isEqualTo(2)
        assertThat(applied.last().single().removable).isTrue()

        advance(3_600_000)
        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun `nothing running schedules no further reload`() {
        source = { listOf(item(1)) }
        reloader.resume()
        advance(3_600_000)
        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun `an older snapshot already captured cannot replace a newer one`() {
        var snapshot = listOf(item(1), item(2))
        source = { snapshot }
        reloader.resume()
        ioRuns()                        // the old load has read [1, 2] and is waiting to be delivered
        snapshot = listOf(item(2))      // row 1 is removed
        reloader.reload()
        ioRuns()
        mainRuns()
        assertThat(applied).hasSize(1)
        assertThat(applied.single().map { it.id }).containsExactly(2L)
    }

    @Test
    fun `stop cancels a pending load and the timer so nothing refreshes while paused`() {
        source = { listOf(item(1, end = 5_000)) }
        reloader.resume()
        reloader.stop()
        advance(3_600_000)
        assertThat(applied).isEmpty()
        assertThat(loads).isEqualTo(0)

        reloader.resume(); advance(0)
        assertThat(applied).hasSize(1)
        reloader.stop()
        advance(3_600_000)
        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun `a removal finishing while paused does not restart refreshing until resume`() {
        source = { listOf(item(1, end = 5_000)) }
        reloader.resume(); advance(0)
        reloader.cancel()               // removal confirmed
        reloader.stop()                 // screen paused before it finishes
        reloader.reload()               // removal outcome asks for a reload
        advance(3_600_000)
        assertThat(loads).isEqualTo(1)

        reloader.resume(); advance(0)
        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun `a reload after a removal completes while active`() {
        source = { listOf(item(1)) }
        reloader.resume(); advance(0)
        reloader.cancel()
        reloader.reload(); advance(0)
        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun `reloading replaces the running timer so only the latest deadline fires`() {
        clock = 0
        source = { listOf(item(1, end = 10_000)) }
        reloader.resume(); advance(0)
        clock = 5_000
        source = { listOf(item(1, end = 60_000)) }
        reloader.reload(); advance(0)
        assertThat(loads).isEqualTo(2)

        advance(11_001)                 // the superseded 10 s deadline passes
        assertThat(loads).isEqualTo(2)
        clock = 60_000
        source = { listOf(item(1)) }
        advance(55_000 + 1_001)
        assertThat(loads).isEqualTo(3)
    }

    @Test
    fun `earliest running end drives the next refresh time`() {
        val items = listOf(item(1, end = 9_000), item(2, end = 4_000), item(3))
        assertThat(items.nextRunningEnd()).isEqualTo(4_000L)
        assertThat(listOf(item(3)).nextRunningEnd()).isNull()
    }
}
