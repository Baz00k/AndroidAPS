package app.aaps.ui.dialogs

import app.aaps.ui.dialogs.compose.TempBasalInputs
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TempBasalRequestTest {

    @Test
    fun `a constrained percent is what is sent and is flagged`() {
        val request = tempBasalRequest(TempBasalInputs(isPercent = true, value = 250.0, durationMin = 60), { minOf(it, 200) }, { error("absolute") })

        assertThat(request).isEqualTo(TempBasalRequest(isPercent = true, percent = 200, absolute = 0.0, durationMin = 60, constrained = true))
    }

    @Test
    fun `a constrained absolute rate is what is sent and is flagged`() {
        val request = tempBasalRequest(TempBasalInputs(isPercent = false, value = 3.55, durationMin = 30), { error("percent") }, { minOf(it, 2.0) })

        assertThat(request).isEqualTo(TempBasalRequest(isPercent = false, percent = 0, absolute = 2.0, durationMin = 30, constrained = true))
    }

    @Test
    fun `a zero rate within limits is sent unchanged`() {
        val zeroPercent = tempBasalRequest(TempBasalInputs(isPercent = true, value = 0.0, durationMin = 30), { it }, { it })
        val zeroAbsolute = tempBasalRequest(TempBasalInputs(isPercent = false, value = 0.0, durationMin = 30), { it }, { it })

        assertThat(zeroPercent.percent).isEqualTo(0)
        assertThat(zeroPercent.constrained).isFalse()
        assertThat(zeroAbsolute.absolute).isEqualTo(0.0)
        assertThat(zeroAbsolute.constrained).isFalse()
    }
}
