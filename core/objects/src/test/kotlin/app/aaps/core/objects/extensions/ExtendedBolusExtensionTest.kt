package app.aaps.core.objects.extensions

import app.aaps.core.data.iob.Iob
import app.aaps.core.data.model.EB
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.profile.Profile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

class ExtendedBolusExtensionTest {

    private val profile: Profile = mock {
        on { dia } doAnswer { 5.0 }
    }
    private val insulin: Insulin = mock {
        on { iobCalcForTreatment(any(), any(), any()) } doAnswer {
            Iob(iobContrib = it.getArgument<app.aaps.core.data.model.BS>(0).amount)
        }
    }

    @Test
    fun `active extended bolus contributes delivered insulin before the first full minute`() {
        val bolus = EB(timestamp = 1_000L, duration = 15 * 60_000L, amount = 0.5)

        val result = bolus.iobCalc(19_000L, profile, insulin)

        assertThat(result.extendedBolusInsulin).isWithin(0.000_001).of(0.01)
        assertThat(result.iob).isWithin(0.000_001).of(0.01)
    }

    @Test
    fun `sub-minute cancelled extended bolus retains its authoritative delivered amount in IOB`() {
        val bolus = EB(timestamp = 1_000L, duration = 18_000L, amount = 0.08)

        val result = bolus.iobCalc(19_000L, profile, insulin)

        assertThat(result.extendedBolusInsulin).isWithin(0.000_001).of(0.08)
        assertThat(result.iob).isWithin(0.000_001).of(0.08)
    }

    @Test
    fun `autosens extended bolus calculation also retains sub-minute delivery`() {
        val bolus = EB(timestamp = 1_000L, duration = 18_000L, amount = 0.08)

        val result = bolus.iobCalc(
            time = 19_000L,
            profile = profile,
            lastAutosensResult = AutosensResult(),
            exerciseMode = false,
            halfBasalExerciseTarget = 160,
            isTempTarget = false,
            insulinInterface = insulin,
        )

        assertThat(result.extendedBolusInsulin).isWithin(0.000_001).of(0.08)
        assertThat(result.iob).isWithin(0.000_001).of(0.08)
    }
}
