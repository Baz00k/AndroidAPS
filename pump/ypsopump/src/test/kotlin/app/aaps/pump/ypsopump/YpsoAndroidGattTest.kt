package app.aaps.pump.ypsopump

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import app.aaps.pump.ypsopump.ble.YpsoAndroidGattAdapter
import app.aaps.pump.ypsopump.ble.YpsoAndroidGattConnection
import app.aaps.pump.ypsopump.ble.YpsoBondException
import app.aaps.pump.ypsopump.ble.YpsoGattConnection
import app.aaps.pump.ypsopump.ble.YpsoGattEvents
import app.aaps.pump.ypsopump.ble.YpsoGattLayout
import app.aaps.pump.ypsopump.ble.YpsoRemoteWrite
import app.aaps.pump.ypsopump.ble.YpsoWriteAuthorizer
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

/** The Android side of the GATT port: API 33 writes, the authorizer gate, service scoping, callback forwarding, bonding. */
@Suppress("DEPRECATION")
class YpsoAndroidGattTest {

    private val auth = YpsoGattLayout.CHAR_AUTH
    private val cccd = YpsoWritePolicy.CCCD_UUID
    private val genericService = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    private val payload = byteArrayOf(0x04, 0x31, 0x9d.toByte(), 0x09)

    private data class AuthorizerCall(
        val connection: YpsoGattConnection, val purpose: YpsoRemoteWrite, val characteristic: UUID?, val descriptor: UUID?, val value: List<Byte>,
    )

    private class RecordingAuthorizer(private val allow: Boolean) : YpsoWriteAuthorizer {
        val calls = mutableListOf<AuthorizerCall>()
        override fun allows(connection: YpsoGattConnection, purpose: YpsoRemoteWrite, characteristic: UUID?, descriptor: UUID?, value: ByteArray): Boolean {
            calls += AuthorizerCall(connection, purpose, characteristic, descriptor, value.toList())
            return allow
        }
    }

    /** Stores what each callback delivered at the moment it was delivered, so later mutation of a source array is visible. */
    private class RecordingEvents : YpsoGattEvents {
        val events = mutableListOf<List<Any?>>()
        override fun onConnectionStateChange(connection: YpsoGattConnection, connected: Boolean, status: Int) {
            events += listOf("state", connection, connected, status)
        }
        override fun onServicesDiscovered(connection: YpsoGattConnection, status: Int) {
            events += listOf("services", connection, status)
        }
        override fun onCharacteristicWrite(connection: YpsoGattConnection, uuid: UUID, status: Int) {
            events += listOf("write", connection, uuid, status)
        }
        override fun onCharacteristicRead(connection: YpsoGattConnection, uuid: UUID, value: ByteArray?, status: Int) {
            events += listOf("read", connection, uuid, value, value?.toList(), status)
        }
        override fun onDescriptorWrite(connection: YpsoGattConnection, uuid: UUID, status: Int) {
            events += listOf("descriptor", connection, uuid, status)
        }
        override fun onCharacteristicChanged(connection: YpsoGattConnection, uuid: UUID, value: ByteArray, receivedAtNanos: Long) {
            events += listOf("changed", connection, uuid, value.toList(), receivedAtNanos)
        }
    }

    private class Fixture(val gatt: BluetoothGatt, val characteristic: BluetoothGattCharacteristic, val descriptor: BluetoothGattDescriptor)

    /** One AUTH characteristic with a CCCD, in a service that is not the control service (AUTH is not service-scoped). */
    private fun authGatt(): Fixture {
        val gatt: BluetoothGatt = mock()
        val service: BluetoothGattService = mock()
        val characteristic: BluetoothGattCharacteristic = mock()
        val descriptor: BluetoothGattDescriptor = mock()
        whenever(characteristic.uuid).thenReturn(auth)
        whenever(characteristic.getDescriptor(cccd)).thenReturn(descriptor)
        whenever(descriptor.uuid).thenReturn(cccd)
        whenever(descriptor.characteristic).thenReturn(characteristic)
        whenever(service.uuid).thenReturn(genericService)
        whenever(service.getCharacteristic(auth)).thenReturn(characteristic)
        whenever(gatt.services).thenReturn(listOf(service))
        return Fixture(gatt, characteristic, descriptor)
    }

    private fun connection(gatt: BluetoothGatt, authorizer: YpsoWriteAuthorizer = RecordingAuthorizer(true), events: YpsoGattEvents = RecordingEvents()) =
        YpsoAndroidGattConnection(events, authorizer).also { it.attach(gatt) }

    @Test
    fun `characteristic write delivers the destination and bytes through the API 33 call`() {
        val fixture = authGatt()
        val delivered = mutableListOf<Pair<UUID, List<Byte>>>()
        whenever(fixture.gatt.writeCharacteristic(any(), any(), any())).thenAnswer {
            delivered += it.getArgument<BluetoothGattCharacteristic>(0).uuid to it.getArgument<ByteArray>(1).toList()
            BluetoothStatusCodes.SUCCESS
        }

        assertTrue(connection(fixture.gatt).writeCharacteristic(auth, payload, YpsoRemoteWrite.AUTHENTICATION))

        assertEquals(listOf(auth to payload.toList()), delivered)
        verify(fixture.gatt).writeCharacteristic(same(fixture.characteristic), any(), eq(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
        verify(fixture.gatt, never()).writeCharacteristic(any())
        verify(fixture.characteristic, never()).setValue(any<ByteArray>())
    }

    @Test
    fun `platform refusal of a characteristic write is reported as not sent`() {
        val fixture = authGatt()
        whenever(fixture.gatt.writeCharacteristic(any(), any(), any())).thenReturn(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY)
        assertFalse(connection(fixture.gatt).writeCharacteristic(auth, payload, YpsoRemoteWrite.AUTHENTICATION))
    }

    @Test
    fun `authorizer sees the write and its refusal keeps every byte away from Android`() {
        for (purpose in YpsoRemoteWrite.entries) {
            val fixture = authGatt()
            val authorizer = RecordingAuthorizer(false)
            val connection = connection(fixture.gatt, authorizer)

            assertFalse(connection.writeCharacteristic(auth, payload, purpose), "$purpose")
            assertFalse(connection.writeDescriptor(auth, cccd, byteArrayOf(1, 0), purpose), "$purpose")

            assertEquals(
                listOf(
                    AuthorizerCall(connection, purpose, auth, null, payload.toList()),
                    AuthorizerCall(connection, purpose, auth, cccd, listOf<Byte>(1, 0)),
                ),
                authorizer.calls,
                "$purpose",
            )
            authorizer.calls.forEach { assertSame(connection, it.connection) }
            verify(fixture.gatt, never()).writeCharacteristic(any())
            verify(fixture.gatt, never()).writeCharacteristic(any(), any(), any())
            verify(fixture.gatt, never()).writeDescriptor(any())
            verify(fixture.gatt, never()).writeDescriptor(any(), any())
            verify(fixture.characteristic, never()).setValue(any<ByteArray>())
            verify(fixture.descriptor, never()).setValue(any())
        }
    }

    @Test
    fun `admitted descriptor write reaches Android through the API 33 call`() {
        val enable = byteArrayOf(1, 0)
        val fixture = authGatt()
        val authorizer = RecordingAuthorizer(true)
        whenever(fixture.gatt.writeDescriptor(any(), any())).thenReturn(BluetoothStatusCodes.SUCCESS)

        assertTrue(connection(fixture.gatt, authorizer).writeDescriptor(auth, cccd, enable, YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR))

        verify(fixture.gatt).writeDescriptor(same(fixture.descriptor), eq(enable))
        verify(fixture.gatt, never()).writeDescriptor(any())
        verify(fixture.descriptor, never()).setValue(any())
        assertEquals(listOf(YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR to cccd), authorizer.calls.map { it.purpose to it.descriptor })
    }

    @Test
    fun `platform refusal of a descriptor write is reported as not sent`() {
        val fixture = authGatt()
        whenever(fixture.gatt.writeDescriptor(any(), any())).thenReturn(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY)
        assertFalse(connection(fixture.gatt).writeDescriptor(auth, cccd, byteArrayOf(1, 0), YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR))
    }

    @Test
    fun `characteristic dispatch sends the private bytes authorized before caller mutation`() {
        val fixture = authGatt()
        val source = byteArrayOf(4, 5, 6)
        lateinit var authorized: ByteArray
        val authorizer = YpsoWriteAuthorizer { _, _, _, _, bytes ->
            assertNotSame(source, bytes)
            assertEquals(listOf<Byte>(4, 5, 6), bytes.toList())
            authorized = bytes
            source.fill(9)
            true
        }
        whenever(fixture.gatt.writeCharacteristic(any(), any(), any())).thenAnswer {
            val delivered = it.getArgument<ByteArray>(1)
            assertSame(authorized, delivered)
            assertEquals(listOf<Byte>(4, 5, 6), delivered.toList())
            BluetoothStatusCodes.SUCCESS
        }

        assertTrue(connection(fixture.gatt, authorizer).writeCharacteristic(auth, source, YpsoRemoteWrite.AUTHENTICATION))
        verify(fixture.gatt).writeCharacteristic(same(fixture.characteristic), same(authorized), eq(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
    }

    @Test
    fun `descriptor dispatch sends the private bytes authorized before caller mutation`() {
        val fixture = authGatt()
        val source = byteArrayOf(1, 0)
        lateinit var authorized: ByteArray
        val authorizer = YpsoWriteAuthorizer { _, _, _, _, bytes ->
            assertNotSame(source, bytes)
            assertEquals(listOf<Byte>(1, 0), bytes.toList())
            authorized = bytes
            source.fill(9)
            true
        }
        whenever(fixture.gatt.writeDescriptor(any(), any())).thenAnswer {
            val delivered = it.getArgument<ByteArray>(1)
            assertSame(authorized, delivered)
            assertEquals(listOf<Byte>(1, 0), delivered.toList())
            BluetoothStatusCodes.SUCCESS
        }

        assertTrue(connection(fixture.gatt, authorizer).writeDescriptor(auth, cccd, source, YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR))
        verify(fixture.gatt).writeDescriptor(same(fixture.descriptor), same(authorized))
    }

    @Test
    fun `service scoped characteristics are found only in their own service while others are found anywhere`() {
        val gatt: BluetoothGatt = mock()
        val control: BluetoothGattService = mock()
        val extRead: BluetoothGattService = mock()
        val other: BluetoothGattService = mock()
        val decoy = mutableMapOf<UUID, BluetoothGattCharacteristic>()
        for (uuid in listOf(YpsoGattLayout.CHAR_STATUS, YpsoGattLayout.CHAR_BOLUS_STATUS, YpsoGattLayout.CHAR_CONTROL_VERSION, YpsoGattLayout.CHAR_EXTREAD)) {
            val characteristic: BluetoothGattCharacteristic = mock()
            whenever(characteristic.uuid).thenReturn(uuid)
            whenever(characteristic.properties).thenReturn(BluetoothGattCharacteristic.PROPERTY_READ)
            whenever(other.getCharacteristic(uuid)).thenReturn(characteristic)
            whenever(gatt.readCharacteristic(characteristic)).thenReturn(true)
            decoy[uuid] = characteristic
        }
        // EXTREAD placed in the control service and control characteristics in the ext-read service: still the wrong service.
        whenever(control.getCharacteristic(YpsoGattLayout.CHAR_EXTREAD)).thenReturn(decoy[YpsoGattLayout.CHAR_EXTREAD])
        whenever(extRead.getCharacteristic(YpsoGattLayout.CHAR_STATUS)).thenReturn(decoy[YpsoGattLayout.CHAR_STATUS])
        val unscoped: BluetoothGattCharacteristic = mock()
        whenever(unscoped.uuid).thenReturn(YpsoGattLayout.CHAR_SYSTEM_DATE)
        whenever(unscoped.properties).thenReturn(BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE)
        whenever(other.getCharacteristic(YpsoGattLayout.CHAR_SYSTEM_DATE)).thenReturn(unscoped)
        whenever(gatt.readCharacteristic(unscoped)).thenReturn(true)
        whenever(gatt.getService(YpsoGattLayout.SERVICE_CONTROL)).thenReturn(control)
        whenever(gatt.getService(YpsoGattLayout.SERVICE_EXTREAD)).thenReturn(extRead)
        whenever(gatt.services).thenReturn(listOf(other, control, extRead))
        val connection = connection(gatt)

        for (uuid in decoy.keys) {
            assertNull(connection.properties(uuid), "$uuid")
            assertFalse(connection.read(uuid), "$uuid")
        }
        decoy.values.forEach { verify(gatt, never()).readCharacteristic(it) }
        assertEquals(BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE, connection.properties(YpsoGattLayout.CHAR_SYSTEM_DATE))
        assertTrue(connection.read(YpsoGattLayout.CHAR_SYSTEM_DATE))

        // The same characteristics in their own services are found.
        val status: BluetoothGattCharacteristic = mock()
        val ext: BluetoothGattCharacteristic = mock()
        whenever(status.properties).thenReturn(BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        whenever(ext.properties).thenReturn(BluetoothGattCharacteristic.PROPERTY_READ)
        whenever(control.getCharacteristic(YpsoGattLayout.CHAR_STATUS)).thenReturn(status)
        whenever(extRead.getCharacteristic(YpsoGattLayout.CHAR_EXTREAD)).thenReturn(ext)
        whenever(gatt.readCharacteristic(ext)).thenReturn(true)
        assertEquals(BluetoothGattCharacteristic.PROPERTY_NOTIFY, connection.properties(YpsoGattLayout.CHAR_STATUS))
        assertTrue(connection.read(YpsoGattLayout.CHAR_EXTREAD))
        verify(gatt).readCharacteristic(ext)
    }

    @Test
    fun `only connected and disconnected states are forwarded and each names this connection`() {
        val gatt: BluetoothGatt = mock()
        val events = RecordingEvents()
        val connection = connection(gatt, events = events)

        connection.callback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTING)
        connection.callback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
        connection.callback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_DISCONNECTING)
        connection.callback.onConnectionStateChange(gatt, 8, BluetoothProfile.STATE_DISCONNECTED)

        assertEquals(listOf(listOf("state", connection, true, 0), listOf("state", connection, false, 8)), events.events)
        events.events.forEach { assertSame(connection, it[1]) }
    }

    @Test
    fun `API 33 read callback forwards a copy of the bytes and the platform status`() {
        val gatt: BluetoothGatt = mock()
        val characteristic: BluetoothGattCharacteristic = mock()
        whenever(characteristic.uuid).thenReturn(YpsoGattLayout.CHAR_STATUS)
        val events = RecordingEvents()
        val connection = connection(gatt, events = events)

        val platform = byteArrayOf(1, 2, 3)
        connection.callback.onCharacteristicRead(gatt, characteristic, platform, BluetoothGatt.GATT_SUCCESS)
        platform.fill(9)
        connection.callback.onCharacteristicRead(gatt, characteristic, byteArrayOf(), BluetoothGatt.GATT_FAILURE)

        assertEquals(2, events.events.size)
        val (read, failed) = events.events
        assertSame(connection, read[1])
        assertEquals(YpsoGattLayout.CHAR_STATUS, read[2])
        assertEquals(listOf<Byte>(1, 2, 3), (read[3] as ByteArray).toList())
        assertEquals(BluetoothGatt.GATT_SUCCESS, read[5])
        assertSame(connection, failed[1])
        assertEquals(YpsoGattLayout.CHAR_STATUS, failed[2])
        assertEquals(emptyList<Byte>(), (failed[3] as ByteArray).toList())
        assertEquals(BluetoothGatt.GATT_FAILURE, failed[5])
    }

    @Test
    fun `write descriptor services and change callbacks forward uuid status value and receipt time`() {
        val gatt: BluetoothGatt = mock()
        val fixture = authGatt()
        val events = RecordingEvents()
        val connection = connection(gatt, events = events)

        connection.callback.onServicesDiscovered(gatt, 129)
        connection.callback.onCharacteristicWrite(gatt, fixture.characteristic, 3)
        connection.callback.onDescriptorWrite(gatt, fixture.descriptor, 0)
        val before = System.nanoTime()
        connection.callback.onCharacteristicChanged(gatt, fixture.characteristic, byteArrayOf(7, 8))
        val after = System.nanoTime()

        assertEquals(
            listOf(
                listOf("services", connection, 129),
                listOf("write", connection, auth, 3),
                listOf("descriptor", connection, cccd, 0),
            ),
            events.events.take(3),
        )
        val changed = events.events[3]
        assertEquals(listOf("changed", connection, auth, listOf<Byte>(7, 8)), changed.take(4))
        val receivedAt = changed[4] as Long
        assertTrue(receivedAt in before..after, "receipt time $receivedAt outside [$before, $after]")
        events.events.forEach { assertSame(connection, it[1]) }
    }

    private class Platform(val context: Context, val adapter: BluetoothAdapter, val device: BluetoothDevice)

    private fun platform(mac: String = "EC:2A:F0:00:00:01", name: String? = "mylife YpsoPump 000001"): Platform {
        val context: Context = mock()
        val manager: BluetoothManager = mock()
        val adapter: BluetoothAdapter = mock()
        val device: BluetoothDevice = mock()
        whenever(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(manager)
        whenever(manager.adapter).thenReturn(adapter)
        whenever(adapter.isEnabled).thenReturn(true)
        whenever(adapter.getRemoteDevice(mac)).thenReturn(device)
        whenever(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        whenever(device.name).thenReturn(name)
        return Platform(context, adapter, device)
    }

    @Test
    fun `missing Bluetooth bond or permission refuses the pump before any connection`() {
        val mac = "EC:2A:F0:00:00:01"
        fun refusal(platform: Platform): Throwable {
            val thrown = assertThrows(Exception::class.java) { YpsoAndroidGattAdapter(platform.context).bondedPump(mac, "10000001") }
            verify(platform.device, never()).connectGatt(any(), any(), any(), any())
            return thrown
        }

        val noService = platform().also { whenever(it.context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(null) }
        val noAdapter = platform().also { whenever(it.context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(mock<BluetoothManager>()) }
        val off = platform().also { whenever(it.adapter.isEnabled).thenReturn(false) }
        val unbonded = platform().also { whenever(it.device.bondState).thenReturn(BluetoothDevice.BOND_NONE) }
        val bonding = platform().also { whenever(it.device.bondState).thenReturn(BluetoothDevice.BOND_BONDING) }
        for (platform in listOf(noService, noAdapter, off, unbonded, bonding)) {
            val thrown = refusal(platform)
            assertTrue(thrown is YpsoBondException, "$thrown")
            assertFalse((thrown as YpsoBondException).identityMismatch)
        }

        // The link maps any non-identity failure to BOND_OR_PERMISSION; the adapter lets the platform exception through.
        val denied = platform().also { doThrow(SecurityException("BLUETOOTH_CONNECT")).whenever(it.device).bondState }
        assertTrue(refusal(denied) is SecurityException)
    }

    @Test
    fun `bonded name confirms or contradicts the configured serial and unknown aliases defer`() {
        val mac = "EC:2A:F0:00:00:01"
        val other = platform(name = "mylife YpsoPump 000002")
        val mismatch = assertThrows(YpsoBondException::class.java) { YpsoAndroidGattAdapter(other.context).bondedPump(mac, "10000001") }
        assertTrue(mismatch.identityMismatch)
        verify(other.device, never()).connectGatt(any(), any(), any(), any())

        for (name in listOf("mylife YpsoPump 000001", "YpsoPump_10000001")) {
            assertEquals("10000001", YpsoAndroidGattAdapter(platform(name = name).context).bondedPump(mac, "10000001").observedSerial, name)
        }
        for (name in listOf("Kitchen pump", null)) {
            assertNull(YpsoAndroidGattAdapter(platform(name = name).context).bondedPump(mac, "10000001").observedSerial, "$name")
        }
        // Without a configured serial a recognized name confirms nothing.
        assertNull(YpsoAndroidGattAdapter(platform().context).bondedPump(mac, null).observedSerial)
    }

    @Test
    fun `connect hands this connection's callback to an LE GATT client and returns null when Android refuses`() {
        val mac = "EC:2A:F0:00:00:01"
        val refused = platform()
        whenever(refused.device.connectGatt(any(), any(), any(), any())).thenReturn(null)
        assertNull(YpsoAndroidGattAdapter(refused.context).bondedPump(mac, "10000001").connect(RecordingEvents(), RecordingAuthorizer(true)))

        val accepted = platform()
        val gatt: BluetoothGatt = mock()
        whenever(gatt.discoverServices()).thenReturn(true)
        whenever(accepted.device.connectGatt(any(), any(), any(), any())).thenReturn(gatt)
        val events = RecordingEvents()
        val connection = YpsoAndroidGattAdapter(accepted.context).bondedPump(mac, "10000001").connect(events, RecordingAuthorizer(true))

        assertNotNull(connection)
        connection as YpsoAndroidGattConnection
        verify(accepted.device).connectGatt(same(accepted.context), eq(false), same(connection.callback), eq(BluetoothDevice.TRANSPORT_LE))
        assertTrue(connection.discoverServices())
        verify(gatt).discoverServices()
        connection.callback.onServicesDiscovered(gatt, 0)
        assertEquals(listOf(listOf<Any?>("services", connection, 0)), events.events)
        verify(gatt, never()).writeCharacteristic(any())
        verify(gatt, never()).writeCharacteristic(any(), any(), any())
    }
}
