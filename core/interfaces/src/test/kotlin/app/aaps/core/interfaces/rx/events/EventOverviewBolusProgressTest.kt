package app.aaps.core.interfaces.rx.events

import app.aaps.core.interfaces.R
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.resources.ResourceHelper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

class EventOverviewBolusProgressTest {
    private val rh = Mockito.mock(ResourceHelper::class.java) { invocation ->
        val text = when (invocation.arguments[0]) {
            R.string.bolus_delivering             -> "Delivering %1\$sU"
            R.string.bolus_delivered_so_far       -> "%1\$sU / %2\$sU delivered"
            R.string.bolus_delivered_successfully -> "Bolus %1\$sU delivered successfully"
            else                                 -> error("Unexpected resource")
        }
        text.format(*invocation.arguments.drop(1).toTypedArray())
    }

    @BeforeEach fun setup() = BolusProgressData.set(0.025, false, 193)
    @AfterEach fun cleanup() = BolusProgressData.set(0.0, false, -1)

    @Test fun `reported partial delivery preserves delivered and requested amounts on phone and wear`() {
        EventOverviewBolusProgress(rh, delivered = 0.0125, id = 193)
        assertThat(BolusProgressData.status.replace(',', '.')).isEqualTo("Delivering 0.0125U")
        assertThat(BolusProgressData.wearStatus.replace(',', '.')).isEqualTo("0.0125U / 0.025U delivered")
        assertThat(BolusProgressData.percent).isEqualTo(50)
        assertThat(BolusProgressData.delivered).isEqualTo(0.0) // Presentation cannot confirm delivery.
        assertThat(BolusProgressData.insulin).isEqualTo(0.025)
    }

    @Test fun `percentage progress and completion retain the request precision`() {
        EventOverviewBolusProgress(rh, percent = 50, id = 193)
        assertThat(BolusProgressData.status.replace(',', '.')).isEqualTo("Delivering 0.0125U")
        EventOverviewBolusProgress(rh, percent = 100, id = 193)
        assertThat(BolusProgressData.status.replace(',', '.')).isEqualTo("Bolus 0.025U delivered successfully")
        assertThat(BolusProgressData.wearStatus).isEqualTo(BolusProgressData.status)
        assertThat(BolusProgressData.percent).isEqualTo(100)
    }

    @Test fun `stale delivery ids cannot overwrite active progress`() {
        EventOverviewBolusProgress(rh, delivered = 1.0, id = 194)
        EventOverviewBolusProgress(rh, percent = 100, id = 194)
        assertThat(BolusProgressData.status).isEmpty()
        assertThat(BolusProgressData.wearStatus).isEmpty()
        assertThat(BolusProgressData.percent).isEqualTo(0)
    }

    @Test fun `reported arithmetic noise and percentage estimates do not become excessive phone or wear precision`() {
        BolusProgressData.set(0.5, false, 193)
        val delivered = 0.1 + 0.1 + 0.1
        BolusProgressData.delivered = delivered
        EventOverviewBolusProgress(rh, delivered = delivered, id = 193)
        assertThat(BolusProgressData.status.replace(',', '.')).isEqualTo("Delivering 0.30U")
        assertThat(BolusProgressData.wearStatus.replace(',', '.')).isEqualTo("0.30U / 0.50U delivered")
        assertThat(BolusProgressData.delivered).isEqualTo(delivered)
        assertThat(BolusProgressData.insulin).isEqualTo(0.5)
        BolusProgressData.set(0.025, false, 193)
        EventOverviewBolusProgress(rh, percent = 11, id = 193)
        assertThat(BolusProgressData.status.replace(',', '.')).isEqualTo("Delivering 0.00275U")
        assertThat(BolusProgressData.wearStatus.replace(',', '.')).isEqualTo("0.00275U / 0.025U delivered")
        assertThat(BolusProgressData.percent).isEqualTo(11)
        assertThat(BolusProgressData.insulin).isEqualTo(0.025)
    }

    @Test fun `decimal display operands leave the raw delivered state and percentage untouched`() {
        val request = 10.0
        val delivered = request - 990 / 100.0
        BolusProgressData.set(request, false, 193)
        BolusProgressData.delivered = delivered
        EventOverviewBolusProgress(rh, delivered, id = 193, deliveredForDisplay = BigDecimal("10.0").subtract(BigDecimal("9.90")))
        assertThat(BolusProgressData.status.replace(',', '.')).isEqualTo("Delivering 0.10U")
        assertThat(BolusProgressData.wearStatus.replace(',', '.')).isEqualTo("0.10U / 10.00U delivered")
        assertThat(BolusProgressData.delivered).isEqualTo(delivered)
        assertThat(BolusProgressData.percent).isEqualTo((delivered / request * 100).toInt())
    }
}
