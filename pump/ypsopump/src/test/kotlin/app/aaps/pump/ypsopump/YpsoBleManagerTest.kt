package app.aaps.pump.ypsopump

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class YpsoBleManagerTest {
    private val context: Context = mock()
    private val sessionCrypto: SessionCrypto = mock()
    private lateinit var pumpState: YpsoPumpState
    private lateinit var manager: YpsoBleManager

    @BeforeEach
    fun setUp() {
        pumpState = YpsoPumpState()
        manager =
            YpsoBleManager(context, AAPSLoggerTest(), sessionCrypto, pumpState).apply {
                scheduleOpTimeout = { _, _ -> }
                cancelOpTimeout = {}
            }
    }

    @Test
    fun `valid callback publishes only after decrypt CRC and decode`() {
        val fixture = connectedGatt()
        whenever(sessionCrypto.decrypt(any())).thenReturn(validStatusPayload())
        val results = mutableListOf<Boolean>()
        pumpState.publishStatus(42.0, 50, false, 100, 1234L)
        assertTrue(pumpState.hasVerifiedStatus)

        manager.readStatus(results::add)

        assertFalse(pumpState.hasVerifiedStatus)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(true), results)
        assertTrue(pumpState.hasVerifiedStatus)
        assertEquals(5.5, pumpState.reservoirUnits)
        assertEquals(85, pumpState.batteryPercent)
        verify(sessionCrypto).decrypt(byteArrayOf(0x55))
    }

    @Test
    fun `invalid CRC fails once without publishing status`() {
        val fixture = connectedGatt()
        whenever(sessionCrypto.decrypt(any())).thenReturn(ByteArray(20))
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
        verify(fixture.gatt).close()
    }

    @Test
    fun `decrypt failure fails once without publishing status`() {
        val fixture = connectedGatt()
        whenever(sessionCrypto.decrypt(any())).thenThrow(SecurityException("authentication failed"))
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
    }

    @Test
    fun `decoded short status fails without publishing`() {
        val fixture = connectedGatt()
        whenever(sessionCrypto.decrypt(any())).thenReturn(YpsoCrc.appendCrc(byteArrayOf(0x01)))
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
    }

    @Test
    fun `empty frame fails once without decrypting`() {
        val fixture = connectedGatt()
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        verify(sessionCrypto, never()).decrypt(any())
    }

    @Test
    fun `cancelled attempt suppresses late valid publication`() {
        val fixture = connectedGatt()
        whenever(sessionCrypto.decrypt(any())).thenReturn(validStatusPayload())
        val results = mutableListOf<Boolean>()
        val attempt = manager.readStatus(results::add)

        assertTrue(attempt.cancel())
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertTrue(results.isEmpty())
        assertFalse(pumpState.hasVerifiedStatus)
    }

    @Test
    fun `timeout fails once and ignores a late callback`() {
        var timeout: Runnable? = null
        manager.scheduleOpTimeout = { runnable, _ -> timeout = runnable }
        val fixture = connectedGatt()
        whenever(sessionCrypto.decrypt(any())).thenReturn(validStatusPayload())
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        timeout!!.run()
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        verify(sessionCrypto, never()).decrypt(any())
    }

    @Test
    fun `disconnect drains a pending read exactly once`() {
        val fixture = connectedGatt()
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.disconnect()
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        verify(fixture.gatt).disconnect()
        verify(fixture.gatt).close()
    }

    @Test
    fun `callback from stale GATT cannot complete owned operation`() {
        val fixture = connectedGatt()
        val staleGatt: BluetoothGatt = mock()
        whenever(sessionCrypto.decrypt(any())).thenReturn(validStatusPayload())
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(staleGatt, fixture.status, byteArrayOf(0x11, 0x44), BluetoothGatt.GATT_SUCCESS)
        assertTrue(results.isEmpty())

        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)
        assertEquals(listOf(true), results)
        verify(staleGatt, never()).disconnect()
        verify(staleGatt, never()).close()
    }

    @Test
    fun `duplicate same UUID frame fails transaction once`() {
        val fixture = connectedGatt()
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x13, 0x01), BluetoothGatt.GATT_SUCCESS)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.extRead, byteArrayOf(0x23, 0x02), BluetoothGatt.GATT_SUCCESS)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.extRead, byteArrayOf(0x23, 0x02), BluetoothGatt.GATT_SUCCESS)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.extRead, byteArrayOf(0x33, 0x03), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        verify(sessionCrypto, never()).decrypt(any())
        verify(fixture.gatt, times(2)).readCharacteristic(fixture.extRead)
    }

    @Test
    fun `changed frame total fails transaction`() {
        val fixture = connectedGatt()
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x12, 0x01), BluetoothGatt.GATT_SUCCESS)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.extRead, byteArrayOf(0x23, 0x02), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
    }

    @Test
    fun `immediate read dispatch failure completes caller once`() {
        val fixture = connectedGatt(readDispatched = false)
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        verify(fixture.gatt).close()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `duplicate discovery and auth callbacks advance handshake only once`() {
        val gatt: BluetoothGatt = mock()
        val service: BluetoothGattService = mock()
        val auth: BluetoothGattCharacteristic = mock()
        whenever(auth.uuid).thenReturn(CHAR_AUTH)
        whenever(service.getCharacteristic(CHAR_AUTH)).thenReturn(auth)
        whenever(gatt.services).thenReturn(listOf(service))
        whenever(gatt.writeCharacteristic(auth)).thenReturn(true)
        ownGatt(gatt, ConnectionState.DISCOVERING)

        manager.gattCallback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        manager.gattCallback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertEquals(ConnectionState.READY, pumpState.connectionState)
        verify(gatt, times(1)).writeCharacteristic(auth)

        manager.gattCallback.onCharacteristicWrite(gatt, auth, BluetoothGatt.GATT_SUCCESS)
        manager.gattCallback.onCharacteristicWrite(gatt, auth, BluetoothGatt.GATT_SUCCESS)

        assertEquals(ConnectionState.CONNECTED, pumpState.connectionState)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy read callback publishes valid status`() {
        val fixture = connectedGatt()
        whenever(sessionCrypto.decrypt(any())).thenReturn(validStatusPayload())
        whenever(fixture.status.value).thenReturn(byteArrayOf(0x11, 0x55))
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(true), results)
        assertTrue(pumpState.hasVerifiedStatus)
    }

    private fun connectedGatt(readDispatched: Boolean = true): GattFixture {
        val gatt: BluetoothGatt = mock()
        val service: BluetoothGattService = mock()
        val status: BluetoothGattCharacteristic = mock()
        val extRead: BluetoothGattCharacteristic = mock()
        whenever(status.uuid).thenReturn(CHAR_STATUS)
        whenever(extRead.uuid).thenReturn(CHAR_EXTREAD)
        whenever(service.getCharacteristic(CHAR_STATUS)).thenReturn(status)
        whenever(service.getCharacteristic(CHAR_EXTREAD)).thenReturn(extRead)
        whenever(gatt.services).thenReturn(listOf(service))
        whenever(gatt.readCharacteristic(status)).thenReturn(readDispatched)
        whenever(gatt.readCharacteristic(extRead)).thenReturn(readDispatched)
        ownGatt(gatt, ConnectionState.CONNECTED)
        return GattFixture(gatt, status, extRead)
    }

    private fun ownGatt(
        gatt: BluetoothGatt,
        state: ConnectionState,
    ) {
        manager.javaClass.getDeclaredField("bluetoothGatt").apply {
            isAccessible = true
            set(manager, gatt)
        }
        pumpState.connectionState = state
    }

    private fun validStatusPayload(): ByteArray {
        val body = ByteArray(18)
        body[1] = 0x26
        body[2] = 0x02
        body[5] = 0x01
        body[6] = 0x55
        body[10] = 0x64
        return YpsoCrc.appendCrc(body)
    }

    private data class GattFixture(
        val gatt: BluetoothGatt,
        val status: BluetoothGattCharacteristic,
        val extRead: BluetoothGattCharacteristic,
    )

    private companion object {
        val CHAR_STATUS: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee48b7bc5")
        val CHAR_EXTREAD: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff000000ff")
        val CHAR_AUTH: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb2147bc5")
    }
}
