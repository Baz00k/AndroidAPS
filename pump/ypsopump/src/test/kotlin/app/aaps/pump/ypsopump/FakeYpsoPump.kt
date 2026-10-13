package app.aaps.pump.ypsopump

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.pump.ypsopump.ble.ConnectionState
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoBondException
import app.aaps.pump.ypsopump.ble.YpsoGattAdapter
import app.aaps.pump.ypsopump.ble.YpsoGattConnection
import app.aaps.pump.ypsopump.ble.YpsoGattEvents
import app.aaps.pump.ypsopump.ble.YpsoGattLayout
import app.aaps.pump.ypsopump.ble.YpsoLinkScheduler
import app.aaps.pump.ypsopump.ble.YpsoPumpLink
import app.aaps.pump.ypsopump.ble.YpsoRemoteWrite
import app.aaps.pump.ypsopump.ble.YpsoWriteAuthorizer
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

/**
 * A pump behind [YpsoGattAdapter]. Each connection records what the link dispatched and lets a test
 * answer as the platform would. Reads of a characteristic with a [FakeYpsoPump.Connection.values]
 * entry are answered at once; others wait for [FakeYpsoPump.Connection.answerRead]. Writes are
 * recorded only when the link's authorizer admits them, as on Android.
 */
internal class FakeYpsoPump : YpsoGattAdapter {
    var bondFailure: YpsoBondException? = null
    var observedSerial: String? = null
    var refuseConnect = false
    var configure: (Connection) -> Unit = {}
    val connections = mutableListOf<Connection>()
    val current: Connection get() = connections.last()

    override fun bondedPump(mac: String, configuredSerial: String?): YpsoGattAdapter.BondedPump {
        bondFailure?.let { throw it }
        return object : YpsoGattAdapter.BondedPump {
            override val observedSerial: String? = this@FakeYpsoPump.observedSerial
            override fun connect(events: YpsoGattEvents, authorizer: YpsoWriteAuthorizer): YpsoGattConnection? {
                if (refuseConnect) return null
                return Connection(events, authorizer).also {
                    configure(it)
                    connections += it
                }
            }
        }
    }

    inner class Connection(private val events: YpsoGattEvents, private val authorizer: YpsoWriteAuthorizer) : YpsoGattConnection {
        /** Characteristics the pump exposes, with their properties. */
        val characteristics = mutableMapOf(
            YpsoGattLayout.CHAR_AUTH to WRITE,
            YpsoGattLayout.CHAR_STATUS to READ,
            YpsoGattLayout.CHAR_EXTREAD to READ,
            YpsoGattLayout.CHAR_CONTROL_VERSION to READ,
            MASTER_VERSION to READ,
            SUPERVISOR_VERSION to READ,
            YpsoWritePolicy.CONTROL_NOTIFY_UUID to READ,
        )
        /** Values answered as soon as they are read. */
        val values = mutableMapOf(
            MASTER_VERSION to "V05.00.52\u0000".toByteArray(),
            SUPERVISOR_VERSION to "V05.00.52\u0000".toByteArray(),
            YpsoGattLayout.CHAR_CONTROL_VERSION to "1.3\u0000".toByteArray(),
        )
        /** Every read the link dispatched, in order. */
        val reads = mutableListOf<UUID>()
        /** Every characteristic write that passed the authorizer, in order. */
        val writes = mutableListOf<Pair<UUID, ByteArray>>()
        val descriptorWrites = mutableListOf<Pair<UUID, ByteArray>>()
        var acceptReads = true
        /** Reads the platform refuses to dispatch. */
        var refuseRead: (UUID) -> Boolean = { false }
        var acceptWrites = true
        var discoverServices: () -> Boolean = { true }
        var notificationsEnabled = false
        var disconnected = false
        var closed = false
        /** Thrown by [disconnect] and [close] after they record the call, as a revoked permission would. */
        var teardownFailure: RuntimeException? = null
        /** Runs after a write is dispatched, for tests that answer it as the pump. */
        var onWrite: (UUID, ByteArray) -> Unit = { _, _ -> }
        /** Runs after an unanswered read is dispatched. */
        var onRead: (UUID) -> Unit = {}
        var onDescriptorWrite: () -> Unit = {}

        override fun properties(uuid: UUID): Int? = characteristics[uuid]
        override fun customCharacteristics(): Map<UUID, List<UUID>> = emptyMap()
        override fun discoverServices(): Boolean = discoverServices.invoke()

        override fun read(uuid: UUID): Boolean {
            if (uuid !in characteristics || !acceptReads || refuseRead(uuid)) return false
            reads += uuid
            values[uuid]?.let { answerRead(uuid, it) } ?: onRead(uuid)
            return true
        }

        override fun writeCharacteristic(uuid: UUID, value: ByteArray, purpose: YpsoRemoteWrite): Boolean {
            // Like the Android adapter, authorize and send a private copy.
            val bytes = value.copyOf()
            if (uuid !in characteristics || !authorizer.allows(this, purpose, uuid, null, bytes)) return false
            if (!acceptWrites) return false
            writes += uuid to bytes
            onWrite(uuid, bytes)
            return true
        }

        override fun writeDescriptor(characteristic: UUID, descriptor: UUID, value: ByteArray, purpose: YpsoRemoteWrite): Boolean {
            val bytes = value.copyOf()
            if (characteristic !in characteristics || !authorizer.allows(this, purpose, characteristic, descriptor, bytes)) return false
            descriptorWrites += descriptor to bytes
            onDescriptorWrite()
            return true
        }

        override fun enableNotification(characteristic: UUID, descriptor: UUID): Boolean {
            if (characteristic !in characteristics) return false
            notificationsEnabled = true
            return true
        }

        override fun disconnect() {
            disconnected = true
            teardownFailure?.let { throw it }
        }

        override fun close() {
            closed = true
            teardownFailure?.let { throw it }
        }

        // ---- the pump and platform answering ----

        fun connected(status: Int = 0) = events.onConnectionStateChange(this, true, status)
        fun remoteDisconnect(status: Int = 0) = events.onConnectionStateChange(this, false, status)
        fun servicesDiscovered(status: Int = 0) = events.onServicesDiscovered(this, status)
        fun answerRead(uuid: UUID, value: ByteArray?, status: Int = 0) = events.onCharacteristicRead(this, uuid, value, status)
        fun ackWrite(uuid: UUID, status: Int = 0) = events.onCharacteristicWrite(this, uuid, status)
        fun ackDescriptor(status: Int = 0) = events.onDescriptorWrite(this, YpsoWritePolicy.CCCD_UUID, status)
        fun notify(uuid: UUID, value: ByteArray, receivedAtNanos: Long = System.nanoTime()) =
            events.onCharacteristicChanged(this, uuid, value, receivedAtNanos)

        /** Exposes [uuid] as readable and writable. */
        fun expose(vararg uuids: UUID, properties: Int = READ or WRITE) = uuids.forEach { characteristics[it] = properties }
    }

    companion object {
        const val READ = BluetoothGattCharacteristic.PROPERTY_READ
        const val WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE
        val MASTER_VERSION: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb0147bc5")
        val SUPERVISOR_VERSION: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb1147bc5")
        val SERIAL: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    }
}

/** Timers fire only when a test runs them; continuations run at once. */
internal class ManualScheduler : YpsoLinkScheduler {
    val scheduled = linkedMapOf<Runnable, Long>()
    var onCancel: (Runnable) -> Unit = {}
    var execute: (Runnable) -> Unit = { it.run() }

    override fun schedule(task: Runnable, delayMs: Long) {
        scheduled[task] = delayMs
    }

    override fun cancel(task: Runnable) {
        scheduled.remove(task)
        onCancel(task)
    }

    override fun execute(task: Runnable) = execute.invoke(task)

    /** Runs every pending timer once. */
    fun fire() = scheduled.keys.toList().forEach {
        scheduled.remove(it)
        it.run()
    }
}

/** A link and manager on a [FakeYpsoPump], with the provisioning service and crypto mocked. */
internal class YpsoPumpFixture(
    initial: PumpSession.State? = null,
    val mac: String = "12:34:56:78:9A:BC",
    val key: ByteArray = ByteArray(32),
    val logger: AAPSLogger = mock(),
) {
    val context: Context = mock()
    val sessionCrypto: SessionCrypto = mock()
    val pumpState = YpsoPumpState()
    val provisioning: YpsoProvisioningService = mock()
    val scheduler = ManualScheduler()
    val pump = FakeYpsoPump()
    var state: PumpSession.State = initial ?: readBaseline(mac, key, 8, 0)
    val store = object : PumpSession.Store {
        var fail = false
        override fun load() = state
        override fun commit(state: PumpSession.State) {
            check(!fail) { "journal unavailable" }
            this@YpsoPumpFixture.state = state
        }
    }
    val session = PumpSession(store)
    val link = YpsoPumpLink(context, logger, sessionCrypto, pumpState, provisioning, pump, scheduler)
    val manager = YpsoBleManager(context, logger, sessionCrypto, pumpState, provisioning, link).apply { persistProfile = {} }
    val generation: String get() = checkNotNull(session.activeRecord()).generation
    private var readCounter = 0L

    init {
        whenever(context.getSharedPreferences(any(), any())).thenReturn(mock())
        whenever(provisioning.owner).thenReturn(session)
        whenever(provisioning.isCurrentConnection(any())).thenReturn(true)
        whenever(provisioning.markVerified(any(), anyOrNull(), anyOrNull(), any())).thenReturn(false)
        val crypto = SessionCrypto()
        whenever(sessionCrypto.encrypt(any(), any(), any(), any())).thenAnswer {
            crypto.encrypt(it.getArgument(0), it.getArgument(1), it.getArgument(2), it.getArgument(3))
        }
        installSession()
    }

    fun connectionSession(attemptId: String? = null, serial: String = "10000001") =
        YpsoProvisioningService.ConnectionSession(generation, attemptId, serial, mac, key.copyOf(), false)

    /** Installs the active generation as the configured connection. */
    fun installSession(attemptId: String? = null) {
        whenever(provisioning.connectionSession()).thenReturn(connectionSession(attemptId))
        manager.configureInstalledSession()
    }

    /** Opens a connection and completes the handshake; the pump answers authentication with [authStatus]. */
    fun connect(authStatus: Int = 0): FakeYpsoPump.Connection {
        pumpState.pumpAddress = mac
        manager.connectConfiguredSession()
        val connection = pump.current
        connection.connected()
        connection.servicesDiscovered()
        connection.ackWrite(YpsoGattLayout.CHAR_AUTH, authStatus)
        if (authStatus == 0) check(pumpState.connectionState == ConnectionState.CONNECTED) { "handshake did not complete" }
        return connection
    }

    /** A connected link whose status protocol is already known, as after a status read. */
    fun connectWithProtocol(): FakeYpsoPump.Connection = connect().also {
        pumpState.masterVersion = "V05.00.52"
        pumpState.supervisorVersion = "V05.00.52"
        pumpState.controlServiceVersion = "1.3"
    }

    /** Makes the next decrypt return [body] with a fresh read counter. */
    fun decryptsTo(body: ByteArray) {
        whenever(sessionCrypto.decrypt(any(), any())).thenReturn(SessionCrypto.Message(body, 8, ++readCounter))
    }

    /** Answers a single-frame read of [uuid] whose decrypted body is [body]. */
    fun respond(connection: FakeYpsoPump.Connection, uuid: UUID, body: ByteArray) {
        decryptsTo(body)
        connection.answerRead(uuid, SINGLE_FRAME)
    }

    companion object {
        /** Frame 1 of 1 with a one-byte encrypted payload. */
        val SINGLE_FRAME = byteArrayOf(0x11, 0x55)
    }
}
