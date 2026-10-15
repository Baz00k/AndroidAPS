package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.ui.SensorGlucose
import app.aaps.shared.tests.TestBaseWithProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    private fun reading(time: Long, value: Double, trend: TrendArrow = TrendArrow.NONE) = GV(
        timestamp = time, value = value, raw = value, noise = null,
        trendArrow = trend, sourceSensor = SourceSensor.LIBRE_3
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

    @Test fun `new sensor payload uses raw reading delta and source arrow without accessing loop data`() {
        // Overview's semantics: -15 per five minutes, while the source explicitly reports FLAT.
        whenever(persistence.getBgReadingsDataFromTimeToTime(sensorTime - 10_800_000, sensorTime, true))
            .thenReturn(listOf(reading(sensorTime - 300_000, 166.0), reading(sensorTime, 151.0, TrendArrow.FLAT)))
        val observer = rxBus.toObservable(EventMobileToWear::class.java).test()
        handler.sendGlucose()
        val payload = observer.values().single().payload as EventData.SingleBg
        assertEquals("151", payload.sgvString)
        assertEquals(151.0, payload.sgv)
        assertEquals(sensorTime, payload.timeStamp)
        assertEquals("→", payload.slopeArrow)
        assertEquals("-15", payload.delta)
        assertEquals(-15.0, payload.deltaMgdl)
        assertEquals("--", payload.avgDelta)
        verifyNoInteractions(iobCobCalculator, processedTbrEbData, constraintsChecker)
        observer.dispose()
    }

    @Test fun `mmol payload keeps numeric delta in mgdl and converts text once`() {
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MMOL)
        val glucose = SensorGlucose.from(listOf(reading(sensorTime - 300_000, 166.0), reading(sensorTime, 151.0, TrendArrow.FLAT)), sensorTime)
        val payload = handler.sensorGlucoseToWear(glucose, sensorTime)
        assertEquals(profileUtil.fromMgdlToStringInUnits(151.0), payload.sgvString)
        assertEquals("mmol", payload.glucoseUnits)
        assertEquals("-0.8", payload.delta)
        assertEquals(-15.0, payload.deltaMgdl)
    }

    @Test fun `stale reading retains sensor timestamp but cannot advertise a current trend`() {
        val glucose = SensorGlucose.from(listOf(reading(sensorTime - 300_000, 166.0), reading(sensorTime, 151.0, TrendArrow.FLAT)), sensorTime)
        val payload = handler.sensorGlucoseToWear(glucose, sensorTime + 540_000)
        assertEquals(sensorTime, payload.timeStamp)
        assertEquals("151", payload.sgvString)
        assertEquals("", payload.slopeArrow)
        assertEquals("--", payload.delta)
        assertNull(payload.deltaMgdl)
    }

    @Test fun `zero delta has the same text as Overview and notification`() {
        val glucose = SensorGlucose.from(listOf(reading(sensorTime - 300_000, 151.0), reading(sensorTime, 151.0)), sensorTime)
        assertEquals("0", handler.sensorGlucoseToWear(glucose, sensorTime).delta)
    }

    @Test fun `unavailable history clears the old watch reading instead of leaving it current`() {
        val payload = handler.sensorGlucoseToWear(SensorGlucose(), sensorTime)
        assertEquals(0L, payload.timeStamp)
        assertEquals("--", payload.sgvString)
        assertEquals("", payload.slopeArrow)
        assertEquals("--", payload.delta)
        assertNull(payload.deltaMgdl)
    }

    @Test fun `database failure clears the headline and a later refresh can recover`() {
        whenever(persistence.getBgReadingsDataFromTimeToTime(sensorTime - 10_800_000, sensorTime, true))
            .thenThrow(IllegalStateException("test database unavailable"))
            .thenReturn(listOf(reading(sensorTime, 151.0, TrendArrow.FLAT)))
        val observer = rxBus.toObservable(EventMobileToWear::class.java).test()
        handler.sendGlucose()
        handler.sendGlucose()
        val payloads = observer.values().map { it.payload as EventData.SingleBg }
        assertEquals("--", payloads[0].sgvString)
        assertEquals(0L, payloads[0].timeStamp)
        assertEquals("151", payloads[1].sgvString)
        assertEquals(sensorTime, payloads[1].timeStamp)
        observer.dispose()
    }
}
