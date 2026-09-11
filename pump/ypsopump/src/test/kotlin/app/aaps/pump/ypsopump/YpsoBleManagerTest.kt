package app.aaps.pump.ypsopump

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.ble.YpsoRemoteWrite
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.shared.tests.AAPSLoggerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService

class YpsoBleManagerTest {
    private val context: Context = mock()
    private val sessionCrypto: SessionCrypto = mock()
    private lateinit var logger: AAPSLogger
    private lateinit var pumpState: YpsoPumpState
    private lateinit var manager: YpsoBleManager
    private lateinit var provisioning: YpsoProvisioningService
    private val key = ByteArray(32)
    private fun stubStatus(body: ByteArray = validStatusPayload()) {
        whenever(sessionCrypto.decrypt(any(), any())).thenReturn(SessionCrypto.Message(body, 8, 1))
    }

    @BeforeEach
    fun setUp() {
        pumpState = YpsoPumpState()
        provisioning = mock()
        logger = mock()
        manager =
            YpsoBleManager(context, logger, sessionCrypto, pumpState, provisioning).apply {
                scheduleOpTimeout = { _, _ -> }
                cancelOpTimeout = {}
            }
        whenever(provisioning.markVerified(any(), anyOrNull(), anyOrNull(), any())).thenReturn(false)
        manager.session = PumpSession(object : PumpSession.Store {
            var saved = PumpSession.State()
            override fun load() = saved
            override fun commit(state: PumpSession.State) { saved = state }
        }).apply { provisionReadBaseline("12:34:56:78:9A:BC", key, 8, 0) }
        manager.setSharedKey("00".repeat(32))
    }

    @Test
    fun `valid callback publishes only after decrypt CRC and decode`() {
        val fixture = connectedGatt()
        stubStatus()
        val results = mutableListOf<Boolean>()
        pumpState.publishStatus(42.0, 50, false, 100, 1234L)
        assertTrue(pumpState.hasVerifiedStatus)

        manager.readStatus(results::add)

        assertFalse(pumpState.hasVerifiedStatus)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(true), results)
        assertTrue(pumpState.hasVerifiedStatus)
        assertEquals(5.5, pumpState.reservoirUnits)
        assertEquals(null, pumpState.statusSnapshot?.batteryPercent)
        assertEquals(2, pumpState.statusSnapshot?.batteryBars)
        assertEquals("1.3", pumpState.controlServiceVersion)
        verify(sessionCrypto).decrypt(org.mockito.kotlin.eq(byteArrayOf(0x55)), any())
    }

    @Test
    fun `normal status logging excludes decrypted payload and therapy values`() {
        val fixture = connectedGatt()
        stubStatus()

        manager.readStatus()
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        val infoMessages = argumentCaptor<String>()
        verify(logger, org.mockito.kotlin.atLeastOnce()).info(eq(LTag.PUMP), infoMessages.capture())
        assertTrue(infoMessages.allValues.contains("YpsoPump encrypted status accepted"))
        assertFalse(infoMessages.allValues.any { it.contains("reservoir=") || it.contains("basal=") || it.contains("raw=") })
        verify(logger, never()).debug(eq(LTag.PUMP), any<String>())
    }

    @Test
    fun `explicit diagnostic gate logs decrypted status only at debug level`() {
        val fixture = connectedGatt()
        stubStatus()
        manager.diagnosticLoggingEnabled = { true }

        manager.readStatus()
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        val debugMessages = argumentCaptor<String>()
        verify(logger, org.mockito.kotlin.atLeastOnce()).debug(eq(LTag.PUMP), debugMessages.capture())
        assertTrue(debugMessages.allValues.any { it.contains("reservoir=5.5U") && it.contains("raw=") })
        val infoMessages = argumentCaptor<String>()
        verify(logger, org.mockito.kotlin.atLeastOnce()).info(eq(LTag.PUMP), infoMessages.capture())
        assertFalse(infoMessages.allValues.any { it.contains("reservoir=") || it.contains("basal=") || it.contains("raw=") })
    }

    @Test
    fun `successful decryption cannot publish when independent serial verification is unavailable`() {
        val fixture = connectedGatt()
        stubStatus()
        doThrow(SecurityException("Pump serial could not be independently observed"))
            .whenever(provisioning).markVerified(any(), anyOrNull(), isNull(), any())
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
    }

    @Test
    fun `matching GATT serial independently verifies status`() {
        val fixture = connectedGatt(serial = "10000001\u0000".toByteArray())
        stubStatus()
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(true), results)
        assertTrue(pumpState.hasVerifiedStatus)
        verify(provisioning).markVerified(any(), anyOrNull(), eq("10000001"), any())
    }

    @Test
    fun `mismatched GATT serial cannot publish status`() {
        val fixture = connectedGatt(serial = "10000002\u0000".toByteArray())
        stubStatus()
        doThrow(SecurityException("Observed pump identity does not match configured serial"))
            .whenever(provisioning).markVerified(any(), anyOrNull(), eq("10000002"), any())
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
    }

    @Test
    fun `missing malformed or changed control version rejects status before publication`() {
        val unsupportedVersions = listOf<ByteArray?>(
            null,
            "1.3".toByteArray(),
            "1.3\u0000\u0000".toByteArray(),
            "1.4\u0000".toByteArray()
        )

        unsupportedVersions.forEach { controlVersion ->
            setUp()
            val fixture = connectedGatt(controlVersion = controlVersion)
            stubStatus()
            val results = mutableListOf<Boolean>()

            manager.readStatus(results::add)
            manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

            assertEquals(listOf(false), results, "controlVersion=${controlVersion?.contentToString()}")
            assertFalse(pumpState.hasVerifiedStatus)
            assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
        }
    }

    @Test
    fun `canonical control version in wrong service rejects status before publication`() {
        val fixture = connectedGatt(controlVersionInObservedService = false)
        stubStatus()
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        assertEquals("", pumpState.controlServiceVersion)
    }

    @Test
    fun `invalid CRC fails once without publishing status`() {
        val fixture = connectedGatt()
        stubStatus(ByteArray(20))
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
        whenever(sessionCrypto.decrypt(any(), any())).thenThrow(SecurityException("authentication failed"))
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
    }

    @Test
    fun `a key that cannot authenticate the status is reported as rejected`() {
        val fixture = connectedGatt()
        whenever(sessionCrypto.decrypt(any(), any())).thenThrow(SessionCrypto.AuthenticationFailedException())
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        verify(provisioning).failCandidateOrRecord(
            anyOrNull(), anyOrNull(), eq(setOf(PumpSession.AvailabilityCause.KEY_REJECTED)),
            anyOrNull(), any(), anyOrNull(), anyOrNull()
        )
    }

    @Test
    fun `publication failure does not add a generic encrypted-status cause`() {
        val fixture = connectedGatt()
        stubStatus()
        whenever(provisioning.markVerified(any(), anyOrNull(), anyOrNull(), any()))
            .thenThrow(SecurityException("Configured serial does not match the connected pump"))
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        verify(provisioning, never()).failCandidateOrRecord(
            any(), anyOrNull(), eq(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE)),
            anyOrNull(), any(), anyOrNull(), anyOrNull()
        )
        verify(provisioning, never()).recordCandidateOrUnavailable(
            any(), anyOrNull(), eq(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE)),
            anyOrNull(), any(), anyOrNull(), anyOrNull()
        )
    }

    @Test
    fun `decoded short status fails without publishing`() {
        val fixture = connectedGatt()
        stubStatus(YpsoCrc.appendCrc(byteArrayOf(0x01)))
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
        verify(sessionCrypto, never()).decrypt(any(), any())
    }

    @Test
    fun `cancelled attempt suppresses late valid publication`() {
        val fixture = connectedGatt()
        stubStatus()
        val results = mutableListOf<Boolean>()
        val attempt = manager.readStatus(results::add)

        assertTrue(attempt.cancel())
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
    }

    @Test
    fun `timeout fails once and ignores a late callback`() {
        var timeout: Runnable? = null
        manager.scheduleOpTimeout = { runnable, _ -> timeout = runnable }
        val fixture = connectedGatt()
        stubStatus()
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        timeout!!.run()
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        verify(sessionCrypto, never()).decrypt(any(), any())
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
        stubStatus()
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
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x13) + ByteArray(19) { 1 }, BluetoothGatt.GATT_SUCCESS)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.extRead, byteArrayOf(0x23) + ByteArray(19) { 2 }, BluetoothGatt.GATT_SUCCESS)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.extRead, byteArrayOf(0x23) + ByteArray(19) { 2 }, BluetoothGatt.GATT_SUCCESS)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.extRead, byteArrayOf(0x33, 0x03), BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        verify(sessionCrypto, never()).decrypt(any(), any())
        verify(fixture.gatt, times(2)).readCharacteristic(fixture.extRead)
    }

    @Test
    fun `changed frame total fails transaction`() {
        val fixture = connectedGatt()
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x12) + ByteArray(19) { 1 }, BluetoothGatt.GATT_SUCCESS)
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

    @Test
    fun `connected callback with GATT error fails before discovery`() {
        val gatt: BluetoothGatt = mock()
        ownGatt(gatt, ConnectionState.CONNECTING)
        pumpState.publishStatus(42.0, 50, false, 100, 1234L)

        manager.gattCallback.onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_CONNECTED)

        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
        assertFalse(pumpState.hasVerifiedStatus)
        verify(gatt, never()).discoverServices()
        verify(gatt).disconnect()
        verify(gatt).close()
    }

    @Test
    fun `rejected service discovery dispatch fails handshake`() {
        val gatt: BluetoothGatt = mock()
        whenever(gatt.discoverServices()).thenReturn(false)
        ownGatt(gatt, ConnectionState.CONNECTING)
        pumpState.publishStatus(42.0, 50, false, 100, 1234L)

        manager.gattCallback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
        assertFalse(pumpState.hasVerifiedStatus)
        verify(gatt).disconnect()
        verify(gatt).close()
    }

    @Test
    fun `throwing service discovery dispatch fails handshake`() {
        val gatt: BluetoothGatt = mock()
        whenever(gatt.discoverServices()).thenThrow(IllegalStateException("dispatch failed"))
        ownGatt(gatt, ConnectionState.CONNECTING)
        pumpState.publishStatus(42.0, 50, false, 100, 1234L)

        manager.gattCallback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
        assertFalse(pumpState.hasVerifiedStatus)
        verify(gatt).disconnect()
        verify(gatt).close()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `rejected authentication dispatch fails handshake`() {
        val gatt: BluetoothGatt = mock()
        val service: BluetoothGattService = mock()
        val auth: BluetoothGattCharacteristic = mock()
        whenever(auth.uuid).thenReturn(CHAR_AUTH)
        whenever(service.getCharacteristic(CHAR_AUTH)).thenReturn(auth)
        whenever(gatt.services).thenReturn(listOf(service))
        whenever(gatt.writeCharacteristic(auth)).thenReturn(false)
        ownGatt(gatt, ConnectionState.DISCOVERING)
        pumpState.publishStatus(42.0, 50, false, 100, 1234L)

        manager.gattCallback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
        assertFalse(pumpState.hasVerifiedStatus)
        verify(gatt).disconnect()
        verify(gatt).close()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy read callback publishes valid status`() {
        val fixture = connectedGatt()
        stubStatus()
        whenever(fixture.status.value).thenReturn(byteArrayOf(0x11, 0x55))
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(true), results)
        assertTrue(pumpState.hasVerifiedStatus)
    }

    @Test
    fun `reboot recovery uses minimum firmware policy and requires fresh reconnect`() {
        for (firmware in listOf("V05.00.52", "V05.02.03", "V06.00.00")) {
            setUp()
            val old = connectedGatt(firmware = firmware)
            whenever(sessionCrypto.decrypt(any(), any())).thenReturn(SessionCrypto.Message(validStatusPayload(), 9, 1))
            val results = mutableListOf<Boolean>()
            manager.readStatus(results::add)
            manager.gattCallback.onCharacteristicRead(old.gatt, old.status, byteArrayOf(0x11, 0x55), 0)
            assertEquals(listOf(false), results)
            assertFalse(pumpState.hasVerifiedStatus)
            assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
            val next = connectedGatt(firmware = firmware)
            whenever(sessionCrypto.decrypt(any(), any())).thenReturn(SessionCrypto.Message(validStatusPayload(), 9, 2))
            manager.readStatus(results::add)
            manager.gattCallback.onCharacteristicRead(old.gatt, old.status, byteArrayOf(0x11, 0x55), 0)
            assertEquals(listOf(false), results)
            manager.gattCallback.onCharacteristicRead(next.gatt, next.status, byteArrayOf(0x11, 0x55), 0)
            assertEquals(listOf(false, true), results)
            assertEquals(9, manager.session!!.snapshot()!!.reboot)
        }
    }

    @Test
    fun `unsupported firmware or control cannot adopt reboot`() {
        for ((firmware, control) in listOf("V05.00.51" to "1.3", "invalid" to "1.3", "V05.02.03" to "1.4")) {
            setUp()
            val fixture = connectedGatt(firmware = firmware, controlVersion = "$control\u0000".toByteArray())
            whenever(sessionCrypto.decrypt(any(), any())).thenReturn(SessionCrypto.Message(validStatusPayload(), 9, 1))
            manager.readStatus()
            manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x11, 0x55), 0)
            connectedGatt()
            assertEquals(8, manager.session!!.snapshot()!!.reboot)
        }
    }

    private fun connectedGatt(
        readDispatched: Boolean = true,
        controlVersion: ByteArray? = "1.3\u0000".toByteArray(),
        controlVersionInObservedService: Boolean = true,
        firmware: String = "V05.00.52",
        serial: ByteArray? = null
    ): GattFixture {
        val gatt: BluetoothGatt = mock()
        val identityService: BluetoothGattService = mock()
        val controlService: BluetoothGattService = mock()
        val extReadService: BluetoothGattService = mock()
        val wrongService: BluetoothGattService = mock()
        val status: BluetoothGattCharacteristic = mock()
        val extRead: BluetoothGattCharacteristic = mock()
        whenever(identityService.uuid).thenReturn(UUID.fromString("fb349b5f-8000-0080-0010-0000adde0000"))
        whenever(controlService.uuid).thenReturn(SERVICE_CONTROL)
        whenever(extReadService.uuid).thenReturn(SERVICE_EXTREAD)
        whenever(wrongService.uuid).thenReturn(UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"))
        whenever(status.uuid).thenReturn(CHAR_STATUS)
        whenever(extRead.uuid).thenReturn(CHAR_EXTREAD)
        whenever(controlService.getCharacteristic(CHAR_STATUS)).thenReturn(status)
        whenever(extReadService.getCharacteristic(CHAR_EXTREAD)).thenReturn(extRead)
        whenever(gatt.getService(SERVICE_CONTROL)).thenReturn(controlService)
        whenever(gatt.getService(SERVICE_EXTREAD)).thenReturn(extReadService)
        whenever(gatt.services).thenReturn(listOf(identityService, controlService, extReadService, wrongService))
        whenever(gatt.readCharacteristic(status)).thenReturn(readDispatched)
        whenever(gatt.readCharacteristic(extRead)).thenReturn(readDispatched)
        serial?.let { value ->
            val uuid = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
            val serialCharacteristic: BluetoothGattCharacteristic = mock()
            whenever(serialCharacteristic.uuid).thenReturn(uuid)
            whenever(identityService.getCharacteristic(uuid)).thenReturn(serialCharacteristic)
            whenever(gatt.readCharacteristic(serialCharacteristic)).thenAnswer {
                manager.gattCallback.onCharacteristicRead(gatt, serialCharacteristic, value, BluetoothGatt.GATT_SUCCESS)
                true
            }
        }
        for (suffix in listOf("fcbeb0147bc5", "fcbeb1147bc5")) {
            val uuid = UUID.fromString("669a0c20-0008-969e-e211-$suffix")
            val version: BluetoothGattCharacteristic = mock()
            whenever(version.uuid).thenReturn(uuid)
            whenever(identityService.getCharacteristic(uuid)).thenReturn(version)
            whenever(gatt.readCharacteristic(version)).thenAnswer {
                manager.gattCallback.onCharacteristicRead(gatt, version, "$firmware\u0000".toByteArray(), 0)
                true
            }
        }
        controlVersion?.let { value ->
            val uuid = UUID.fromString("669a0c20-0008-969e-e211-fcbee08b7bc5")
            val version: BluetoothGattCharacteristic = mock()
            whenever(version.uuid).thenReturn(uuid)
            val containingService = if (controlVersionInObservedService) controlService else wrongService
            whenever(containingService.getCharacteristic(uuid)).thenReturn(version)
            whenever(gatt.readCharacteristic(version)).thenAnswer {
                manager.gattCallback.onCharacteristicRead(gatt, version, value, BluetoothGatt.GATT_SUCCESS)
                true
            }
        }
        ownGatt(gatt, ConnectionState.CONNECTED)
        return GattFixture(gatt, status, extRead)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `authentication recorder captures the same destination and bytes on both Android adapters`() {
        for (sdk in listOf(31, 32, 33, 36)) {
            val gatt: BluetoothGatt = mock()
            val auth: BluetoothGattCharacteristic = mock()
            val service: BluetoothGattService = mock()
            val writes = mutableListOf<Pair<UUID, List<Byte>>>()
            val descriptor: BluetoothGattDescriptor = mock()
            whenever(descriptor.uuid).thenReturn(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            whenever(gatt.writeDescriptor(any())).thenAnswer { writes.add(descriptor.uuid to listOf(1.toByte(), 0.toByte())); true }
            whenever(gatt.writeDescriptor(any(), any())).thenAnswer {
                writes.add(descriptor.uuid to it.getArgument<ByteArray>(1).toList()); 0
            }
            var legacyValue = byteArrayOf()
            whenever(auth.uuid).thenReturn(CHAR_AUTH)
            whenever(auth.setValue(any<ByteArray>())).thenAnswer { legacyValue = it.getArgument<ByteArray>(0).copyOf(); true }
            whenever(gatt.writeCharacteristic(auth)).thenAnswer { writes.add(CHAR_AUTH to legacyValue.toList()); true }
            whenever(gatt.writeCharacteristic(any(), any(), any())).thenAnswer {
                writes.add(it.getArgument<BluetoothGattCharacteristic>(0).uuid to it.getArgument<ByteArray>(1).toList())
                0
            }
            whenever(service.getCharacteristic(CHAR_AUTH)).thenReturn(auth)
            whenever(gatt.services).thenReturn(listOf(service))
            manager.sdkInt = sdk
            pumpState.pumpAddress = "12:34:56:78:9A:BC"
            ownGatt(gatt, ConnectionState.DISCOVERING)

            manager.gattCallback.onServicesDiscovered(gatt, 0)
            manager.gattCallback.onServicesDiscovered(gatt, 0)
            manager.gattCallback.onCharacteristicWrite(gatt, auth, 0)

            // Independent Python hashlib MD5(mac bytes + documented access salt), public synthetic MAC.
            val expected = "04319d09e5ba61be2acf95ebebffe38a".chunked(2).map { it.toInt(16).toByte() }
            assertEquals(listOf(CHAR_AUTH to expected), writes, "API $sdk")
            assertEquals(ConnectionState.CONNECTED, pumpState.connectionState)

            val outcomes = mutableListOf<Boolean>()
            manager.validateWriteTransport { outcomes.add(true) }
            manager.deliverBolus(1.25, 0, 1.25) { outcomes.add(true) }
            manager.startBolus(1.25, 731) { outcome, _ -> outcomes.add(outcome == YpsoBleManager.BolusStart.NOT_SENT) }
            manager.testBolusCanary(1.25, 731) { sent, _ -> outcomes.add(!sent) }
            manager.cancelBolus(731, false) { sent, _ -> outcomes.add(!sent) }
            manager.testTbrCanary(150, 30, 731) { sent, _ -> outcomes.add(!sent) }
            manager.readLastFastBolusEvent { outcomes.add(it == null) }
            for (category in YpsoRemoteWrite.entries) {
                assertFalse(manager.writeDescriptor(gatt, descriptor, byteArrayOf(1, 0), category))
            }
            assertEquals(List(7) { true }, outcomes)
            assertEquals(0L, manager.writeCounter)
            assertEquals(listOf(CHAR_AUTH to expected), writes, "API $sdk after diagnostic and direct requests")
        }
    }

    @Test
    fun `authentication label cannot authorize a command destination or a changed password`() {
        val fixture = connectedGatt()
        val password = "04319d09e5ba61be2acf95ebebffe38a".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        pumpState.pumpAddress = "12:34:56:78:9A:BC"
        pumpState.connectionState = ConnectionState.READY
        val auth: BluetoothGattCharacteristic = mock()
        whenever(auth.uuid).thenReturn(CHAR_AUTH)
        assertEquals(false, manager.writeCharacteristic(fixture.gatt, fixture.status, password, YpsoRemoteWrite.AUTHENTICATION))
        assertEquals(false, manager.writeCharacteristic(fixture.gatt, auth, password.copyOf().apply { this[0] = 0 }, YpsoRemoteWrite.AUTHENTICATION))
        pumpState.connectionState = ConnectionState.CONNECTED
        assertEquals(false, manager.writeCharacteristic(fixture.gatt, auth, password, YpsoRemoteWrite.AUTHENTICATION))
    }

    @Test
    fun `authentication code 140 preserves suspected rekey evidence and disconnects`() {
        val gatt: BluetoothGatt = mock()
        val auth: BluetoothGattCharacteristic = mock()
        whenever(auth.uuid).thenReturn(CHAR_AUTH)
        pumpState.masterVersion = "V05.00.52"
        ownGatt(gatt, ConnectionState.READY)
        val generation = manager.session!!.activeRecord()!!.generation

        manager.gattCallback.onCharacteristicWrite(gatt, auth, 140)

        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
        verify(provisioning).failCandidateOrRecord(
            eq(generation), isNull(), eq(setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED)),
            eq(CHAR_AUTH.toString()), any(), eq("V05.00.52"), eq(140)
        )
    }

    @Test
    fun `status read code 140 is attributed to the captured attempt rather than globally`() {
        val fixture = connectedGatt()
        val results = mutableListOf<Boolean>()
        val generation = manager.session!!.activeRecord()!!.generation

        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, ByteArray(0), 140)

        assertEquals(listOf(false), results)
        verify(provisioning).failCandidateOrRecord(
            eq(generation), isNull(), eq(setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED)),
            any(), any(), anyOrNull(), eq(140)
        )
        verify(provisioning, never()).recordUnavailable(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `cancelling an active status read records one transport failure`() {
        val fixture = connectedGatt()
        val results = mutableListOf<Boolean>()

        val attempt = manager.readStatus(results::add)
        attempt.cancel()

        assertEquals(listOf(false), results)
        verify(provisioning, times(1)).recordCandidateOrUnavailable(
            anyOrNull(), anyOrNull(), eq(setOf(PumpSession.AvailabilityCause.TRANSPORT)),
            anyOrNull(), any(), anyOrNull(), anyOrNull()
        )
        verify(provisioning, never()).failCandidateOrRecord(
            anyOrNull(), anyOrNull(), eq(setOf(PumpSession.AvailabilityCause.TRANSPORT)),
            anyOrNull(), any(), anyOrNull(), anyOrNull()
        )
    }

    @Test
    fun `remote disconnect during an active read records one transport failure`() {
        val fixture = connectedGatt()

        manager.readStatus { }
        manager.gattCallback.onConnectionStateChange(fixture.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

        // One physical disconnect is exactly one retryable transport report, and it must be the
        // teardown-owned one rather than a drained-read report.
        verify(provisioning, times(1)).recordCandidateOrUnavailable(
            anyOrNull(), anyOrNull(), eq(setOf(PumpSession.AvailabilityCause.TRANSPORT)),
            anyOrNull(), any(), anyOrNull(), anyOrNull()
        )
        verify(provisioning, times(1)).recordCandidateOrUnavailable(
            anyOrNull(), anyOrNull(), eq(setOf(PumpSession.AvailabilityCause.TRANSPORT)),
            eq("gatt-disconnected"), any(), anyOrNull(), anyOrNull()
        )
        verify(provisioning, never()).recordUnavailable(
            eq(setOf(PumpSession.AvailabilityCause.TRANSPORT)), anyOrNull(), anyOrNull(), anyOrNull(), any()
        )
        verify(provisioning, never()).failCandidateOrRecord(
            anyOrNull(), anyOrNull(), eq(setOf(PumpSession.AvailabilityCause.TRANSPORT)),
            anyOrNull(), any(), anyOrNull(), anyOrNull()
        )
    }

    @Test
    fun `intentional local disconnect during an active read records no failure`() {
        connectedGatt()

        manager.readStatus { }
        manager.disconnect()

        verify(provisioning, never()).recordCandidateOrUnavailable(
            any(), anyOrNull(), any(), anyOrNull(), any(), anyOrNull(), anyOrNull()
        )
        verify(provisioning, never()).recordUnavailable(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())
        verify(provisioning, never()).failCandidateOrRecord(
            any(), anyOrNull(), any(), anyOrNull(), any(), anyOrNull(), anyOrNull()
        )
    }

    @Test
    fun `missing discovery and authentication callbacks close their owned handshake`() {
        for (phase in listOf(ConnectionState.CONNECTING, ConnectionState.DISCOVERING)) {
            val gatt: BluetoothGatt = mock()
            val service: BluetoothGattService = mock()
            val auth: BluetoothGattCharacteristic = mock()
            whenever(auth.uuid).thenReturn(CHAR_AUTH)
            whenever(service.getCharacteristic(CHAR_AUTH)).thenReturn(auth)
            whenever(gatt.services).thenReturn(listOf(service))
            whenever(gatt.discoverServices()).thenReturn(true)
            manager.sdkInt = 33
            whenever(gatt.writeCharacteristic(any(), any(), any())).thenReturn(0)
            var timeout: Runnable? = null
            manager.scheduleOpTimeout = { action, delay -> assertEquals(8000L, delay); timeout = action }
            ownGatt(gatt, phase)
            if (phase == ConnectionState.CONNECTING) manager.gattCallback.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
            else manager.gattCallback.onServicesDiscovered(gatt, 0)
            timeout!!.run()
            manager.gattCallback.onServicesDiscovered(gatt, 0)
            manager.gattCallback.onCharacteristicWrite(gatt, auth, 0)
            assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
            verify(gatt).close()
        }
    }

    @Test
    fun `diagnostic failure completes caller and releases cursor for the next session`() {
        val first = connectedGatt()
        val results = mutableListOf<Int?>()
        manager.readEventCount(results::add) // Missing characteristic is an immediate dispatch refusal.
        assertEquals(listOf<Int?>(null), results)
        verify(first.gatt).close()
        val next = connectedGatt()
        stubStatus()
        val statusResults = mutableListOf<Boolean>()
        manager.readStatus(statusResults::add)
        manager.gattCallback.onCharacteristicRead(next.gatt, next.status, byteArrayOf(0x11, 0x55), 0)
        assertEquals(listOf(true), statusResults)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy multi frame bytes are owned until reassembly`() {
        val fixture = connectedGatt()
        val first = byteArrayOf(0x12) + ByteArray(19) { 0x41 }
        whenever(fixture.status.value).thenReturn(first)
        stubStatus()
        val results = mutableListOf<Boolean>()
        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, 0)
        first[1] = 0x7f
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.extRead, byteArrayOf(0x22, 0x42), 0)
        assertEquals(listOf(true), results)
        verify(sessionCrypto).decrypt(org.mockito.kotlin.eq(ByteArray(19) { 0x41 } + byteArrayOf(0x42)), any())
    }

    @Test
    fun `callback and close exceptions still allow the next session to read`() {
        val first = connectedGatt()
        whenever(first.gatt.disconnect()).thenThrow(SecurityException("permission revoked"))
        whenever(first.gatt.close()).thenThrow(IllegalStateException("close failed"))
        var completions = 0
        manager.readStatus { completions++; throw IllegalStateException("consumer failed") }
        manager.disconnect()
        assertEquals(1, completions)
        val next = connectedGatt()
        stubStatus()
        val results = mutableListOf<Boolean>()
        manager.readStatus(results::add)
        manager.gattCallback.onCharacteristicRead(next.gatt, next.status, byteArrayOf(0x11, 0x55), 0)
        assertEquals(listOf(true), results)
        verify(first.gatt).close()
    }

    @Test
    fun `overlapping diagnostic completes refusal while original EXTREAD transaction completes`() {
        val fixture = connectedGatt()
        stubStatus()
        val statuses = mutableListOf<Boolean>()
        val diagnostics = mutableListOf<Int?>()
        manager.readStatus(statuses::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.status, byteArrayOf(0x12) + ByteArray(19) { 0x41 }, 0)
        manager.readEventCount(diagnostics::add)
        manager.gattCallback.onCharacteristicRead(fixture.gatt, fixture.extRead, byteArrayOf(0x22, 0x42), 0)
        assertEquals(listOf<Int?>(null), diagnostics)
        assertEquals(listOf(true), statuses)
        verify(sessionCrypto).decrypt(org.mockito.kotlin.eq(ByteArray(19) { 0x41 } + byteArrayOf(0x42)), any())
    }

    @Test
    fun `cancelled owner cannot invalidate a later session sample`() {
        connectedGatt()
        val firstResults = mutableListOf<Boolean>()
        val attempt = manager.readStatus(firstResults::add)
        manager.disconnect()
        val next = connectedGatt()
        stubStatus()
        val nextResults = mutableListOf<Boolean>()
        manager.readStatus(nextResults::add)
        manager.gattCallback.onCharacteristicRead(next.gatt, next.status, byteArrayOf(0x11, 0x55), 0)
        assertFalse(attempt.cancel())
        assertEquals(listOf(false), firstResults)
        assertEquals(listOf(true), nextResults)
        assertEquals(5.5, pumpState.statusSnapshot?.reservoirUnits)
    }

    @Test
    fun `connect deadline releases GATT and allows a subsequent connect`() {
        val bluetooth: BluetoothManager = mock()
        val adapter: BluetoothAdapter = mock()
        val device: BluetoothDevice = mock()
        val gatt: BluetoothGatt = mock()
        whenever(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(bluetooth)
        whenever(bluetooth.adapter).thenReturn(adapter)
        whenever(adapter.isEnabled).thenReturn(true)
        whenever(adapter.getRemoteDevice("12:34:56:78:9A:BC")).thenReturn(device)
        whenever(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        whenever(device.connectGatt(any(), any(), any(), any())).thenReturn(gatt)
        var deadline: Runnable? = null
        manager.scheduleOpTimeout = { action, delay -> assertEquals(8000L, delay); deadline = action }
        manager.connect("12:34:56:78:9A:BC")
        assertEquals(ConnectionState.CONNECTING, pumpState.connectionState)
        deadline!!.run()
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
        verify(gatt).close()
        manager.connect("12:34:56:78:9A:BC")
        assertEquals(ConnectionState.CONNECTING, pumpState.connectionState)
        verify(device, times(2)).connectGatt(any(), any(), any(), any())
    }

    @Test
    fun `recognized bonded name must match configured serial while unknown names defer to GATT identity`() {
        val bluetooth: BluetoothManager = mock()
        val adapter: BluetoothAdapter = mock()
        val device: BluetoothDevice = mock()
        val gatt: BluetoothGatt = mock()
        val installed = YpsoProvisioningService.InstalledSession(
            "10000001", "EC:2A:F0:00:00:01", "fingerprint", null, null, emptyMap(), null,
            PumpSession.Availability(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE))
        )
        whenever(provisioning.installed()).thenReturn(installed)
        val installedKey = ByteArray(32) { 1 }
        val ownerSession = PumpSession(object : PumpSession.Store {
            var saved = PumpSession.State()
            override fun load() = saved
            override fun commit(state: PumpSession.State) { saved = state }
        }).apply { provisionReadBaseline(installed.mac, installedKey, 8, 0) }
        manager.session = ownerSession
        whenever(provisioning.owner).thenReturn(ownerSession)
        manager.setSharedKey("01".repeat(32))
        whenever(provisioning.connectionSession()).thenReturn(
            YpsoProvisioningService.ConnectionSession(
                manager.session!!.activeRecord()!!.generation, null, installed.serial, installed.mac, installedKey.copyOf(), false
            )
        )
        whenever(provisioning.isCurrentConnection(any())).thenReturn(true)
        whenever(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(bluetooth)
        whenever(bluetooth.adapter).thenReturn(adapter)
        whenever(adapter.isEnabled).thenReturn(true)
        whenever(adapter.getRemoteDevice(installed.mac)).thenReturn(device)
        whenever(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        whenever(device.connectGatt(any(), any(), any(), any())).thenReturn(gatt)
        manager.configureInstalledSession()

        whenever(device.name).thenReturn("mylife YpsoPump 000002")
        manager.connect(installed.mac)
        verify(provisioning).failCandidateOrRecord(
            any(),
            isNull(),
            eq(setOf(PumpSession.AvailabilityCause.IDENTITY_MISMATCH)),
            eq("connect"),
            any(),
            anyOrNull(),
            isNull()
        )
        verify(device, never()).connectGatt(any(), any(), any(), any())

        whenever(device.name).thenReturn("custom pump alias")
        manager.connect(installed.mac)
        assertEquals(ConnectionState.CONNECTING, pumpState.connectionState)
        assertEquals("", pumpState.observedIdentitySerial)
        verify(device).connectGatt(any(), any(), any(), any())
    }

    @Test
    fun `missing bond Bluetooth and permission refuse connection and invalidate measurements`() {
        val bluetooth: BluetoothManager = mock()
        val adapter: BluetoothAdapter = mock()
        val device: BluetoothDevice = mock()
        whenever(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(bluetooth)
        whenever(bluetooth.adapter).thenReturn(adapter)
        whenever(adapter.getRemoteDevice("12:34:56:78:9A:BC")).thenReturn(device)
        for (fault in 0..2) {
            whenever(adapter.isEnabled).thenReturn(fault != 0)
            if (fault == 2) whenever(device.bondState).thenThrow(SecurityException("permission missing"))
            else whenever(device.bondState).thenReturn(BluetoothDevice.BOND_NONE)
            pumpState.publishStatus(41.0, 80, false, 100, 5000)
            manager.connect("12:34:56:78:9A:BC")
            assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
            assertEquals(null, pumpState.statusSnapshot)
        }
    }

    private fun ownGatt(
        gatt: BluetoothGatt,
        state: ConnectionState,
    ) {
        manager.javaClass.getDeclaredField("sessionToken").apply {
            isAccessible = true
            set(manager, manager.session!!.open("12:34:56:78:9A:BC", key))
        }
        manager.javaClass.getDeclaredField("bluetoothGatt").apply {
            isAccessible = true
            set(manager, gatt)
        }
        pumpState.connectionState = state
    }

    private fun validStatusPayload(): ByteArray {
        val body = ByteArray(18)
        body[0] = 0x0a
        body[1] = 0x26
        body[2] = 0x02
        body[5] = 0x02
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
        val SERVICE_CONTROL: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0000")
        val SERVICE_EXTREAD: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0002")
    }
}
