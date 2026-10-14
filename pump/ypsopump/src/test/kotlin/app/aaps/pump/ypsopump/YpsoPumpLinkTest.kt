package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.ConnectionState
import app.aaps.pump.ypsopump.ble.YpsoAuthentication
import app.aaps.pump.ypsopump.ble.YpsoBondException
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_AUTH
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_BOLUS_STATUS
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_EXTREAD
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_STATUS
import app.aaps.pump.ypsopump.ble.YpsoLinkLease
import app.aaps.pump.ypsopump.ble.YpsoLinkOperation
import app.aaps.pump.ypsopump.ble.YpsoRemoteWrite
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.crypto.PumpSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/** Connection lifecycle, ownership and the write boundary of [app.aaps.pump.ypsopump.ble.YpsoPumpLink]. */
class YpsoPumpLinkTest {
    private val fixture = YpsoPumpFixture()
    private val link = fixture.link
    private val pumpState = fixture.pumpState
    private val provisioning = fixture.provisioning

    /** Starts a connection without answering any of its callbacks. */
    private fun open(): FakeYpsoPump.Connection {
        pumpState.pumpAddress = fixture.mac
        fixture.manager.connectConfiguredSession()
        return fixture.pump.current
    }

    private fun assertTornDown(connection: FakeYpsoPump.Connection) {
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
        assertNull(link.epoch())
        assertTrue(connection.disconnected)
        assertTrue(connection.closed)
    }

    @Test
    fun `the handshake publishes each phase and ends authenticated`() {
        val connection = open()
        assertEquals(ConnectionState.CONNECTING, pumpState.connectionState)
        connection.connected()
        assertEquals(ConnectionState.DISCOVERING, pumpState.connectionState)
        connection.servicesDiscovered()
        assertEquals(ConnectionState.READY, pumpState.connectionState)
        assertNull(link.epoch())
        // Independent Python hashlib MD5(mac bytes + documented access salt), public synthetic MAC.
        assertEquals("04319d09e5ba61be2acf95ebebffe38a", connection.writes.single().second.joinToString("") { "%02x".format(it) })
        connection.ackWrite(CHAR_AUTH)
        assertEquals(ConnectionState.CONNECTED, pumpState.connectionState)
        val epoch = checkNotNull(link.epoch())
        assertEquals(fixture.generation, epoch.generation)
        assertEquals(8, epoch.reboot)
    }

    @Test
    fun `duplicate discovery and auth callbacks advance handshake only once`() {
        val connection = open()
        connection.connected()
        connection.servicesDiscovered()
        connection.servicesDiscovered()
        assertEquals(ConnectionState.READY, pumpState.connectionState)
        assertEquals(1, connection.writes.size)
        connection.ackWrite(CHAR_AUTH)
        connection.ackWrite(CHAR_AUTH)
        assertEquals(ConnectionState.CONNECTED, pumpState.connectionState)
    }

    @Test
    fun `connected callback with GATT error fails before discovery`() {
        var discovered = false
        fixture.pump.configure = { it.discoverServices = { discovered = true; true } }
        val connection = open()
        pumpState.publishStatus(42.0, 50, false, 100, 1234L)

        connection.connected(133)

        assertTornDown(connection)
        assertFalse(pumpState.hasVerifiedStatus)
        assertFalse(discovered)
    }

    @Test
    fun `rejected or throwing service discovery dispatch fails handshake`() {
        for (dispatch in listOf<() -> Boolean>({ false }, { throw IllegalStateException("dispatch failed") })) {
            val fixture = YpsoPumpFixture()
            fixture.pump.configure = { it.discoverServices = dispatch }
            fixture.pumpState.pumpAddress = fixture.mac
            fixture.manager.connectConfiguredSession()
            val connection = fixture.pump.current
            fixture.pumpState.publishStatus(42.0, 50, false, 100, 1234L)

            connection.connected()

            assertEquals(ConnectionState.DISCONNECTED, fixture.pumpState.connectionState)
            assertFalse(fixture.pumpState.hasVerifiedStatus)
            assertTrue(connection.closed)
        }
    }

    @Test
    fun `rejected authentication dispatch fails handshake`() {
        fixture.pump.configure = { it.acceptWrites = false }
        val connection = open()
        connection.connected()
        pumpState.publishStatus(42.0, 50, false, 100, 1234L)

        connection.servicesDiscovered()

        assertTornDown(connection)
        assertFalse(pumpState.hasVerifiedStatus)
    }

    @Test
    fun `a pump without the authentication characteristic is not authenticated`() {
        fixture.pump.configure = { it.characteristics.remove(CHAR_AUTH) }
        val connection = open()
        connection.connected()
        connection.servicesDiscovered()

        assertTornDown(connection)
        assertTrue(connection.writes.isEmpty())
    }

    @Test
    fun `missing connection discovery and authentication callbacks close their owned handshake`() {
        for (phase in listOf(ConnectionState.CONNECTING, ConnectionState.DISCOVERING, ConnectionState.READY)) {
            val fixture = YpsoPumpFixture()
            fixture.pumpState.pumpAddress = fixture.mac
            fixture.manager.connectConfiguredSession()
            val connection = fixture.pump.current
            if (phase != ConnectionState.CONNECTING) connection.connected()
            if (phase == ConnectionState.READY) connection.servicesDiscovered()
            assertEquals(phase, fixture.pumpState.connectionState)
            assertEquals(listOf(8000L), fixture.scheduler.scheduled.values.toList(), "$phase")

            fixture.scheduler.fire()
            connection.servicesDiscovered()
            connection.ackWrite(CHAR_AUTH)

            assertEquals(ConnectionState.DISCONNECTED, fixture.pumpState.connectionState, "$phase")
            assertTrue(connection.closed)
            verify(fixture.provisioning).recordCandidateOrUnavailable(
                eq(fixture.generation), isNull(), eq(setOf(PumpSession.AvailabilityCause.TRANSPORT)),
                eq("ble"), any(), anyOrNull(), anyOrNull()
            )
        }
    }

    @Test
    fun `a connect deadline releases the connection and allows a subsequent connect`() {
        val first = open()
        fixture.scheduler.fire()
        assertTornDown(first)
        val second = open()
        assertEquals(ConnectionState.CONNECTING, pumpState.connectionState)
        assertEquals(2, fixture.pump.connections.size)
        assertNotEquals(first, second)
    }

    @Test
    fun `authentication code 140 preserves suspected rekey evidence and disconnects`() {
        pumpState.masterVersion = "V05.00.52"
        val connection = fixture.connect(authStatus = 140)

        assertTornDown(connection)
        verify(provisioning).failCandidateOrRecord(
            eq(fixture.generation), isNull(), eq(setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED)),
            eq(CHAR_AUTH.toString()), any(), eq("V05.00.52"), eq(140)
        )
    }

    @Test
    fun `generic authentication GATT failure is retryable and never claims key rejection`() {
        val connection = fixture.connect(authStatus = 129)

        assertTornDown(connection)
        verify(provisioning).recordCandidateOrUnavailable(
            eq(fixture.generation), isNull(), eq(setOf(PumpSession.AvailabilityCause.AUTHENTICATION)),
            eq("ble"), any(), anyOrNull(), eq(129)
        )
        verify(provisioning, never()).failCandidateOrRecord(
            anyOrNull(), anyOrNull(), eq(setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED)),
            anyOrNull(), any(), anyOrNull(), anyOrNull()
        )
    }

    @Test
    fun `a bonded pump named for another serial fails its attempt and is never connected`() {
        fixture.pump.bondFailure = YpsoBondException(PumpSession.AvailabilityCause.IDENTITY_MISMATCH, "Bonded pump name does not match configured serial")
        pumpState.publishStatus(41.0, 80, false, 100, 5000)

        fixture.manager.connectConfiguredSession()

        verify(provisioning).failCandidateOrRecord(
            eq(fixture.generation), isNull(), eq(setOf(PumpSession.AvailabilityCause.IDENTITY_MISMATCH)),
            eq("connect"), any(), anyOrNull(), isNull()
        )
        assertTrue(fixture.pump.connections.isEmpty())
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
        assertNull(pumpState.statusSnapshot)
    }

    @Test
    fun `missing bond Bluetooth off permission and unknown failures stay distinct and invalidate measurements`() {
        for ((failure, cause) in listOf(
            YpsoBondException(PumpSession.AvailabilityCause.BOND_MISSING, "existing Bluetooth bond required") to PumpSession.AvailabilityCause.BOND_MISSING,
            YpsoBondException(PumpSession.AvailabilityCause.BLUETOOTH_OFF, "Bluetooth is off") to PumpSession.AvailabilityCause.BLUETOOTH_OFF,
            SecurityException("permission missing") to PumpSession.AvailabilityCause.BLUETOOTH_PERMISSION_MISSING,
            IllegalArgumentException("platform failure") to PumpSession.AvailabilityCause.TRANSPORT,
        )) {
            val fixture = YpsoPumpFixture()
            fixture.pump.bondFailure = failure
            fixture.pumpState.publishStatus(41.0, 80, false, 100, 5000)

            fixture.manager.connectConfiguredSession()

            assertEquals(ConnectionState.DISCONNECTED, fixture.pumpState.connectionState)
            assertNull(fixture.pumpState.statusSnapshot)
            assertNull(fixture.link.epoch())
            assertTrue(fixture.pump.connections.isEmpty())
            verify(fixture.provisioning).recordCandidateOrUnavailable(
                eq(fixture.generation), isNull(), eq(setOf(cause)),
                eq("connect"), any(), anyOrNull(), isNull()
            )
            verify(fixture.provisioning, never()).failCandidateOrRecord(anyOrNull(), anyOrNull(), any(), any(), any(), anyOrNull(), anyOrNull())
        }
    }

    @Test
    fun `permission revoked when opening GATT retains permission guidance`() {
        fixture.pump.configure = { throw SecurityException("permission revoked after bond check") }
        pumpState.publishStatus(41.0, 80, false, 100, 5000)

        fixture.manager.connectConfiguredSession()

        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
        assertNull(pumpState.statusSnapshot)
        assertNull(link.epoch())
        verify(provisioning).recordCandidateOrUnavailable(
            eq(fixture.generation), isNull(), eq(setOf(PumpSession.AvailabilityCause.BLUETOOTH_PERMISSION_MISSING)),
            eq("connect-gatt"), any(), anyOrNull(), isNull()
        )
    }

    @Test
    fun `a refused GATT connection is a retryable transport failure`() {
        fixture.pump.refuseConnect = true

        fixture.manager.connectConfiguredSession()

        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
        verify(provisioning).recordCandidateOrUnavailable(
            eq(fixture.generation), isNull(), eq(setOf(PumpSession.AvailabilityCause.TRANSPORT)),
            eq("connect-gatt"), any(), anyOrNull(), isNull()
        )
    }

    @Test
    fun `a stale connection snapshot opens nothing and records nothing`() {
        org.mockito.kotlin.whenever(provisioning.isCurrentConnection(any())).thenReturn(false)

        fixture.manager.connectConfiguredSession()

        assertTrue(fixture.pump.connections.isEmpty())
        verify(provisioning, never()).recordCandidateOrUnavailable(anyOrNull(), anyOrNull(), any(), any(), any(), anyOrNull(), anyOrNull())
        verify(provisioning, never()).recordUnavailable(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `callbacks from a replaced connection cannot complete or tear down the current one`() {
        val old = fixture.connect()
        fixture.manager.disconnect()
        val current = fixture.connect()
        fixture.decryptsTo(ByteArray(0))
        val results = mutableListOf<Boolean>()
        fixture.manager.readStatus(results::add)

        old.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x44))
        old.remoteDisconnect()
        old.ackWrite(CHAR_AUTH, 140)
        old.notify(YpsoWritePolicy.CONTROL_NOTIFY_UUID, ByteArray(20))

        assertTrue(results.isEmpty())
        assertEquals(ConnectionState.CONNECTED, pumpState.connectionState)
        assertFalse(current.closed)
        verify(provisioning, never()).failCandidateOrRecord(anyOrNull(), anyOrNull(), any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `disconnect drains a pending read exactly once`() {
        val connection = fixture.connect()
        val results = mutableListOf<Boolean>()

        fixture.manager.readStatus(results::add)
        fixture.manager.disconnect()
        connection.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x55))

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        assertTornDown(connection)
    }

    @Test
    fun `an overlapping multi frame read is refused while the first transaction completes`() {
        val connection = fixture.connect()
        connection.expose(CHAR_BOLUS_STATUS)
        fixture.decryptsTo(ByteArray(0))
        val statuses = mutableListOf<Boolean>()
        val bolusStatus = mutableListOf<Any?>()

        fixture.manager.readStatus(statuses::add)
        connection.answerRead(CHAR_STATUS, byteArrayOf(0x12) + ByteArray(19) { 0x41 })
        fixture.manager.readBolusStatus { bolusStatus += it }
        assertEquals(listOf<Any?>(null), bolusStatus)
        assertEquals(ConnectionState.CONNECTED, pumpState.connectionState)
        connection.answerRead(CHAR_EXTREAD, byteArrayOf(0x22, 0x42))

        verify(fixture.sessionCrypto).decrypt(eq(ByteArray(19) { 0x41 } + byteArrayOf(0x42)), any())
        assertFalse(CHAR_BOLUS_STATUS in connection.reads)
    }

    @Test
    fun `authentication label cannot authorize a command destination a changed password or a late write`() {
        val connection = open()
        connection.connected()
        connection.servicesDiscovered()
        val password = YpsoAuthentication.password(fixture.mac)
        connection.writes.clear()

        assertFalse(connection.writeCharacteristic(CHAR_STATUS, password, YpsoRemoteWrite.AUTHENTICATION))
        assertFalse(connection.writeCharacteristic(CHAR_AUTH, password.copyOf().apply { this[0] = 0 }, YpsoRemoteWrite.AUTHENTICATION))
        connection.ackWrite(CHAR_AUTH)
        assertEquals(ConnectionState.CONNECTED, pumpState.connectionState)
        assertFalse(connection.writeCharacteristic(CHAR_AUTH, password, YpsoRemoteWrite.AUTHENTICATION))
        for (category in YpsoRemoteWrite.entries) {
            assertFalse(connection.writeCharacteristic(YpsoWritePolicy.BOLUS_START_STOP_UUID.also { connection.expose(it) }, byteArrayOf(1), category))
        }
        assertTrue(connection.writes.isEmpty())
    }

    @Test
    fun `only the authorized frame bytes reach the pump while that frame is dispatched`() {
        val connection = fixture.connect()
        connection.expose(YpsoWritePolicy.SETTING_ID_UUID)
        val frame = byteArrayOf(1, 2, 3)
        var nested: Boolean? = null
        connection.onWrite = { uuid, _ ->
            if (nested == null) nested = connection.writeCharacteristic(uuid, byteArrayOf(1, 2, 4), YpsoRemoteWrite.SETTINGS_SELECTOR)
        }
        val epoch = checkNotNull(link.epoch())

        assertFalse(link.writeFrame(epoch, YpsoLinkLease(YpsoLinkOperation.PROFILE), frame))
        val lease = checkNotNull(link.acquire(YpsoLinkOperation.PROFILE))
        assertTrue(link.writeFrame(epoch, lease, frame))
        frame[2] = 9

        assertEquals(false, nested)
        assertEquals(listOf(byteArrayOf(1, 2, 3).toList()), connection.writes.drop(1).map { it.second.toList() })
        assertFalse(connection.writeCharacteristic(YpsoWritePolicy.SETTING_ID_UUID, byteArrayOf(1, 2, 3), YpsoRemoteWrite.SETTINGS_SELECTOR))
    }

    @Test
    fun `a lease ended by teardown cannot release or write for its successor`() {
        fixture.connect().expose(YpsoWritePolicy.SETTING_ID_UUID)
        val old = checkNotNull(link.acquire(YpsoLinkOperation.PROFILE))
        fixture.manager.disconnect()
        val connection = fixture.connect()
        connection.expose(YpsoWritePolicy.SETTING_ID_UUID)
        val epoch = checkNotNull(link.epoch())
        val successor = checkNotNull(link.acquire(YpsoLinkOperation.PROFILE))

        link.release(old)

        assertTrue(link.isActive(YpsoLinkOperation.PROFILE))
        assertNull(link.acquire(YpsoLinkOperation.BOLUS))
        assertFalse(link.writeFrame(epoch, old, byteArrayOf(1)))
        assertTrue(link.writeFrame(epoch, successor, byteArrayOf(1)))
        link.release(successor)
        assertFalse(link.isActive(YpsoLinkOperation.PROFILE))
    }

    @Test
    fun `a refused dispatch reports its result after the link lock is released`() {
        val connection = fixture.connect()
        connection.refuseRead = { it == CHAR_STATUS }
        var probed = false
        fixture.manager.readStatus {
            val probe = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                assertTrue(probe.submit<Boolean> { link.epoch(); true }.get(2, java.util.concurrent.TimeUnit.SECONDS),
                           "result delivered while holding the link lock")
            } finally {
                probe.shutdownNow()
            }
            probed = true
        }

        assertTrue(probed)
    }

    @Test
    fun `a replaced connection cannot write even with the current authorization`() {
        val old = fixture.connect()
        fixture.manager.disconnect()
        fixture.connect()

        assertFalse(old.writeCharacteristic(CHAR_AUTH, YpsoAuthentication.password(fixture.mac), YpsoRemoteWrite.AUTHENTICATION))
        assertFalse(old.writeDescriptor(YpsoWritePolicy.CONTROL_NOTIFY_UUID, YpsoWritePolicy.CCCD_UUID, byteArrayOf(1, 0), YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR))
        assertEquals(1, old.writes.size)
    }

    @Test
    fun `each connection has its own epoch and an epoch never outlives its connection`() {
        fixture.connect()
        val first = checkNotNull(link.epoch())
        assertTrue(link.isCurrent(first))
        fixture.manager.disconnect()
        assertFalse(link.isCurrent(first))
        assertNull(link.ifCurrent(first) { "ran" })
        fixture.connect()
        val second = checkNotNull(link.epoch())
        assertNotEquals(first.key, second.key)
        assertEquals(first.generation, second.generation)
        assertFalse(link.isCurrent(first))
        assertThrows(IllegalStateException::class.java) { link.decrypt(first, byteArrayOf(1)) }
    }

    @Test
    fun `notifications name the connection they arrived on`() {
        val received = mutableListOf<String?>()
        fixture.manager.onBolusNotification = { _, key, _ -> received += key }
        val connection = fixture.connect()
        // Fast block delivering sequence 1, slow block idle.
        val notification = byteArrayOf(1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        connection.notify(YpsoWritePolicy.CONTROL_NOTIFY_UUID, notification)
        connection.notify(CHAR_STATUS, notification)

        assertEquals(listOf(link.epoch()?.key), received)
    }

    @Test
    fun `connection state has a single writer`() {
        assertThrows(IllegalStateException::class.java) { pumpState.claimConnectionWriter() }
    }
}
