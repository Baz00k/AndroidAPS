package app.aaps.plugins.aps.openAPSSMB

import androidx.collection.LongSparseArray
import app.aaps.core.data.aps.AverageTDD
import app.aaps.core.data.model.TDD
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.plugins.aps.R
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.math.ln

class OpenAPSSMBPluginTest : TestBaseWithProfile() {

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var determineBasalSMB: DetermineBasalSMB
    @Mock lateinit var bgQualityCheck: BgQualityCheck
    @Mock lateinit var tddCalculator: TddCalculator
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var profiler: Profiler
    @Mock lateinit var pump: Pump
    @Mock lateinit var insulin: Insulin
    private lateinit var openAPSSMBPlugin: OpenAPSSMBPlugin

    private val effectiveProfile: ProfileSealed.EPS
        get() = ProfileSealed.EPS(
            effectiveProfileSwitch.copy(iCfg = effectiveProfileSwitch.iCfg.copy(insulinEndTime = 5 * 60 * 60 * 1000L)),
            activePlugin
        )

    @BeforeEach fun prepare() {
        openAPSSMBPlugin = OpenAPSSMBPlugin(
            aapsLogger, rxBus, constraintChecker, rh, profileFunction, profileUtil, config, activePlugin,
            iobCobCalculator, hardLimits, preferences, dateUtil, processedTbrEbData, persistenceLayer, glucoseStatusProvider,
            tddCalculator, bgQualityCheck, uiInteraction, determineBasalSMB, profiler, GlucoseStatusCalculatorSMB(aapsLogger, iobCobCalculator, dateUtil, decimalFormatter, deltaCalculator), apsResultProvider
        )
    }

    @Test
    fun specialEnableConditionTest() {
        assertThat(openAPSSMBPlugin.specialEnableCondition()).isTrue()
    }

    @Test
    fun specialShowInListConditionTest() {
        assertThat(openAPSSMBPlugin.specialShowInListCondition()).isTrue()
    }

    @Test
    fun preferenceScreenTest() {
        val screen = preferenceManager.createPreferenceScreen(context)
        openAPSSMBPlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }

    @Test
    fun getIsfMgdlFallsBackWhenDynamicIsfOutOfLimits() {
        prepareDynamicIsf()
        stubTdd(totalAmount = 0.01)

        assertThat(openAPSSMBPlugin.getIsfMgdl(effectiveProfile, "test")).isNull()
    }

    @Test
    fun getIsfMgdlReturnsCalculatedValueWithinLimits() {
        prepareDynamicIsf()
        stubTdd(totalAmount = 6.2)

        val expectedTdd = ((1.4 * 6.2 + 0.6 * 6.2) * 3 * 0.33) + (6.2 * 0.34) + (6.2 * 0.33)
        val expected = Round.roundTo(1800 / (expectedTdd * ln(150.0 / 55.0 + 1)), 0.1)

        assertThat(openAPSSMBPlugin.getIsfMgdl(effectiveProfile, "test")).isEqualTo(expected)
    }

    @Test
    fun invokeFallsBackToProfileSensitivityWhenDynamicIsfOutOfLimits() {
        prepareDynamicIsf()
        stubTdd(totalAmount = 0.01)
        val profile = effectiveProfile
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(pump.baseBasalRate).thenReturn(1.0)
        whenever(pump.pumpDescription).thenReturn(PumpDescription())
        whenever(persistenceLayer.getApsResults(any(), any())).thenReturn(emptyList())
        whenever(constraintChecker.isAdvancedFilteringEnabled()).thenReturn(ConstraintObject(false, aapsLogger))
        whenever(constraintChecker.getMaxIOBAllowed()).thenReturn(ConstraintObject(0.0, aapsLogger))
        whenever(constraintChecker.getMaxBasalAllowed(any())).thenReturn(ConstraintObject(10.0, aapsLogger))
        whenever(constraintChecker.isUAMEnabled()).thenReturn(ConstraintObject(false, aapsLogger))
        whenever(constraintChecker.isAutosensModeEnabled()).thenReturn(ConstraintObject(false, aapsLogger))
        whenever(constraintChecker.isSMBModeEnabled(any<Constraint<Boolean>>())).thenAnswer { it.getArgument(0) }
        whenever(iobCobCalculator.calculateIobArrayForSMB(any(), any(), any(), any())).thenReturn(emptyArray())
        whenever(iobCobCalculator.getMealDataWithWaitingForCalculationFinish()).thenReturn(MealData())
        whenever(determineBasalSMB.determine_basal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(RT(algorithm = APSResult.Algorithm.SMB, runningDynamicIsf = false, timestamp = now))

        openAPSSMBPlugin.setPluginEnabledBlocking(PluginType.APS, true)
        openAPSSMBPlugin.invoke("test", false)

        val profileCaptor = argumentCaptor<OapsProfile>()
        val dynIsfModeCaptor = argumentCaptor<Boolean>()
        verify(determineBasalSMB).determine_basal(
            any(), any(), any(), profileCaptor.capture(), any(), any(), any(), any(), any(), dynIsfModeCaptor.capture()
        )
        assertThat(profileCaptor.firstValue.variable_sens).isEqualTo(0.0)
        assertThat(dynIsfModeCaptor.firstValue).isFalse()
    }

    private fun prepareDynamicIsf() {
        whenever(activePlugin.activeAPS).thenReturn(openAPSSMBPlugin)
        whenever(activePlugin.activeInsulin).thenReturn(insulin)
        whenever(insulin.peak).thenReturn(75)
        whenever(preferences.get(BooleanKey.ApsUseDynamicSensitivity)).thenReturn(true)
        whenever(preferences.get(IntKey.ApsDynIsfAdjustmentFactor)).thenReturn(100)
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(GlucoseStatusSMB(glucose = 150.0, date = now))
        whenever(rh.gs(R.string.fallback_to_isf_no_tdd)).thenReturn("Fallback to profile sensitivity. Reason: %s")
        whenever(rh.gs(R.string.fallback_smb_isf_out_of_range)).thenReturn("Fallback to profile sensitivity. Dynamic ISF out of limits.")
    }

    private fun stubTdd(totalAmount: Double) {
        val tdd = TDD(timestamp = now, totalAmount = totalAmount)
        whenever(tddCalculator.calculate(any(), any())).thenReturn(LongSparseArray<TDD>())
        whenever(tddCalculator.calculateDaily(any(), any())).thenReturn(tdd)
        whenever(tddCalculator.averageTDD(anyOrNull())).thenReturn(AverageTDD(tdd, false))
    }
}
