package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class SensorGlucoseWearTest : TestBaseWithProfile() {
    private val sensorTime = 1_700_000_000_000L
    private val persistence: PersistenceLayer = mock()
    private val clock: DateUtil = mock()
    private lateinit var handler: DataHandlerMobile

    private fun reading(time: Long, value: Double) = GV(
        timestamp = time, value = value, raw = value, noise = null,
        trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_3
    )

    @BeforeEach fun prepare() {
        whenever(clock.now()).thenReturn(sensorTime)
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
        handler = DataHandlerMobile(
            aapsSchedulers = aapsSchedulers, context = context, rxBus = rxBus, aapsLogger = aapsLogger,
            rh = rh, preferences = preferences, config = config, iobCobCalculator = iobCobCalculator,
            processedTbrEbData = processedTbrEbData, glucoseStatusProvider = mock(),
            profileFunction = profileFunction, profileUtil = profileUtil, loop = mock(),
            processedDeviceStatusData = mock(), receiverStatusStore = mock(), quickWizard = mock(),
            dateUtil = clock, constraintChecker = constraintsChecker, uel = mock(), activePlugin = activePlugin,
            commandQueue = mock(), fabricPrivacy = fabricPrivacy, uiInteraction = mock(),
            persistenceLayer = persistence, importExportPrefs = mock(), decimalFormatter = decimalFormatter,
            bolusWizardProvider = mock(), pumpStatusProvider = mock()
        )
    }

    @Test fun `sensor sends preserve source arrow timestamp and units independently of loop data`() {
        whenever(persistence.getBgReadingsDataFromTimeToTime(sensorTime - 10_800_000, sensorTime, true))
            .thenReturn(listOf(reading(sensorTime - 300_000, 166.0), reading(sensorTime, 151.0)))
        val observer = rxBus.toObservable(EventMobileToWear::class.java).test()
        try {
            for ((units, value, delta) in listOf(Triple(GlucoseUnit.MGDL, "151", "-15"), Triple(GlucoseUnit.MMOL, "8.4", "-0.8"))) {
                whenever(profileFunction.getUnits()).thenReturn(units)
                handler.sendGlucose()
                val sent = observer.values().last().payload as EventData.SingleBg
                assertEquals(value, sent.sgvString)
                assertEquals(sensorTime, sent.timeStamp)
                assertEquals("→", sent.slopeArrow)
                assertEquals(delta, sent.delta)
                assertEquals(-15.0, sent.deltaMgdl)
                assertEquals("--", sent.avgDelta)
            }
            verifyNoInteractions(iobCobCalculator, processedTbrEbData, constraintsChecker)
        } finally { observer.dispose() }
    }

    @Test fun `missing or unreadable glucose sends nothing so the watch does not loop on resend requests`() {
        whenever(persistence.getBgReadingsDataFromTimeToTime(sensorTime - 10_800_000, sensorTime, true))
            .thenThrow(IllegalStateException("test database unavailable"))
            .thenReturn(emptyList())
            .thenReturn(listOf(reading(sensorTime, 151.0)))
        whenever(persistence.getLastGlucoseValue()).thenReturn(null)
        val observer = rxBus.toObservable(EventMobileToWear::class.java).test()
        try {
            handler.sendGlucose()
            handler.sendGlucose()
            observer.assertNoValues()
            handler.sendGlucose()
            val sent = observer.values().map { it.payload as EventData.SingleBg }
            assertEquals(listOf("151"), sent.map { it.sgvString })
            assertEquals(listOf(sensorTime), sent.map { it.timeStamp })
        } finally { observer.dispose() }
    }
}
