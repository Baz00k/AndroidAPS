package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.data.model.EB
import app.aaps.core.data.model.TB
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BasalDetailTest {

    private val minute = 60_000L
    private val now = 1_000 * minute

    private fun detail(scheduled: Double? = 0.8, tb: TB? = null, eb: EB? = null) =
        basalDetail(now, scheduled, tb, eb, { "t${(it - now) / minute}" }, { "%.2f U/h".format(it) })

    @Test
    fun `percent temp basal shows the rate it gives on the scheduled basal`() {
        val tb = TB(timestamp = now - 20 * minute, type = TB.Type.NORMAL, isAbsolute = false, rate = 150.0, duration = 60 * minute)
        assertThat(detail(tb = tb).tempBasal).isEqualTo(BasalDetail.Running("1.20 U/h · 150%", "t-20", "40 of 60 min"))
    }

    @Test
    fun `absolute temp basal shows its percent of the scheduled basal`() {
        val tb = TB(timestamp = now - 10 * minute, type = TB.Type.NORMAL, isAbsolute = true, rate = 0.4, duration = 30 * minute)
        assertThat(detail(tb = tb).tempBasal?.rate).isEqualTo("0.40 U/h · 50%")
    }

    @Test
    fun `without a profile the scheduled rate and a percent temp basal's rate are unknown`() {
        val tb = TB(timestamp = now - 10 * minute, type = TB.Type.NORMAL, isAbsolute = false, rate = 0.0, duration = 30 * minute)
        val result = detail(scheduled = null, tb = tb)
        assertThat(result.scheduled).isNull()
        assertThat(result.tempBasal?.rate).isEqualTo("0%")
    }

    @Test
    fun `ended invalid or future records are not shown as running`() {
        val ended = TB(timestamp = now - 60 * minute, type = TB.Type.NORMAL, isAbsolute = true, rate = 1.0, duration = 60 * minute)
        val invalid = EB(timestamp = now - minute, amount = 1.0, duration = 60 * minute, isValid = false)
        val future = EB(timestamp = now + minute, amount = 1.0, duration = 60 * minute)
        assertThat(detail(tb = ended, eb = invalid)).isEqualTo(BasalDetail("0.80 U/h"))
        assertThat(detail(eb = future).extendedBolus).isNull()
    }

    @Test
    fun `running extended bolus shows its rate and time left`() {
        val eb = EB(timestamp = now - 30 * minute, amount = 2.0, duration = 120 * minute)
        assertThat(detail(eb = eb).extendedBolus).isEqualTo(BasalDetail.Running("1.00 U/h", "t-30", "90 of 120 min"))
    }
}
