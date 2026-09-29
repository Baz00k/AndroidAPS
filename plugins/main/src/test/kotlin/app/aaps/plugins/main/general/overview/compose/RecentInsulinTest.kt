package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.EB
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RecentInsulinTest {

    private val hour = 3_600_000L
    private val now = 24 * hour
    private val windowStart = now - 6 * hour

    private fun entries(boluses: List<BS> = emptyList(), extended: List<EB> = emptyList(), limit: Int = 10) =
        recentInsulinEntries(boluses, extended, windowStart, now, limit, { "t$it" }, { "%.2f U".format(it) })

    @Test
    fun `extended boluses are listed with boluses newest first`() {
        val result = entries(
            boluses = listOf(
                BS(id = 1, timestamp = now - hour, amount = 2.0, type = BS.Type.NORMAL),
                BS(id = 2, timestamp = now - 3 * hour, amount = 0.3, type = BS.Type.SMB)
            ),
            extended = listOf(EB(id = 3, timestamp = now - 2 * hour, amount = 1.5, duration = hour))
        )
        assertThat(result.map { it.id }).containsExactly(1L, 3L, 2L).inOrder()
        val eb = result[1]
        assertThat(eb.type).isEqualTo(HomeUiState.InsulinType.EXTENDED)
        assertThat(eb.kind).isEqualTo("Extended · 60 min")
        assertThat(eb.durationMs).isEqualTo(hour)
        assertThat(eb.amount).isEqualTo(1.5)
        assertThat(eb.removable).isTrue()
        assertThat(result[0].type).isEqualTo(HomeUiState.InsulinType.BOLUS)
        assertThat(result[2].kind).isEqualTo("SMB")
    }

    @Test
    fun `running extended bolus is listed but not removable`() {
        val eb = entries(extended = listOf(EB(id = 3, timestamp = now - hour, amount = 2.0, duration = 2 * hour))).single()
        assertThat(eb.removable).isFalse()
        assertThat(eb.kind).endsWith("running")
    }

    @Test
    fun `extended bolus that started before the window but delivered inside it is listed`() {
        val overlapping = EB(id = 3, timestamp = windowStart - hour, amount = 2.0, duration = 2 * hour)
        val finishedBefore = EB(id = 4, timestamp = windowStart - 3 * hour, amount = 2.0, duration = hour)
        assertThat(entries(extended = listOf(overlapping, finishedBefore)).map { it.id }).containsExactly(3L)
    }

    @Test
    fun `primes invalid zero and future records are excluded`() {
        val result = entries(
            boluses = listOf(
                BS(id = 1, timestamp = now - hour, amount = 1.0, type = BS.Type.PRIMING),
                BS(id = 2, timestamp = now - hour, amount = 1.0, type = BS.Type.NORMAL, isValid = false),
                BS(id = 3, timestamp = now - hour, amount = 0.0, type = BS.Type.NORMAL)
            ),
            extended = listOf(
                EB(id = 4, timestamp = now - hour, amount = 1.0, duration = hour, isValid = false),
                EB(id = 5, timestamp = now - hour, amount = 0.0, duration = hour),
                EB(id = 6, timestamp = now + 1, amount = 1.0, duration = hour)
            )
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `limit applies after merging`() {
        val boluses = (1..10).map { BS(id = it.toLong(), timestamp = now - it * 60_000L, amount = 1.0, type = BS.Type.NORMAL) }
        val eb = EB(id = 99, timestamp = now - 30_000L, amount = 1.0, duration = hour)
        val result = entries(boluses, listOf(eb))
        assertThat(result).hasSize(10)
        assertThat(result.first().id).isEqualTo(99L)
        assertThat(result.map { it.id }).doesNotContain(10L)
    }
}
