package app.aaps.implementation.pump

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.TestBase
import io.reactivex.rxjava3.core.Single
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PumpSyncImplementationTest : TestBase() {

    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var activePlugin: ActivePlugin
    @Mock lateinit var activePump: Pump

    private lateinit var pumpSync: PumpSyncImplementation

    @BeforeEach
    fun setUp() {
        pumpSync = PumpSyncImplementation(
            aapsLogger,
            dateUtil,
            preferences,
            rxBus,
            rh,
            profileFunction,
            persistenceLayer,
            activePlugin,
        )
        whenever(preferences.get(StringNonKey.ActivePumpType)).thenReturn(PumpType.YPSOPUMP.description)
        whenever(preferences.get(StringNonKey.ActivePumpSerialNumber)).thenReturn("10000001")
        whenever(preferences.get(LongNonKey.ActivePumpChangeTimestamp)).thenReturn(2_000L)
        whenever(activePlugin.activePump).thenReturn(activePump)
        whenever(activePump.model()).thenReturn(PumpType.YPSOPUMP)
        whenever(activePump.serialNumber()).thenReturn("10000001")
        whenever(persistenceLayer.syncPumpBolus(any(), any())).thenReturn(Single.just(PersistenceLayer.TransactionResult<BS>()))
    }

    @Test
    fun `ordinary history rejects a bolus older than pump activation`() {
        val result = pumpSync.syncBolusWithPumpIdDetailed(
            1_000L,
            1.0,
            BS.Type.NORMAL,
            101L,
            PumpType.YPSOPUMP,
            "10000001",
        )

        assertEquals(PumpSync.BolusSyncResult.REJECTED, result)
        verify(persistenceLayer, never()).syncPumpBolus(any(), any())
    }

    @Test
    fun `durable replay preserves old timestamp for the same active pump`() {
        val result = pumpSync.replayConfirmedBolusWithPumpIdDetailed(
            1_000L,
            1.0,
            BS.Type.NORMAL,
            101L,
            PumpType.YPSOPUMP,
            "10000001",
        )

        assertEquals(PumpSync.BolusSyncResult.UNCHANGED, result)
        verify(persistenceLayer).syncPumpBolus(any(), any())
    }

    @Test
    fun `durable replay registers an absent identity from the matching active pump`() {
        whenever(preferences.get(StringNonKey.ActivePumpType)).thenReturn("")
        whenever(preferences.get(StringNonKey.ActivePumpSerialNumber)).thenReturn("")
        whenever(dateUtil.now()).thenReturn(3_000L)

        val result = pumpSync.replayConfirmedBolusWithPumpIdDetailed(
            1_000L,
            1.0,
            BS.Type.NORMAL,
            101L,
            PumpType.YPSOPUMP,
            "10000001",
        )

        assertEquals(PumpSync.BolusSyncResult.UNCHANGED, result)
        verify(preferences).put(StringNonKey.ActivePumpType, PumpType.YPSOPUMP.description)
        verify(preferences).put(StringNonKey.ActivePumpSerialNumber, "10000001")
        verify(preferences).put(LongNonKey.ActivePumpChangeTimestamp, 3_000L)
        verify(persistenceLayer).syncPumpBolus(any(), any())
    }

    @Test
    fun `durable replay rejects a different active pump identity`() {
        whenever(activePump.serialNumber()).thenReturn("20000002")

        val result = pumpSync.replayConfirmedBolusWithPumpIdDetailed(
            1_000L,
            1.0,
            BS.Type.NORMAL,
            101L,
            PumpType.YPSOPUMP,
            "10000001",
        )

        assertEquals(PumpSync.BolusSyncResult.REJECTED, result)
        verify(persistenceLayer, never()).syncPumpBolus(any(), any())
    }
}
