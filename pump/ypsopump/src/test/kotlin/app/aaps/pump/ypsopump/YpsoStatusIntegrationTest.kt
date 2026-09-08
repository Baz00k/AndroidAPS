package app.aaps.pump.ypsopump

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.pump.PumpEnactResultObject
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.isNull
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.UUID
import javax.inject.Provider

/** Real plugin, manager, framing, CRC, decode and state; mock only Android and AEAD transport. */
class YpsoStatusIntegrationTest {
    @Suppress("DEPRECATION")
    @Test
    fun `authenticated polling preserves counter and idle acquisition across expiry and recovery`() {
        for (sdk in listOf(31, 32, 33)) {
            val context: Context = mock()
            val prefs: SharedPreferences = mock()
            whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
            whenever(prefs.getString(eq(YpsoPumpConst.PREF_SHARED_KEY), isNull())).thenReturn("01".repeat(32))
            whenever(prefs.getString(eq(YpsoPumpConst.PREF_PUMP_MAC), isNull())).thenReturn("12:34:56:78:9A:BC")
            val crypto: SessionCrypto = mock()
            var counter = 591L
            whenever(crypto.writeCounter).thenAnswer { counter }
            doAnswer { counter = it.getArgument(0); null }.whenever(crypto).writeCounter = any()
            // Synthetic running status: reservoir 550 centi-units, 2 battery bars, basal 0.85 U/h.
            // CRC computed independently using a Python bitwise polynomial loop.
            whenever(crypto.decrypt(any())).thenReturn(hex("0a2602000002550000006400000000000000a9d1"))
            var elapsed = 1_000L
            val state = YpsoPumpState().apply { elapsedRealtime = { elapsed }; pumpAddress = "12:34:56:78:9A:BC" }
            val manager = YpsoBleManager(context, AAPSLoggerTest(), crypto, state).apply {
                sdkInt = sdk
                scheduleOpTimeout = { _, _ -> }
                cancelOpTimeout = {}
            }
            val rh: ResourceHelper = mock()
            whenever(rh.gs(any())).thenReturn("localized")
            whenever(rh.gs(any(), anyVararg())).thenReturn("localized")
            val preferences: Preferences = mock()
            whenever(preferences.get(IntKey.OverviewResCritical)).thenReturn(10)
            val sync: PumpSync = mock()
            val ui: UiInteraction = mock()
            val plugin = YpsoPumpPlugin(AAPSLoggerTest(), rh, preferences, mock(), state, manager, sync, mock(), mock(), mock(), ui,
                                        Provider { PumpEnactResultObject(rh) })
            val writes = mutableListOf<Pair<UUID, List<Byte>>>()
            var readSucceeds = true
            fun authenticate() {
                val gatt: BluetoothGatt = mock()
                val service: BluetoothGattService = mock()
                val auth: BluetoothGattCharacteristic = mock()
                val status: BluetoothGattCharacteristic = mock()
                val authUuid = UUID.fromString("669a0c20-0008-969e-e211-fcbeb2147bc5")
                val statusUuid = UUID.fromString("669a0c20-0008-969e-e211-fcbee48b7bc5")
                whenever(auth.uuid).thenReturn(authUuid)
                whenever(status.uuid).thenReturn(statusUuid)
                whenever(service.getCharacteristic(authUuid)).thenReturn(auth)
                whenever(service.getCharacteristic(statusUuid)).thenReturn(status)
                whenever(gatt.services).thenReturn(listOf(service))
                for (suffix in listOf("fcbeb0147bc5", "fcbeb1147bc5")) {
                    val uuid = UUID.fromString("669a0c20-0008-969e-e211-$suffix")
                    val version: BluetoothGattCharacteristic = mock()
                    whenever(version.uuid).thenReturn(uuid)
                    whenever(service.getCharacteristic(uuid)).thenReturn(version)
                    whenever(gatt.readCharacteristic(version)).thenAnswer {
                        manager.gattCallback.onCharacteristicRead(gatt, version, "V05.00.52\u0000".toByteArray(), 0)
                        true
                    }
                }
                var legacy = byteArrayOf()
                whenever(auth.setValue(any<ByteArray>())).thenAnswer { legacy = it.getArgument<ByteArray>(0).copyOf(); true }
                whenever(gatt.writeCharacteristic(any())).thenAnswer { writes.add(authUuid to legacy.toList()); true }
                whenever(gatt.writeCharacteristic(any(), any(), any())).thenAnswer {
                    writes.add(authUuid to it.getArgument<ByteArray>(1).toList()); 0
                }
                whenever(gatt.writeDescriptor(any())).thenAnswer { error("Unexpected descriptor dispatch") }
                whenever(gatt.writeDescriptor(any(), any())).thenAnswer { error("Unexpected descriptor dispatch") }
                whenever(gatt.readCharacteristic(status)).thenAnswer {
                    if (readSucceeds) manager.gattCallback.onCharacteristicRead(gatt, status, byteArrayOf(0x11, 0x55), 0)
                    readSucceeds
                }
                manager.javaClass.getDeclaredField("bluetoothGatt").apply { isAccessible = true; set(manager, gatt) }
                state.connectionState = YpsoBleManager.ConnectionState.DISCOVERING
                manager.gattCallback.onServicesDiscovered(gatt, 0)
                manager.gattCallback.onCharacteristicWrite(gatt, auth, 0)
            }
            authenticate()
            plugin.getPumpStatus("poll")
            assertEquals(5.5, plugin.reservoirLevel)
            assertEquals(40, plugin.batteryLevel)
            val acquired = plugin.lastDataTime
            assertTrue(acquired > 0)
            val date: DateUtil = mock()
            whenever(date.minOrSecAgo(eq(rh), any())).thenReturn("reading age")
            fun display() = buildPumpStatusState(state, mock(), date, rh)
            assertEquals(5.5, display().reservoir)
            assertNull(display().battery)
            assertEquals(2, display().batteryBars)
            plugin.disconnect("Queue empty")
            elapsed += 299_999
            assertEquals(acquired, plugin.lastDataTime)
            assertEquals(5.5, plugin.reservoirLevel)
            assertEquals(5.5, display().reservoir)
            assertEquals(false, display().connectionHealthy) // Link disconnected, sample remains current.
            elapsed++
            assertEquals(0L, plugin.lastDataTime)
            assertTrue(plugin.reservoirLevel.isNaN())
            assertNull(plugin.batteryLevel)
            assertNull(display().reservoir)
            assertNull(display().battery)
            authenticate()
            assertEquals(0L, plugin.lastDataTime)
            plugin.getPumpStatus("recovery")
            assertEquals(5.5, plugin.reservoirLevel)
            readSucceeds = false
            plugin.getPumpStatus("failed poll")
            assertTrue(plugin.reservoirLevel.isNaN())
            assertEquals(0L, plugin.lastDataTime)
            verify(ui, times(2)).addNotification(eq(Notification.PUMP_RESERVOIR_LOW), any(), eq(Notification.URGENT))
            verifyNoInteractions(sync)
            assertEquals(591L, manager.writeCounter)
            val expectedAuth = UUID.fromString("669a0c20-0008-969e-e211-fcbeb2147bc5") to hex("04319d09e5ba61be2acf95ebebffe38a").toList()
            assertEquals(listOf(expectedAuth, expectedAuth), writes)
        }
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
