package app.aaps.pump.virtual

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.keys.StringKey
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import org.mockito.kotlin.eq
import org.mockito.kotlin.any

class VirtualPumpPluginTest : TestBaseWithProfile() {

    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var pumpSync: PumpSync
    @Mock lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Mock lateinit var persistenceLayer: PersistenceLayer

    private lateinit var virtualPumpPlugin: VirtualPumpPlugin

    @BeforeEach
    fun prepareMocks() {
        virtualPumpPlugin = VirtualPumpPlugin(
            aapsLogger, rxBus, fabricPrivacy, rh, aapsSchedulers, preferences, profileFunction,
            commandQueue, pumpSync, config, dateUtil, processedDeviceStatusData, persistenceLayer, pumpEnactResultProvider
        )
    }

    @Test
    fun refreshConfiguration() {
        whenever(preferences.get(StringKey.VirtualPumpType)).thenReturn("Accu-Chek Combo")
        virtualPumpPlugin.refreshConfiguration()
        assertThat(virtualPumpPlugin.pumpType).isEqualTo(PumpType.ACCU_CHEK_COMBO)
    }

    @Test
    fun `640G virtual delivery reports and records the exact small request`() {
        whenever(preferences.get(StringKey.VirtualPumpType)).thenReturn("Medtronic 640G")
        whenever(rh.gs(app.aaps.core.ui.R.string.virtualpump_resultok)).thenReturn("OK")
        whenever(rh.gs(app.aaps.core.interfaces.R.string.bolus_delivering)).thenReturn("Delivering %1\$sU")
        whenever(rh.gs(app.aaps.core.interfaces.R.string.bolus_delivered_so_far)).thenReturn("%1\$sU / %2\$sU delivered")
        whenever(rh.gs(app.aaps.core.interfaces.R.string.bolus_delivered_successfully)).thenReturn("Bolus %1\$sU delivered successfully")
        virtualPumpPlugin.refreshConfiguration()
        assertThat(virtualPumpPlugin.pumpDescription.bolusStep).isEqualTo(0.025)
        val info = DetailedBolusInfo().apply { insulin = 0.025 }
        BolusProgressData.set(info.insulin, false, info.id)

        val result = virtualPumpPlugin.deliverTreatment(info)

        assertThat(result.success).isTrue()
        assertThat(result.bolusDelivered).isEqualTo(0.025)
        assertThat(BolusProgressData.status.replace(',', '.')).isEqualTo("Bolus 0.025U delivered successfully")
        assertThat(BolusProgressData.wearStatus).isEqualTo(BolusProgressData.status)
        verify(pumpSync).syncBolusWithPumpId(eq(info.timestamp), eq(0.025), eq(info.bolusType), any(), eq(PumpType.MEDTRONIC_640G), any())
        assertThat(info.insulin).isEqualTo(0.025)
    }

    @AfterEach fun clearProgress() = BolusProgressData.set(0.0, false, -1)

    @Test fun `virtual delivery progress cleans repeated increment noise without changing result or record sync`() {
        whenever(rh.gs(app.aaps.core.ui.R.string.virtualpump_resultok)).thenReturn("OK")
        whenever(rh.gs(app.aaps.core.interfaces.R.string.bolus_delivering)).thenReturn("Delivering %1\$sU")
        whenever(rh.gs(app.aaps.core.interfaces.R.string.bolus_delivered_so_far)).thenReturn("%1\$sU / %2\$sU delivered")
        whenever(rh.gs(app.aaps.core.interfaces.R.string.bolus_delivered_successfully)).thenReturn("Bolus %1\$sU delivered successfully")
        val info = DetailedBolusInfo().apply { insulin = 0.5 }
        BolusProgressData.set(info.insulin, false, info.id)
        val statuses = mutableListOf<String>()
        val observer = rxBus.toObservable(EventOverviewBolusProgress::class.java).subscribe {
            statuses += BolusProgressData.status.replace(',', '.')
        }
        try {
            val result = virtualPumpPlugin.deliverTreatment(info)
            assertThat(statuses).containsExactly(
                "Delivering 0.00U", "Delivering 0.10U", "Delivering 0.20U", "Delivering 0.30U", "Delivering 0.40U",
                "Bolus 0.50U delivered successfully"
            ).inOrder()
            assertThat(result.bolusDelivered).isEqualTo(0.5)
            assertThat(info.insulin).isEqualTo(0.5)
            verify(pumpSync).syncBolusWithPumpId(eq(info.timestamp), eq(0.5), eq(info.bolusType), any(), eq(PumpType.GENERIC_AAPS), any())
        } finally {
            observer.dispose()
        }
    }

    @Test fun `long virtual delivery does not expose accumulated noise on phone or wear`() {
        whenever(rh.gs(app.aaps.core.ui.R.string.virtualpump_resultok)).thenReturn("OK")
        whenever(rh.gs(app.aaps.core.interfaces.R.string.bolus_delivering)).thenReturn("Delivering %1\$sU")
        whenever(rh.gs(app.aaps.core.interfaces.R.string.bolus_delivered_so_far)).thenReturn("%1\$sU / %2\$sU delivered")
        whenever(rh.gs(app.aaps.core.interfaces.R.string.bolus_delivered_successfully)).thenReturn("Bolus %1\$sU delivered successfully")
        val info = DetailedBolusInfo().apply { insulin = 10.1 }
        BolusProgressData.set(info.insulin, false, info.id)
        val labels = mutableListOf<Pair<String, String>>()
        val observer = rxBus.toObservable(EventOverviewBolusProgress::class.java).subscribe {
            labels += BolusProgressData.status to BolusProgressData.wearStatus
        }
        try {
            val result = virtualPumpPlugin.deliverTreatment(info)
            assertThat(labels).contains("Delivering 6.00U" to "6.00U / 10.10U delivered")
            assertThat(labels).contains("Delivering 10.00U" to "10.00U / 10.10U delivered")
            assertThat(result.bolusDelivered).isEqualTo(10.1)
            assertThat(info.insulin).isEqualTo(10.1)
            verify(pumpSync).syncBolusWithPumpId(eq(info.timestamp), eq(10.1), eq(info.bolusType), any(), eq(PumpType.GENERIC_AAPS), any())
        } finally {
            observer.dispose()
        }
    }

    @Test
    fun refreshConfigurationTwice() {
        whenever(preferences.get(StringKey.VirtualPumpType)).thenReturn("Accu-Chek Combo")
        virtualPumpPlugin.refreshConfiguration()
        whenever(preferences.get(StringKey.VirtualPumpType)).thenReturn("Accu-Chek Combo")
        virtualPumpPlugin.refreshConfiguration()
        assertThat(virtualPumpPlugin.pumpType).isEqualTo(PumpType.ACCU_CHEK_COMBO)
    }

    @Test
    fun preferenceScreenTest() {
        val screen = preferenceManager.createPreferenceScreen(context)
        virtualPumpPlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }
}
