package app.aaps.ui.dialogs.compose

import app.aaps.core.compose.components.NumericDraft
import app.aaps.core.compose.components.NumericSpec
import app.aaps.core.compose.components.formatNumeric
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusSize
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class NumericDoseRequestTest {
    @Test fun typedRequestAndPumpQuantizationAreSeparateAndBothCanBeDisplayedExactly() {
        val entry = NumericSpec(0.0, 5.0, 0.025, 2)
        var request = 0.0
        NumericDraft("0.013").commit(entry) { request = it }
        assertThat(request).isEqualTo(0.013)
        assertThat(formatNumeric(request, 2, Locale.US)).isEqualTo("0.013")
        val constrained = PumpType.MEDTRONIC_640G.determineCorrectBolusSize(request)
        assertThat(constrained).isEqualTo(0.025)
        assertThat(formatNumeric(constrained, 2, Locale.US)).isEqualTo("0.025")
        assertThat(NumericDraft("0.025").validated(entry)).isEqualTo(0.025)
    }
}
