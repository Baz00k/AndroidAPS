package app.aaps.pump.ypsopump.ble

import android.bluetooth.BluetoothGatt
import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.crypto.SessionJournal
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, DISCOVERING, READY, CONNECTED }

/**
 * Which authenticated connection a piece of pump work belongs to: the connection, the session
 * generation whose counters it uses, and the pump reboot counter authenticated on it. Work started
 * on one epoch never continues on another; [YpsoPumpLink] fails it instead.
 */
internal class YpsoConnectionEpoch(
    internal val connection: YpsoGattConnection,
    internal val token: PumpSession.Token,
    /** Identity of the key the generation authenticates with. */
    val keyId: String,
    /** Verification attempt the connection was opened for, when its key is still a candidate. */
    internal val attemptId: String?,
    /** Connections opened by this process, in order; never reused. */
    val sequence: Long,
    /** Reboot counter authenticated on this connection; null until the first authenticated read. */
    val reboot: Int?,
) {
    val generation: String get() = token.generation

    /** Identity of the connection and generation, for evidence that must stay on one link. */
    val key: String get() = connectionKey(sequence, generation)

    companion object {
        internal fun connectionKey(sequence: Long, generation: String) = "$sequence:$generation"
    }
}

/** One claim on the link for an [operation]. Only this claim can write the operation's frames or release it. */
internal class YpsoLinkLease internal constructor(val operation: YpsoLinkOperation)

/** One logical pump operation. At most one owns the link at a time. */
internal enum class YpsoLinkOperation(val writeUuid: UUID?, val writePurpose: YpsoRemoteWrite?, val therapy: Boolean) {
    STATUS(null, null, false),
    PROFILE(YpsoWritePolicy.SETTING_ID_UUID, YpsoRemoteWrite.SETTINGS_SELECTOR, false),
    HISTORY(YpsoWritePolicy.EVENT_INDEX_UUID, YpsoRemoteWrite.HISTORY_SELECTOR, false),
    BOLUS(YpsoWritePolicy.BOLUS_START_STOP_UUID, YpsoRemoteWrite.THERAPY_COMMAND, true),
    TBR(YpsoWritePolicy.TBR_START_STOP_UUID, YpsoRemoteWrite.THERAPY_COMMAND, true),
}

/** Timers and the continuation thread used by the link and the operations running on it. */
internal interface YpsoLinkScheduler {
    fun schedule(task: Runnable, delayMs: Long)
    fun cancel(task: Runnable)
    fun execute(task: Runnable)
}

/** A dedicated watchdog thread, independent of UI load. */
internal class YpsoWatchdogScheduler : YpsoLinkScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "ypso-ble-watchdog").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val scheduled = ConcurrentHashMap<Runnable, ScheduledFuture<*>>()

    override fun schedule(task: Runnable, delayMs: Long) {
        scheduled.remove(task)?.cancel(false)
        scheduled[task] = executor.schedule({
            scheduled.remove(task)
            task.run()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    override fun cancel(task: Runnable) {
        scheduled.remove(task)?.cancel(false)
    }

    override fun execute(task: Runnable) = executor.execute(task)
}

/**
 * The BLE link to the pump. It owns credentials, the GATT connection and its authentication, the
 * serial operation queue and its timeouts, and which logical operation may use the link. Callbacks
 * from any connection other than the current one are dropped here, so code above the link only ever
 * sees results for the [YpsoConnectionEpoch] it started on, or a failure.
 */
@Singleton
internal class YpsoPumpLink(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val sessionCrypto: SessionCrypto,
    private val pumpState: YpsoPumpState,
    private val provisioning: YpsoProvisioningService,
    private val adapter: YpsoGattAdapter,
    override val scheduler: YpsoLinkScheduler,
) : YpsoWriteLane.Link {
    @Inject constructor(
        context: Context,
        aapsLogger: AAPSLogger,
        sessionCrypto: SessionCrypto,
        pumpState: YpsoPumpState,
        provisioning: YpsoProvisioningService,
    ) : this(context, aapsLogger, sessionCrypto, pumpState, provisioning, YpsoAndroidGattAdapter(context), YpsoWatchdogScheduler())

    /** What runs above the link. Calls marked "locked" hold the link lock and must not block. */
    internal interface Listener {
        /** Locked: [connection] stops being the pump's connection. */
        fun connectionClosed(connection: YpsoGattConnection, detail: String)

        /** Locked: [epoch] authenticated; no operation can use it before this returns. */
        fun authenticated(epoch: YpsoConnectionEpoch)

        /** Write callback for the operation that owns the link, on the [scheduler] thread. */
        fun operationWrite(connection: YpsoGattConnection, uuid: UUID, status: Int)

        /** A characteristic change on the current connection; [epochKey] is null before authentication. */
        fun notification(epochKey: String?, uuid: UUID, value: ByteArray, receivedAtNanos: Long)
    }

    @Volatile internal var listener: Listener? = null

    private val lock = Any()
    private val publishConnectionState = pumpState.claimConnectionWriter()

    init {
        provisioning.quiesceConnection = { disconnect() }
    }

    val connectionState: ConnectionState get() = pumpState.connectionState
    val isConnected: Boolean get() = connectionState == ConnectionState.CONNECTED

    @Volatile override var session: PumpSession? = null
    @Volatile private var sessionToken: PumpSession.Token? = null
    private var configuredKey: ByteArray? = null
    private var configuredGeneration: String? = null
    private var configuredAttemptId: String? = null
    private var configuredConnection: YpsoProvisioningService.ConnectionSession? = null

    /** Serial confirmed by the bonded device name, or null when the name could not confirm it. */
    @Volatile internal var bondedIdentitySerial: String? = null
        private set

    @Volatile private var connection: YpsoGattConnection? = null
    private var connectionSequence = 0L
    private var activeLease: YpsoLinkLease? = null
    private val activeOperation: YpsoLinkOperation? get() = activeLease?.operation
    @Volatile private var controlNotificationsEnabled = false

    // ---- credentials and connect ----

    /**
     * Installs the protected session's credentials for the next connection. A changed key closes the
     * current one; [publish] runs under the link lock once the new credentials are in place.
     */
    internal fun configure(installed: YpsoProvisioningService.ConnectionSession, publish: () -> Unit) {
        synchronized(lock) {
            if (configuredGeneration != installed.generation) pumpState.invalidateStatus()
            if (!installed.key.contentEquals(configuredKey)) {
                disconnect()
                pumpState.invalidateStatus()
            }
            configuredKey?.fill(0)
            configuredKey = installed.key
            configuredGeneration = installed.generation
            configuredAttemptId = installed.attemptId
            configuredConnection = installed
            session = provisioning.owner
            publish()
        }
    }

    /**
     * Opens the GATT link for the exact immutable credential snapshot installed by [configure], then
     * STAYS connected. Returns immediately; the connection proceeds asynchronously
     * (CONNECTING -> DISCOVERING -> READY -> CONNECTED once MD5 auth succeeds). Only the plugin closes
     * an idle link; nothing here disconnects on its own except a fault.
     */
    fun connectConfigured() {
        val configured = synchronized(lock) { configuredConnection } ?: return
        // A stale snapshot must have no side effects: recording here would write a failure into
        // whatever session superseded it.
        if (!provisioning.isCurrentConnection(configured)) return
        val macAddress = configured.mac
        val pump = runCatching { adapter.bondedPump(macAddress, configured.serial) }.getOrElse {
            disconnect()
            val cause = connectionFailureCause(it)
            reportConnectionFailure(configured, setOf(cause), operation = "connect", terminal = cause == PumpSession.AvailabilityCause.IDENTITY_MISMATCH)
            aapsLogger.error(LTag.PUMP, "YpsoPump connection unavailable: ${it.message}")
            return
        }
        synchronized(lock) {
            if (!provisioning.isCurrentConnection(configured)) return
            if (pumpState.pumpAddress != macAddress) disconnect()
            if (connectionState != ConnectionState.DISCONNECTED) return
            try {
                val owner = session ?: PumpSession(SessionJournal(context)).also { session = it }
                sessionToken = owner.openGeneration(configured.generation, macAddress.uppercase(java.util.Locale.ROOT), configured.key)
            } catch (e: Exception) {
                pumpState.invalidateStatus(preserveDisplay = true)
                reportConnectionFailure(configured, setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN), operation = "session-open")
                aapsLogger.error(LTag.PUMP, "YpsoPump session unavailable: ${e.message}")
                return
            }
            bondedIdentitySerial = pump.observedSerial
            pumpState.observedIdentitySerial = pump.observedSerial.orEmpty()
            if (pumpState.pumpAddress != macAddress) pumpState.invalidateStatus()
            publishConnectionState(ConnectionState.CONNECTING)
            queue.clear()
            current = null
            pumpState.pumpAddress = macAddress
            aapsLogger.info(LTag.PUMP, "YpsoPump connecting to bonded pump")
            if (!provisioning.isCurrentConnection(configured)) {
                session?.quiesce()
                sessionToken = null
                publishConnectionState(ConnectionState.DISCONNECTED)
                return
            }
            val opening = runCatching { pump.connect(events, authorizer) }
            val opened = opening.getOrNull()
            opening.exceptionOrNull()?.let { aapsLogger.error(LTag.PUMP, "YpsoPump connect failed: ${it.message}") }
            if (opened == null) {
                publishConnectionState(ConnectionState.DISCONNECTED)
                pumpState.invalidateStatus(preserveDisplay = true)
                val cause = opening.exceptionOrNull()?.let(::connectionFailureCause) ?: PumpSession.AvailabilityCause.TRANSPORT
                reportConnectionFailure(configured, setOf(cause), operation = "connect-gatt")
            } else if (connectionState != ConnectionState.DISCONNECTED) {
                connection = opened
                connectionSequence++
                armHandshakeTimeout(opened, ConnectionState.CONNECTING)
            } else {
                runCatching { opened.close() }
            }
        }
    }

    // ---- epoch and operation ownership ----

    /** The authenticated connection, or null when there is none. */
    fun epoch(): YpsoConnectionEpoch? = synchronized(lock) { epochLocked() }

    private fun epochLocked(): YpsoConnectionEpoch? {
        val owner = connection ?: return null
        val token = sessionToken ?: return null
        if (connectionState != ConnectionState.CONNECTED) return null
        val record = session?.snapshot() ?: return null
        return YpsoConnectionEpoch(owner, token, record.keyId, configuredAttemptId, connectionSequence, record.reboot)
    }

    fun isCurrent(epoch: YpsoConnectionEpoch): Boolean = synchronized(lock) { isCurrentLocked(epoch) }

    private fun isCurrentLocked(epoch: YpsoConnectionEpoch): Boolean =
        connection === epoch.connection && sessionToken === epoch.token &&
            (epoch.reboot == null || session?.snapshot()?.reboot == epoch.reboot)

    /** Runs [block] under the link lock only while [epoch] is current, so nothing can replace it meanwhile. */
    fun <T> ifCurrent(epoch: YpsoConnectionEpoch, block: () -> T): T? = synchronized(lock) {
        if (isCurrentLocked(epoch)) block() else null
    }

    /** Claim one whole logical pump operation before it can enqueue anything on Android's GATT lane. */
    fun acquire(operation: YpsoLinkOperation): YpsoLinkLease? = synchronized(lock) {
        if (activeLease != null) null else YpsoLinkLease(operation).also { activeLease = it }
    }

    /** Ends [lease]. A lease already ended, for example by teardown before a newer claim, is left alone. */
    override fun release(lease: YpsoLinkLease) {
        synchronized(lock) { if (activeLease === lease) activeLease = null }
        // A therapy write held the queue for its own reads; let anything waiting behind it run.
        if (lease.operation.therapy) pumpOps()
    }

    fun isActive(operation: YpsoLinkOperation): Boolean = synchronized(lock) { activeOperation == operation }

    // ---- teardown ----

    fun disconnect(preserveStatus: Boolean = false) {
        val (owned, failed) = synchronized(lock) {
            val owned = connection
            owned?.let { listener?.connectionClosed(it, "local disconnect") }
            connection = null
            session?.quiesce()
            sessionToken = null
            publishConnectionState(ConnectionState.DISCONNECTED)
            activeLease = null
            controlNotificationsEnabled = false
            if (!preserveStatus) pumpState.invalidateStatus(preserveDisplay = true)
            owned to drainPendingOperationsLocked()
        }
        // A deliberate local teardown is not a pump failure: suppress callback reports while draining.
        drain(failed)
        runCatching { owned?.disconnect() }
        runCatching { owned?.close() }
    }

    /**
     * Tears down [epoch]'s connection, if it is still the pump's, and when [cause] is non-null records
     * exactly one durable availability failure against the epoch's generation and verification attempt.
     */
    fun fail(epoch: YpsoConnectionEpoch, message: String, cause: PumpSession.AvailabilityCause? = null) =
        fail(epoch.connection, message, cause, ownership = FailureOwner(epoch.generation, epoch.attemptId))

    private data class FailureOwner(val generation: String?, val attemptId: String?)

    private fun fail(
        failed: YpsoGattConnection,
        message: String,
        cause: PumpSession.AvailabilityCause? = null,
        code: Int? = null,
        operation: String = "ble",
        ownership: FailureOwner? = null,
    ) {
        val (operations, owner) = synchronized(lock) {
            if (connection !== failed) return
            val captured = ownership ?: FailureOwner(sessionToken?.generation ?: configuredGeneration, configuredAttemptId)
            detachLocked(failed, message) to captured
        }
        aapsLogger.error(LTag.PUMP, "YpsoPump: $message")
        if (cause != null) {
            runCatching {
                if (cause == PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED) {
                    provisioning.failCandidateOrRecord(
                        owner.generation, owner.attemptId, setOf(cause), operation = operation,
                        firmware = firmware(), code = code
                    )
                } else {
                    provisioning.recordCandidateOrUnavailable(
                        owner.generation, owner.attemptId, setOf(cause), operation = operation,
                        firmware = firmware(), code = code
                    )
                }
            }
        }
        drain(operations)
        runCatching { failed.disconnect() }
        runCatching { failed.close() }
    }

    private fun detachLocked(failed: YpsoGattConnection, detail: String): List<Op> {
        connection = null
        publishConnectionState(ConnectionState.DISCONNECTED)
        pumpState.invalidateStatus(preserveDisplay = true)
        listener?.connectionClosed(failed, detail)
        activeLease = null
        controlNotificationsEnabled = false
        return drainPendingOperationsLocked()
    }

    private fun drainPendingOperationsLocked(): List<Op> {
        session?.quiesce()
        sessionToken = null
        handshakeTimeout?.let { runCatching { scheduler.cancel(it) } }
        handshakeTimeout = null
        val operations = buildList {
            current?.let(::add)
            addAll(queue)
        }
        current = null
        currentConnection = null
        currentTimeout?.let { runCatching { scheduler.cancel(it) } }
        currentTimeout = null
        queue.clear()
        multiframeOwner = null
        return operations
    }

    // Depth of active teardown drains: the teardown already recorded the durable failure, so callbacks
    // forced out by the drain must not report a second one. A counter keeps nested/overlapping teardowns
    // from re-enabling reporting while another drain is still running.
    private val teardownReporting = AtomicInteger()

    private fun drain(operations: List<Op>) {
        teardownReporting.incrementAndGet()
        try {
            operations.forEach { op ->
                runCatching { op.onResult(null, NO_CONNECTION_STATUS) }
                    .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump failed-operation callback threw: ${it.message}") }
            }
        } finally {
            teardownReporting.decrementAndGet()
        }
    }

    /**
     * Route a connection failure to the exact verification attempt. Identity failures are terminal for
     * the attempt; transport and session-open failures keep it for bounded retry.
     */
    private fun reportConnectionFailure(
        configured: YpsoProvisioningService.ConnectionSession,
        causes: Set<PumpSession.AvailabilityCause>,
        operation: String,
        terminal: Boolean = false,
    ) {
        if (terminal) {
            provisioning.failCandidateOrRecord(configured.generation, configured.attemptId, causes, operation, firmware = firmware())
        } else {
            provisioning.recordCandidateOrUnavailable(configured.generation, configured.attemptId, causes, operation, firmware = firmware())
        }
    }

    private fun firmware(): String? = pumpState.masterVersion.takeIf(String::isNotBlank)

    // ---- handshake ----

    private var handshakeTimeout: Runnable? = null

    private fun armHandshakeTimeout(owner: YpsoGattConnection, phase: ConnectionState) {
        handshakeTimeout?.let(scheduler::cancel)
        val timeout = Runnable {
            synchronized(lock) {
                if (connection !== owner || connectionState != phase) return@Runnable
                fail(owner, "$phase timed out", cause = PumpSession.AvailabilityCause.TRANSPORT)
            }
        }
        handshakeTimeout = timeout
        scheduler.schedule(timeout, OP_TIMEOUT_MS)
    }

    /**
     * The single ownership check for platform callbacks: [block] runs under the link lock only for the
     * current connection. A callback from a replaced connection is dropped here.
     */
    private inline fun <T> owned(from: YpsoGattConnection, block: () -> T): T? = synchronized(lock) {
        if (from === connection) block() else {
            aapsLogger.warn(LTag.PUMP, "YpsoPump ignored callback from stale GATT instance")
            null
        }
    }

    private class Box<T>(val value: T)

    private val events = object : YpsoGattEvents {
        override fun onConnectionStateChange(connection: YpsoGattConnection, connected: Boolean, status: Int) {
            if (connected) {
                val failure = owned(connection) {
                    if (connectionState != ConnectionState.CONNECTING) return
                    if (status != BluetoothGatt.GATT_SUCCESS) return@owned "connection failed ($status)"
                    publishConnectionState(ConnectionState.DISCOVERING)
                    armHandshakeTimeout(connection, ConnectionState.DISCOVERING)
                    runCatching { connection.discoverServices() }.fold(
                        onSuccess = { dispatched -> if (dispatched) null else "service discovery could not be started" },
                        onFailure = { "service discovery dispatch failed: ${it.message}" },
                    )
                }
                failure?.let { fail(connection, it, cause = PumpSession.AvailabilityCause.TRANSPORT) }
                return
            }
            val (failed, ownership) = owned(connection) {
                val ownership = FailureOwner(sessionToken?.generation, configuredAttemptId)
                detachLocked(connection, "remote disconnect") to ownership
            } ?: return
            drain(failed)
            // Remote teardown records exactly one transport failure; drained callbacks are
            // suppressed by the teardown marker so a cancellation/disconnect cannot count twice.
            provisioning.recordCandidateOrUnavailable(
                ownership.generation, ownership.attemptId,
                setOf(PumpSession.AvailabilityCause.TRANSPORT), operation = "gatt-disconnected"
            )
            runCatching { connection.close() }
        }

        override fun onServicesDiscovered(connection: YpsoGattConnection, status: Int) {
            val failure = runCatching {
                owned(connection) {
                    if (connectionState != ConnectionState.DISCOVERING) return
                    if (status != BluetoothGatt.GATT_SUCCESS) return@owned "service discovery failed ($status)"
                    connection.customCharacteristics().forEach { (service, characteristics) ->
                        aapsLogger.info(LTag.PUMP, "YpsoPump discovered service $service custom characteristics=$characteristics")
                    }
                    if (connection.properties(YpsoGattLayout.CHAR_AUTH) == null) return@owned "AUTH characteristic not found"
                    publishConnectionState(ConnectionState.READY)
                    armHandshakeTimeout(connection, ConnectionState.READY)
                    aapsLogger.info(LTag.PUMP, "YpsoPump connected; writing MD5 auth")
                    val password = YpsoAuthentication.password(pumpState.pumpAddress)
                    if (!connection.writeCharacteristic(YpsoGattLayout.CHAR_AUTH, password, YpsoRemoteWrite.AUTHENTICATION)) {
                        return@owned "auth write could not be dispatched"
                    }
                    null
                } ?: return
            }.getOrElse { "authentication dispatch failed: ${it.message}" }
            fail(connection, failure, cause = PumpSession.AvailabilityCause.TRANSPORT)
        }

        override fun onCharacteristicWrite(connection: YpsoGattConnection, uuid: UUID, status: Int) {
            if (uuid == YpsoGattLayout.CHAR_AUTH) return authenticationWritten(connection, status)
            val operationWrite = owned(connection) { activeOperation?.writeUuid == uuid } ?: return
            if (operationWrite) {
                scheduler.execute { listener?.operationWrite(connection, uuid, status) }
            } else {
                completeCurrent(connection, uuid, null, status)
            }
        }

        override fun onCharacteristicRead(connection: YpsoGattConnection, uuid: UUID, value: ByteArray?, status: Int) =
            completeCurrent(connection, uuid, value, status)

        override fun onDescriptorWrite(connection: YpsoGattConnection, uuid: UUID, status: Int) =
            completeCurrent(connection, uuid, null, status)

        override fun onCharacteristicChanged(connection: YpsoGattConnection, uuid: UUID, value: ByteArray, receivedAtNanos: Long) {
            val key = owned(connection) {
                aapsLogger.debug(LTag.PUMP, "YpsoPump notify $uuid: ${value.joinToString("") { "%02x".format(it) }}")
                // Captured with the payload so the observer can tell which connection it belongs to.
                Box(sessionToken?.let { YpsoConnectionEpoch.connectionKey(connectionSequence, it.generation) })
            } ?: return
            // Dispatch outside the link lock: observers persist durable bolus state.
            runCatching { listener?.notification(key.value, uuid, value, receivedAtNanos) }
                .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump notification observer failed: ${it.message}") }
        }
    }

    private fun authenticationWritten(from: YpsoGattConnection, status: Int) {
        var ownership: FailureOwner? = null
        val failure = owned(from) {
            ownership = FailureOwner(sessionToken?.generation ?: configuredGeneration, configuredAttemptId)
            if (connectionState != ConnectionState.READY) return
            aapsLogger.debug(LTag.PUMP, "auth write status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return@owned "auth write failed ($status)"
            handshakeTimeout?.let(scheduler::cancel)
            handshakeTimeout = null
            publishConnectionState(ConnectionState.CONNECTED)
            aapsLogger.info(LTag.PUMP, "YpsoPump ready (authenticated, control notifications enabled=false)")
            epochLocked()?.let { epoch -> listener?.authenticated(epoch) }
            null
        } ?: return
        if (status == ERR_NO_SHARED_KEY) {
            fail(
                from, failure, cause = PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED,
                code = ERR_NO_SHARED_KEY, operation = YpsoGattLayout.CHAR_AUTH.toString(), ownership = ownership
            )
        } else {
            fail(
                from, failure, cause = PumpSession.AvailabilityCause.AUTHENTICATION,
                code = status.takeIf { it >= 0 }, ownership = ownership
            )
        }
    }

    // ---- guarded writes ----

    private class AuthorizedFrame(val uuid: UUID, val purpose: YpsoRemoteWrite, val value: ByteArray)

    // Plaintext IDs are allowlisted by the write lane before encryption/reservation. The final
    // GATT boundary admits only these bytes, compared with a private copy, during that lane's synchronous dispatch.
    private var authorizedFrame: AuthorizedFrame? = null

    private val authorizer = YpsoWriteAuthorizer { from, purpose, characteristic, descriptor, value ->
        val allowed = synchronized(lock) {
            from === connection && characteristic != null && when {
                descriptor != null -> YpsoWritePolicy.allowsDescriptor(purpose, characteristic, descriptor, value)
                purpose == YpsoRemoteWrite.SETTINGS_SELECTOR ||
                    purpose == YpsoRemoteWrite.HISTORY_SELECTOR ||
                    purpose == YpsoRemoteWrite.THERAPY_COMMAND ->
                    authorizedFrame?.let { it.uuid == characteristic && it.purpose == purpose && it.value.contentEquals(value) } == true
                else -> YpsoWritePolicy.allowsCharacteristic(
                    purpose, characteristic, value,
                    runCatching { YpsoAuthentication.password(pumpState.pumpAddress) }.getOrDefault(byteArrayOf()),
                    connectionState == ConnectionState.READY
                )
            }
        }
        if (!allowed) aapsLogger.error(LTag.PUMP, "YpsoPump policy blocked $purpose write to ${descriptor ?: characteristic}")
        allowed
    }

    /**
     * Sends one frame of [lease]'s selector or therapy write. Only the lease that owns the link may
     * write, only between queued operations, and only this exact frame is admitted at the boundary.
     */
    override fun writeFrame(epoch: YpsoConnectionEpoch, lease: YpsoLinkLease, value: ByteArray, mayDispatch: () -> Boolean): Boolean = synchronized(lock) {
        val operation = lease.operation
        val uuid = operation.writeUuid ?: return@synchronized false
        val purpose = operation.writePurpose ?: return@synchronized false
        if (!isCurrentLocked(epoch) || current != null || activeLease !== lease) return@synchronized false
        if (epoch.connection.properties(uuid) == null) return@synchronized false
        // Last, so no wait above can carry a frame past its caller's deadline.
        if (!mayDispatch()) return@synchronized false
        authorizedFrame = AuthorizedFrame(uuid, purpose, value.copyOf())
        try {
            epoch.connection.writeCharacteristic(uuid, value, purpose)
        } finally {
            authorizedFrame = null
        }
    }

    /** Enables CONTROL_NOTIFY once per connection; [done] reports whether it is enabled. */
    fun enableControlNotifications(epoch: YpsoConnectionEpoch, done: (Boolean) -> Unit) {
        if (controlNotificationsEnabled) {
            done(true)
            return
        }
        val owner = epoch.connection
        if (!owner.enableNotification(YpsoWritePolicy.CONTROL_NOTIFY_UUID, YpsoWritePolicy.CCCD_UUID)) {
            done(false)
            return
        }
        enqueue(
            Op(
                owner,
                YpsoWritePolicy.CCCD_UUID,
                priority = synchronized(lock) { activeOperation?.therapy == true },
                action = { target, op ->
                    if (!target.writeDescriptor(
                            YpsoWritePolicy.CONTROL_NOTIFY_UUID, YpsoWritePolicy.CCCD_UUID, byteArrayOf(1, 0),
                            YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR
                        )
                    ) complete(op, target, YpsoWritePolicy.CCCD_UUID, null, NO_CONNECTION_STATUS)
                },
                onResult = { _, status ->
                    // Set only for the connection that enabled it: a teardown in between already reset it.
                    val success = status == BluetoothGatt.GATT_SUCCESS && synchronized(lock) {
                        (connection === owner).also { if (it) controlNotificationsEnabled = true }
                    }
                    done(success)
                },
            ),
        )
    }

    // ---- serial GATT op queue ----
    // Ops are enqueued from command threads and completed from the BLE callback thread, so the queue
    // state is guarded by [lock].

    private class Op(
        val connection: YpsoGattConnection,
        val uuid: UUID,
        /** May run while a therapy write owns the link; everything else waits for it. */
        val priority: Boolean,
        val action: (YpsoGattConnection, Op) -> Unit,
        /** [NO_CONNECTION_STATUS] when the op never completed on its connection. */
        val onResult: (ByteArray?, Int) -> Unit,
    )

    private val queue = ArrayDeque<Op>()
    private var current: Op? = null
    private var currentConnection: YpsoGattConnection? = null
    private var currentTimeout: Runnable? = null
    /** The op whose action runs under [lock]; a result it produces synchronously is delivered after unlocking. */
    private var dispatching: Op? = null
    private var dispatchedResult: (() -> Unit)? = null

    private fun enqueue(op: Op) {
        synchronized(lock) { queue.addLast(op) }
        pumpOps()
    }

    private fun pumpOps() {
        val (op, owner, timeout) = synchronized(lock) {
            if (current != null) return
            val op = if (activeOperation?.therapy == true) {
                val index = queue.indexOfFirst { it.priority }
                if (index < 0) return
                queue.removeAt(index)
            } else queue.removeFirstOrNull() ?: return
            val owner = connection
            if (owner == null || owner !== op.connection) return@synchronized Triple(op, null, null)
            // A dropped callback used to leave the active operation and multi-frame transaction latched
            // forever. Time each operation out so its result path tears down the transaction cleanly.
            val timeout = Runnable {
                aapsLogger.error(LTag.PUMP, "YpsoPump: BLE op ${op.uuid} timed out after ${OP_TIMEOUT_MS}ms")
                complete(op, owner, op.uuid, null, OP_TIMEOUT_STATUS)
            }
            current = op
            currentConnection = owner
            currentTimeout = timeout
            Triple(op, owner, timeout)
        }
        if (owner == null || timeout == null) {
            runCatching { op.onResult(null, NO_CONNECTION_STATUS) }
                .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump operation callback threw: ${it.message}") }
            pumpOps()
            return
        }
        val refused = synchronized(lock) {
            if (current !== op || currentConnection !== owner || connection !== owner) return@synchronized null
            dispatching = op
            try {
                runCatching {
                    scheduler.schedule(timeout, OP_TIMEOUT_MS)
                    op.action(owner, op)
                }.onFailure {
                    aapsLogger.error(LTag.PUMP, "YpsoPump operation dispatch threw: ${it.message}")
                    complete(op, owner, op.uuid, null, NO_CONNECTION_STATUS)
                }
            } finally {
                dispatching = null
            }
            dispatchedResult.also { dispatchedResult = null }
        }
        refused?.invoke()
    }

    private fun complete(op: Op, from: YpsoGattConnection, uuid: UUID, value: ByteArray?, status: Int) {
        val timeout = synchronized(lock) {
            if (current !== op || currentConnection !== from || connection !== from || op.uuid != uuid) return
            current = null
            currentConnection = null
            val timeout = currentTimeout.also { currentTimeout = null }
            // A refused or throwing dispatch completes inside pumpOps' locked section; deliver it from there.
            if (dispatching === op) {
                dispatchedResult = { deliver(op, timeout, value, status) }
                return
            }
            timeout
        }
        deliver(op, timeout, value, status)
    }

    private fun deliver(op: Op, timeout: Runnable?, value: ByteArray?, status: Int) {
        timeout?.let { runCatching { scheduler.cancel(it) } }
        // Results can publish availability and synchronously enter the pump plugin. Never do that while
        // holding the link lock: a therapy command may be waiting for this exact callback, and the reverse
        // lock order otherwise deadlocks the command worker and Android's main thread. The operation was
        // detached above, so teardown cannot drain or deliver it a second time.
        runCatching { op.onResult(value, status) }
            .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump operation callback threw: ${it.message}") }
        pumpOps()
    }

    private fun completeCurrent(from: YpsoGattConnection, uuid: UUID, value: ByteArray?, status: Int) {
        // Android callbacks expose no operation ID. Same-UUID read chains are protected by frame
        // sequence validation; selector and therapy writes have a separate whole-write owner.
        val op = owned(from) { current?.takeIf { currentConnection === from && it.uuid == uuid } } ?: return
        complete(op, from, uuid, value, status)
    }

    /** Ends [epoch]'s operation in flight without reporting a transport failure. */
    fun cancelCurrentOperation(epoch: YpsoConnectionEpoch) {
        val op = synchronized(lock) { current?.takeIf { currentConnection === epoch.connection } } ?: return
        complete(op, epoch.connection, op.uuid, null, LOCAL_CANCEL_STATUS)
    }

    /** One single-frame read; any status other than [BluetoothGatt.GATT_SUCCESS] is a failure. */
    fun read(epoch: YpsoConnectionEpoch, uuid: UUID, onResult: (ByteArray?, Int) -> Unit) =
        enqueue(Op(epoch.connection, uuid, priority = false, action = { target, op ->
            if (!target.read(uuid)) complete(op, target, uuid, null, NO_CONNECTION_STATUS)
        }, onResult = onResult))

    // The pump's EXTREAD characteristic is a single shared cursor, so only ONE multi-frame read may
    // be in flight at a time — overlapping reads interleave EXTREAD frames and corrupt both. This
    // guard rejects (rather than silently corrupting) an overlapping read; all internal flows chain
    // sequentially via callbacks.
    private var multiframeOwner: Any? = null

    /**
     * Reads a multi-frame value on [epoch]. A transport failure tears the link down and is recorded
     * once; with [reportToAttempt] it counts against the epoch's verification attempt. [onFailure]
     * also runs for a local cancellation, without a report. [priority] reads may run while a therapy
     * write owns the link.
     */
    fun readFrames(
        epoch: YpsoConnectionEpoch,
        uuid: UUID,
        reportToAttempt: Boolean = false,
        priority: Boolean = false,
        onFailure: () -> Unit = {},
        done: (ByteArray) -> Unit,
    ) {
        val token = Any()
        val owner = epoch.connection
        val claimed = synchronized(lock) {
            if (multiframeOwner != null || !isCurrentLocked(epoch)) false else {
                multiframeOwner = token
                true
            }
        }
        if (!claimed) {
            aapsLogger.error(LTag.PUMP, "YpsoPump: multi-frame read on $uuid rejected (disconnected or another read is active)")
            onFailure()
            return
        }
        val frames = ArrayList<ByteArray>()
        val active = AtomicBoolean(true)
        var totalFrames = 0
        fun finishTransaction(): Boolean {
            if (!active.compareAndSet(true, false)) return false
            synchronized(lock) { if (multiframeOwner === token) multiframeOwner = null }
            return true
        }
        fun step(now: UUID, expectedFrame: Int): Unit = enqueue(Op(owner, now, priority, action = { target, op ->
            if (!target.read(now)) complete(op, target, now, null, NO_CONNECTION_STATUS)
        }) { v, s ->
            val reportedTotal = v?.let { YpsoFraming.validateFrame(it, expectedFrame, totalFrames) }
            val invalidFrame = v != null && reportedTotal == null
            if (s != BluetoothGatt.GATT_SUCCESS || v == null || invalidFrame) {
                if (!finishTransaction()) return@Op
                if (s == LOCAL_CANCEL_STATUS) {
                    onFailure()
                    return@Op
                }
                // Name 0x8C explicitly. Reported as a bare "status=140" it reads like a transient BLE
                // fault and invites hours of restarting things that cannot possibly help; it actually
                // means the shared key is gone and only a re-key will fix it.
                val message = if (s == ERR_NO_SHARED_KEY)
                    "Pump returned code 140 (0x8C) on $now; the configured key/session is probably no longer accepted and external re-provisioning is required."
                else if (invalidFrame)
                    "read $now returned an invalid frame; expected frame $expectedFrame${if (totalFrames == 0) "" else "/$totalFrames"}"
                else
                    "read $now failed (status=$s, got ${frames.size} frames)"
                fail(owner, message, cause = null)
                if (teardownReporting.get() == 0) reportReadFailure(epoch, now, s, reportToAttempt)
                onFailure()
                return@Op
            }
            frames.add(v)
            if (totalFrames == 0) totalFrames = reportedTotal ?: 1
            if (frames.size < totalFrames) step(YpsoGattLayout.CHAR_EXTREAD, expectedFrame + 1) else {
                if (!finishTransaction()) return@Op
                if (isCurrent(epoch)) done(reassemble(frames)) else onFailure()
            }
        })
        step(uuid, 1)
    }

    private fun reportReadFailure(epoch: YpsoConnectionEpoch, uuid: UUID, status: Int, reportToAttempt: Boolean) {
        val causes = if (status == ERR_NO_SHARED_KEY) setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED)
        else setOf(PumpSession.AvailabilityCause.TRANSPORT)
        if (!reportToAttempt) {
            provisioning.recordUnavailable(causes = causes, code = status.takeIf { it >= 0 }, operation = uuid.toString(), firmware = firmware())
        } else if (status == ERR_NO_SHARED_KEY) {
            provisioning.failCandidateOrRecord(epoch.generation, epoch.attemptId, causes, operation = uuid.toString(), code = status, firmware = firmware())
        } else {
            provisioning.recordCandidateOrUnavailable(
                epoch.generation, epoch.attemptId, causes, operation = uuid.toString(), code = status.takeIf { it >= 0 }, firmware = firmware()
            )
        }
    }

    private fun reassemble(frames: List<ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        for (f in frames) if (f.size > 1) for (i in 1 until f.size) out.add(f[i])
        return out.toByteArray()
    }

    /**
     * Decrypts [payload] with [epoch]'s session. Throws when the epoch is no longer current, and closes
     * the link when the pump turns out to have rebooted.
     */
    fun decrypt(epoch: YpsoConnectionEpoch, payload: ByteArray): ByteArray = synchronized(lock) {
        val owner = checkNotNull(session) { "No durable session" }
        check(isCurrentLocked(epoch)) { "No session generation" }
        val origin = epoch.token
        val transaction = owner.begin(origin)
        try {
            owner.decrypt(origin, transaction, payload, sessionCrypto, allowObservedReboot = pumpState.hasCompatibleStatusProtocol())
        } catch (e: PumpSession.RebootAdoptedException) {
            disconnect()
            throw e
        } finally {
            owner.finish(origin, transaction)
        }
    }

    /**
     * Keeps an interrupted write's counter as the session's high-water mark and retires its reservation
     * on the session open now, so later commands can proceed.
     */
    override fun recoverInterruptedWrite() {
        synchronized(lock) {
            val token = sessionToken ?: return
            session?.recoverInterruptedWrite(token)
        }
    }

    companion object {
        /** A BLE op with no callback in this long is treated as stalled and force-failed. */
        internal const val OP_TIMEOUT_MS = 8000L
        /** The op never completed on its connection: refused, drained by teardown, or replaced. */
        internal const val NO_CONNECTION_STATUS = -1
        private const val OP_TIMEOUT_STATUS = -2   // a timed-out op, distinct from NO_CONNECTION_STATUS
        internal const val LOCAL_CANCEL_STATUS = -3 // local preemption; release ownership without reporting transport failure
        // App-error 0x8C (140) is documented as NO_SHARED_KEY / key exchange required. It was observed
        // after prolonged access on V05.00.52. Advisory tracking uses the maintainer-reported 28-day
        // maximum (issue #211); this error is still suspected re-key/session loss, not a measured
        // expiry countdown. Date edits must never clear it or authorize another rejected-key retry.
        internal const val ERR_NO_SHARED_KEY = 140
    }
}
