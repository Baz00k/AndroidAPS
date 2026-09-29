package app.aaps.implementation.pump

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.IDs
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
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
    fun `history exclusion is strictly before activation and requires both pump identities`() {
        assertTrue(pumpSync.isHistoryRecordBeforeActivePump(1_999L, PumpType.YPSOPUMP, "10000001"))
        assertFalse(pumpSync.isHistoryRecordBeforeActivePump(2_000L, PumpType.YPSOPUMP, "10000001"))
        assertFalse(pumpSync.isHistoryRecordBeforeActivePump(2_001L, PumpType.YPSOPUMP, "10000001"))
        assertFalse(pumpSync.isHistoryRecordBeforeActivePump(1_000L, PumpType.YPSOPUMP, "20000002"))
        whenever(activePump.serialNumber()).thenReturn("20000002")
        assertFalse(pumpSync.isHistoryRecordBeforeActivePump(1_000L, PumpType.YPSOPUMP, "10000001"))
        whenever(activePump.serialNumber()).thenReturn("10000001")
        whenever(preferences.get(StringNonKey.ActivePumpSerialNumber)).thenReturn("20000002")
        assertFalse(pumpSync.isHistoryRecordBeforeActivePump(1_000L, PumpType.YPSOPUMP, "10000001"))
        whenever(preferences.get(StringNonKey.ActivePumpSerialNumber)).thenReturn("")
        whenever(preferences.get(StringNonKey.ActivePumpType)).thenReturn("")
        assertFalse(pumpSync.isHistoryRecordBeforeActivePump(1_000L, PumpType.YPSOPUMP, "10000001"))
        verify(preferences, never()).put(eq(LongNonKey.ActivePumpChangeTimestamp), any<Long>())
        verify(persistenceLayer, never()).syncPumpExtendedBolus(any())
    }

    @Test
    fun `history exclusion requires a known cutoff and matching pump types`() {
        whenever(preferences.get(LongNonKey.ActivePumpChangeTimestamp)).thenReturn(0L)
        assertFalse(pumpSync.isHistoryRecordBeforeActivePump(-1L, PumpType.YPSOPUMP, "10000001"))
        whenever(preferences.get(LongNonKey.ActivePumpChangeTimestamp)).thenReturn(2_000L)
        whenever(activePump.model()).thenReturn(PumpType.USER)
        assertFalse(pumpSync.isHistoryRecordBeforeActivePump(1_000L, PumpType.YPSOPUMP, "10000001"))
        whenever(activePump.model()).thenReturn(PumpType.YPSOPUMP)
        whenever(preferences.get(StringNonKey.ActivePumpType)).thenReturn(PumpType.USER.description)
        assertFalse(pumpSync.isHistoryRecordBeforeActivePump(1_000L, PumpType.YPSOPUMP, "10000001"))
        verify(preferences, never()).put(eq(LongNonKey.ActivePumpChangeTimestamp), any<Long>())
        verify(persistenceLayer, never()).syncPumpExtendedBolus(any())
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

    @Test
    fun `ordinary extended sync rejects a dose older than pump activation`() {
        assertFalse(
            pumpSync.syncExtendedBolusWithPumpId(1_000L, 0.5, 900_000L, false, 101L, PumpType.YPSOPUMP, "10000001")
        )
        verify(persistenceLayer, never()).syncPumpExtendedBolus(any())
    }

    @Test
    fun `correction keeps the original start of a dose that registered the pump`() {
        // Registration stores "now", which is later than the dose's own start, so the plain sync gate
        // would reject every correction and leave the programmed amount standing.
        whenever(persistenceLayer.getExtendedBolusByPumpId(eq(101L), any(), any())).thenReturn(existingExtendedBolus())
        whenever(persistenceLayer.syncPumpExtendedBolus(any()))
            .thenReturn(Single.just(PersistenceLayer.TransactionResult<EB>().apply { updated.add(existingExtendedBolus()) }))

        assertTrue(
            pumpSync.correctExtendedBolusWithPumpId(1_000L, 0.08, 120_000L, false, 101L, PumpType.YPSOPUMP, "10000001")
        )
        verify(persistenceLayer).syncPumpExtendedBolus(any())
    }

    @Test
    fun `correction never creates a record the driver has not already synchronized`() {
        whenever(persistenceLayer.getExtendedBolusByPumpId(eq(101L), any(), any())).thenReturn(null)

        assertFalse(
            pumpSync.correctExtendedBolusWithPumpId(1_000L, 0.08, 120_000L, false, 101L, PumpType.YPSOPUMP, "10000001")
        )
        verify(persistenceLayer, never()).syncPumpExtendedBolus(any())
    }

    @Test
    fun `correction rejects a pump that is not the active one`() {
        whenever(activePump.serialNumber()).thenReturn("20000002")

        assertFalse(
            pumpSync.correctExtendedBolusWithPumpId(1_000L, 0.08, 120_000L, false, 101L, PumpType.YPSOPUMP, "10000001")
        )
        verify(persistenceLayer, never()).syncPumpExtendedBolus(any())
    }

    @Test
    fun `correction rejects a pump identity that is not the registered one`() {
        whenever(preferences.get(StringNonKey.ActivePumpSerialNumber)).thenReturn("30000003")

        assertFalse(
            pumpSync.correctExtendedBolusWithPumpId(1_000L, 0.08, 120_000L, false, 101L, PumpType.YPSOPUMP, "10000001")
        )
        verify(persistenceLayer, never()).syncPumpExtendedBolus(any())
    }

    private fun existingExtendedBolus() = EB(
        timestamp = 1_000L,
        amount = 0.5,
        duration = 900_000L,
        isEmulatingTempBasal = false,
        ids = IDs(pumpId = 101L, pumpType = PumpType.YPSOPUMP, pumpSerial = "10000001"),
    )
}
