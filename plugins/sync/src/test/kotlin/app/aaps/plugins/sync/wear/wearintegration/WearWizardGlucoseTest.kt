package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.iob.CobInfo
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.ui.SensorGlucoseSnapshot
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.core.objects.wizard.QuickWizardEntry
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The watch wizards correct from the newest sensor reading, not from the loop's last five-minute bucket. */
class WearWizardGlucoseTest : TestBaseWithProfile() {

    private val time = 1_700_000_000_000L
    private val minute = 60_000L
    private val persistence: PersistenceLayer = mock()
    private val clock: DateUtil = mock()
    private val loop: Loop = mock()
    private val quickWizard: QuickWizard = mock()
    private val bolusWizard: BolusWizard = mock(defaultAnswer = Answers.RETURNS_SELF)
    private lateinit var handler: DataHandlerMobile

    private fun reading(time: Long, value: Double) = GV(
        timestamp = time, value = value, raw = value, noise = null, trendArrow = TrendArrow.NONE, sourceSensor = SourceSensor.LIBRE_3
    )

    /** Rising fast in one-minute readings; the loop's last bucket held the 181 from three minutes ago. */
    private val risingFast = listOf(170.0, 175.0, 181.0, 188.0, 195.0).mapIndexed { i, v -> reading(time - (5 - i) * minute, v) } +
        reading(time - 15_000L, 202.0)

    @BeforeEach fun prepare() {
        whenever(clock.now()).thenReturn(time)
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
        whenever(profileFunction.getProfile()).thenReturn(validProfile)
        whenever(profileFunction.getProfileName()).thenReturn("Test")
        whenever(loop.runningMode).thenReturn(RM.Mode.CLOSED_LOOP)
        whenever(constraintsChecker.applyCarbsConstraints(any())).thenAnswer { it.arguments[0] as ConstraintObject<*> }
        whenever(iobCobCalculator.getCobInfo(any())).thenReturn(CobInfo(time, 0.0, 0.0))
        whenever(preferences.get(BooleanKey.WearWizardBg)).thenReturn(true)
        // The loop's last five-minute bucket: the value the wizards used to correct from.
        val loopBucket = InMemoryGlucoseValue(time - 3 * minute, 181.0)
        val ads: AutosensDataStore = mock()
        whenever(ads.lastBg()).thenReturn(loopBucket)
        whenever(ads.actualBg()).thenReturn(loopBucket)
        whenever(iobCobCalculator.ads).thenReturn(ads)
        handler = DataHandlerMobile(
            aapsSchedulers = aapsSchedulers, context = context, rxBus = rxBus, aapsLogger = aapsLogger,
            rh = rh, preferences = preferences, config = config, iobCobCalculator = iobCobCalculator,
            processedTbrEbData = processedTbrEbData, glucoseStatusProvider = mock(),
            profileFunction = profileFunction, profileUtil = profileUtil, loop = loop,
            processedDeviceStatusData = mock(), receiverStatusStore = mock(), quickWizard = quickWizard,
            dateUtil = clock, constraintChecker = constraintsChecker, uel = mock(), activePlugin = activePlugin,
            commandQueue = mock(), fabricPrivacy = fabricPrivacy, uiInteraction = mock(),
            persistenceLayer = persistence, importExportPrefs = mock(), decimalFormatter = decimalFormatter,
            bolusWizardProvider = { bolusWizard }, pumpStatusProvider = mock()
        )
    }

    private fun readings(list: List<GV>) =
        whenever(persistence.getBgReadingsDataFromTimeToTime(time - SensorGlucoseSnapshot.LATEST_WINDOW_MS, time, true)).thenReturn(list)

    private fun wizardBg(): Double = argumentCaptor<Double>().run {
        verify(bolusWizard).doCalc(
            any(), any(), anyOrNull(), any(), any(), capture(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any()
        )
        firstValue
    }

    @Test fun `wizard corrects from the newest reading`() {
        readings(risingFast)
        rxBus.send(EventData.ActionWizardPreCheck(carbs = 0, percentage = 100))
        assertEquals(202.0, wizardBg())
    }

    @Test fun `wizard refuses to calculate without a fresh reading`() {
        readings(emptyList())
        whenever(persistence.getLastGlucoseValue()).thenReturn(reading(time - 20 * minute, 150.0))
        whenever(rh.gs(any<Int>())).thenReturn("error")
        val observer = rxBus.toObservable(EventMobileToWear::class.java).test()
        try {
            rxBus.send(EventData.ActionWizardPreCheck(carbs = 0, percentage = 100))
            assertEquals(EventData.ConfirmAction::class.java, observer.values().single().payload::class.java)
            verify(bolusWizard, never()).doCalc(
                any(), any(), anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()
            )
        } finally { observer.dispose() }
    }

    @Test fun `quick wizard corrects from the newest reading`() {
        readings(risingFast)
        val entry: QuickWizardEntry = mock()
        whenever(quickWizard.get("guid")).thenReturn(entry)
        whenever(entry.doCalc(any(), any(), any())).thenReturn(bolusWizard)
        rxBus.send(EventData.ActionQuickWizardPreCheck("guid"))
        val bg = argumentCaptor<GV>()
        verify(entry).doCalc(any(), any(), bg.capture())
        assertEquals(202.0, bg.firstValue.value)
    }
}
