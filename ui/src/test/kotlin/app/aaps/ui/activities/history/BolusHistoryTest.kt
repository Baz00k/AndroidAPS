package app.aaps.ui.activities.history

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.resources.ResourceHelper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class BolusHistoryTest {
    private val rh = Mockito.mock(ResourceHelper::class.java) { invocation ->
        "%1\$s U".format(*invocation.arguments.drop(1).toTypedArray())
    }

    @Test fun `manual and SMB history use the persisted amount without current pump rounding`() {
        for (type in listOf(BS.Type.NORMAL, BS.Type.SMB)) {
            val bolus = BS(id = 193, timestamp = 1000, amount = 0.025, type = type, notes = "recorded")
            val item = bolus.toHistoryItem("Today", "12:30", rh)!!
            assertThat(item.value.replace(',', '.')).isEqualTo("0.025 U")
            assertThat(item.kind).isEqualTo(if (type == BS.Type.SMB) HistoryKind.SMB else HistoryKind.BOLUS)
            assertThat(item.id).isEqualTo(193)
            assertThat(item.sub).isEqualTo("recorded")
            assertThat(bolus.amount).isEqualTo(0.025)
        }
    }

    @Test fun `invalid and priming doses remain excluded from history`() {
        assertThat(BS(timestamp = 1000, amount = 0.025, type = BS.Type.NORMAL, isValid = false).toHistoryItem("Today", "12:30", rh)).isNull()
        assertThat(BS(timestamp = 1000, amount = 0.025, type = BS.Type.PRIMING).toHistoryItem("Today", "12:30", rh)).isNull()
    }
}
