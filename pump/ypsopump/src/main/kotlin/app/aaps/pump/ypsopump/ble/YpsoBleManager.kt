package app.aaps.pump.ypsopump.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.comm.commands.StatusCommand
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionJournal
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.data.YpsoFirmwareVersion
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.provisioning.PumpIdentity
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE manager for the YpsoPump status-only flow: connect over the existing OS bond -> MD5 access
 * authentication -> encrypted multi-frame status read -> update [YpsoPumpState]. The target-firmware
 * protocol contract is not yet qualified.
 *
 * Set the captured session key with [setSharedKey] before connecting. No write/dosing path here yet.
 */
@Singleton
class YpsoBleManager @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val sessionCrypto: SessionCrypto,
    private val pumpState: YpsoPumpState,
    private val provisioning: YpsoProvisioningService
) {

    init {
        provisioning.quiesceConnection = { disconnect() }
    }

    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, DISCOVERING, READY, CONNECTED }

    private var bluetoothGatt: BluetoothGatt? = null
    val isConnected: Boolean get() = pumpState.connectionState == ConnectionState.CONNECTED
    internal var session: PumpSession? = null
    private var sessionToken: PumpSession.Token? = null
    @Volatile private var configuredKey: ByteArray? = null
    @Volatile private var configuredGeneration: String? = null
    @Volatile private var configuredAttemptId: String? = null
    @Volatile private var configuredConnection: YpsoProvisioningService.ConnectionSession? = null
    private var bondedIdentitySerial: String? = null
    private val readCounter: Long get() = session?.snapshot()?.read ?: 0L

    companion object {
        private const val OP_TIMEOUT_MS = 8000L   // 2026-07-13: a BLE op with no callback in this long is treated as stalled and force-failed (unwedges the queue + multiframe latch)
        private const val OP_TIMEOUT_STATUS = -2   // sentinel status for a timed-out op (!= GATT_SUCCESS, distinct from -1 no-gatt)
        private val SUPPORTED_CONTROL_SERVICE_VERSION = "1.3\u0000".toByteArray(Charsets.US_ASCII)
        private val SERVICE_CONTROL: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0000")
        private val SERVICE_EXTREAD: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0002")
        private val CHAR_AUTH: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb2147bc5")
        private val CHAR_STATUS: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee48b7bc5")
        private val CHAR_EXTREAD: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff000000ff")
        // History (events) — used for the zero-therapy write-transport validation.
        private val CHAR_EVENT_COUNT: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecb3b7bc5")
        private val CHAR_BOLUS_STATUS: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee28b7bc5")
        private val CHAR_CONTROL_VERSION: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee08b7bc5")
        // Provisional control-notification UUID. Non-auth write transport is unsupported in this artifact.
        // App-error 0x8C (140) is documented as NO_SHARED_KEY / key exchange required. It was observed
        // after prolonged access on V05.00.52, but the exact invalidating event and lifetime remain
        // unresolved. Treat it as suspected re-key/session loss and preserve target evidence rather than
        // claiming a fixed 28-day expiry.
        private const val ERR_NO_SHARED_KEY = 140
    }

    /** Test/bench seam; normal builds load the key only through [configureInstalledSession]. */
    internal fun setSharedKey(hex: String) {
        require(hex.length == 64 && hex.all { it.digitToIntOrNull(16) != null }) {
            disconnect()
            "session key must contain 32 hex-encoded bytes"
        }
        val key = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        synchronized(opLock) {
            if (!key.contentEquals(configuredKey)) disconnect()
            configuredKey?.fill(0)
            configuredKey = key
            configuredGeneration = session?.activeRecord()?.generation
            configuredAttemptId = null
            configuredConnection = null
        }
    }

    fun configureInstalledSession(): Boolean {
        val installed = provisioning.connectionSession() ?: return false
        val key = installed.key
        synchronized(opLock) {
            if (!key.contentEquals(configuredKey)) disconnect()
            configuredKey?.fill(0)
            configuredKey = key
            configuredGeneration = installed.generation
            configuredAttemptId = installed.attemptId
            configuredConnection = installed
            session = provisioning.owner
        }
        return installed.mac.isNotBlank()
    }

    fun installedPumpMac(): String = provisioning.connectionSession()?.mac.orEmpty()

    /** Connect the exact immutable credential snapshot installed by [configureInstalledSession]. */
    fun connectConfiguredSession() {
        val configured = synchronized(opLock) { configuredConnection } ?: return
        connect(configured.mac, configured)
    }

    private val ypsoPrefs by lazy { context.getSharedPreferences("ypso_ble_state", Context.MODE_PRIVATE) }

    /** Debug/test migration seam retained for independently captured replay evidence. */
    internal fun importReadBaseline(mac: String, hex: String, reboot: Int, read: Long) = synchronized(opLock) {
        require(mac.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")))
        require(hex.length == 64 && hex.all { it.digitToIntOrNull(16) != null })
        disconnect()
        val key = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        try {
            val owner = session ?: PumpSession(SessionJournal(context)).also { session = it }
            owner.provisionReadBaseline(mac.uppercase(java.util.Locale.ROOT), key, reboot, read)
        } finally {
            key.fill(0)
        }
    }

    /** Legacy imported counters are diagnostics, never replay recovery or write readiness. */
    internal fun setCounters(writeCounter: Long, rebootCounter: Int) {
        aapsLogger.debug(LTag.PUMP, "YpsoPump ignoring legacy counter seeds ($writeCounter/$rebootCounter); durable session required")
    }

    val writeCounter: Long get() = session?.snapshot()?.write ?: 0L

    /**
     * Open the GATT link and authenticate, then STAY connected. Returns immediately; the connection
     * proceeds asynchronously (CONNECTING -> DISCOVERING -> CONNECTED once MD5 auth succeeds). The
     * AAPS command queue drives reads via [readStatus] while connected and calls [disconnect] when
     * idle — so we must not auto-disconnect here (that caused a 1s reconnect storm).
     */
    @SuppressLint("MissingPermission")
    fun connect(macAddress: String) {
        connect(macAddress, synchronized(opLock) { configuredConnection })
    }

    @SuppressLint("MissingPermission")
    private fun connect(macAddress: String, configured: YpsoProvisioningService.ConnectionSession?) {
        // The null branch is retained exclusively for the internal test/bench setSharedKey seam.
        val directTestSession = configured == null && configuredKey != null && configuredGeneration != null
        // A stale or mismatched immutable snapshot must have no side effects: recording here would write
        // an unconfigured condition into whatever session superseded it. Record only a genuinely absent
        // configured session.
        if (!directTestSession && (configured == null || configured.mac != macAddress || !provisioning.isCurrentConnection(configured))) {
            if (configured == null) provisioning.recordUnavailable(setOf(PumpSession.AvailabilityCause.UNCONFIGURED), operation = "connect")
            return
        }
        var independentlyObservedSerial: String? = null
        val device = runCatching {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            check(adapter != null && adapter.isEnabled) { "Bluetooth unavailable" }
            adapter.getRemoteDevice(macAddress).also {
                check(it.bondState == BluetoothDevice.BOND_BONDED) { "existing Bluetooth bond required" }
                val configuredSerial = configured?.serial ?: provisioning.connectionSession()?.serial
                if (configuredSerial != null && PumpIdentity.isSupportedDeviceName(it.name)) {
                    check(PumpIdentity.deviceNameMatches(configuredSerial, it.name)) { "Bonded pump name does not match configured serial" }
                    independentlyObservedSerial = configuredSerial
                }
            }
        }.getOrElse {
            disconnect()
            val cause = if (it.message == "Bonded pump name does not match configured serial")
                PumpSession.AvailabilityCause.IDENTITY_MISMATCH
            else PumpSession.AvailabilityCause.BOND_OR_PERMISSION
            reportConnectionFailure(configured, setOf(cause), operation = "connect", terminal = cause == PumpSession.AvailabilityCause.IDENTITY_MISMATCH)
            aapsLogger.error(LTag.PUMP, "YpsoPump connection unavailable: ${it.message}")
            return
        }
        synchronized(opLock) {
            if (!directTestSession && !provisioning.isCurrentConnection(checkNotNull(configured))) return
            if (pumpState.pumpAddress != macAddress) disconnect()
            if (pumpState.connectionState != ConnectionState.DISCONNECTED) return
            try {
                val owner = session ?: PumpSession(SessionJournal(context)).also { session = it }
                val key = configured?.key ?: checkNotNull(configuredKey)
                val generation = configured?.generation ?: checkNotNull(configuredGeneration)
                importDebugBaseline(owner, macAddress, key)
                sessionToken = owner.openGeneration(generation, macAddress.uppercase(java.util.Locale.ROOT), key)
            } catch (e: Exception) {
                pumpState.invalidateStatus()
                reportConnectionFailure(configured, setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN), operation = "session-open")
                aapsLogger.error(LTag.PUMP, "YpsoPump session unavailable: ${e.message}")
                return
            }
            bondedIdentitySerial = independentlyObservedSerial
            pumpState.observedIdentitySerial = independentlyObservedSerial.orEmpty()
            if (pumpState.pumpAddress != macAddress) pumpState.invalidateStatus()
            pumpState.connectionState = ConnectionState.CONNECTING
            queue.clear()
            current = null
            pumpState.pumpAddress = macAddress
            aapsLogger.info(LTag.PUMP, "YpsoPump connecting to bonded pump")
            if (!directTestSession && !provisioning.isCurrentConnection(checkNotNull(configured))) {
                session?.quiesce()
                sessionToken = null
                pumpState.connectionState = ConnectionState.DISCONNECTED
                return
            }
            val openedGatt = runCatching { device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE) }
                .getOrElse {
                    pumpState.connectionState = ConnectionState.DISCONNECTED
                    pumpState.invalidateStatus()
                    aapsLogger.error(LTag.PUMP, "YpsoPump connect failed: ${it.message}")
                    null
                }
            if (openedGatt == null) {
                pumpState.connectionState = ConnectionState.DISCONNECTED
                pumpState.invalidateStatus()
                reportConnectionFailure(configured, setOf(PumpSession.AvailabilityCause.TRANSPORT), operation = "connect-gatt")
            } else if (pumpState.connectionState != ConnectionState.DISCONNECTED) {
                bluetoothGatt = openedGatt
                armHandshakeTimeout(openedGatt, ConnectionState.CONNECTING)
            } else {
                runCatching { openedGatt.close() }
            }
        }
    }

    /** Read SYSTEM_STATUS over the already-open connection and update [YpsoPumpState]. No disconnect. */
    fun readStatus(onDone: (Boolean) -> Unit = {}): StatusReadAttempt {
        val attempt = StatusReadAttempt()
        val ownership = synchronized(opLock) {
            pumpState.invalidateStatus()
            ReadOwnership(bluetoothGatt, sessionToken?.generation, configuredAttemptId)
        }
        val originGatt = ownership.gatt
        attempt.onCancel = {
            synchronized(opLock) {
                if (originGatt != null && bluetoothGatt === originGatt)
                    fail(originGatt, "status read cancelled", cause = PumpSession.AvailabilityCause.TRANSPORT, ownership = ownership)
            }
            runCatching { onDone(false) }
        }
        // CommandReadStatus infers success from lastDataTime, so invalidate the previous sample before
        // every attempt. A failed current read must never inherit a recent successful timestamp or values.
        if (!isConnected || originGatt == null) {
            aapsLogger.warn(LTag.PUMP, "YpsoPump readStatus: not connected")
            if (attempt.tryComplete()) onDone(false)
            return attempt
        }
        readIdentity(originGatt) {
            if (!attempt.isActive) return@readIdentity
            // Diagnostic bolus capture stays behind the firmware + control-protocol gate:
            // unknown versions get raw diagnostics only, never parsed measurements.
            val eligible = hasCompatibleStatusProtocol()
            if (protocolCaptureEnabled && eligible && findChar(originGatt, CHAR_BOLUS_STATUS) != null)
                readBolusStatus { readStatusInternal(originGatt, ownership, attempt, onDone) }
            else readStatusInternal(originGatt, ownership, attempt, onDone)
        }
        return attempt
    }

    private fun readIdentity(gatt: BluetoothGatt, done: () -> Unit) {
        pumpState.firmwareVersion = ""
        pumpState.masterVersion = ""
        pumpState.supervisorVersion = ""
        pumpState.baseServiceVersion = ""
        pumpState.settingsServiceVersion = ""
        pumpState.historyServiceVersion = ""
        pumpState.controlServiceVersion = ""
        pumpState.observedIdentitySerial = bondedIdentitySerial.orEmpty()
        var finished = false
        val candidates = listOf(
            "serial" to "00002a25-0000-1000-8000-00805f9b34fb",
            "firmware" to "00002a26-0000-1000-8000-00805f9b34fb",
            "software" to "00002a28-0000-1000-8000-00805f9b34fb",
            "master" to "669a0c20-0008-969e-e211-fcbeb0147bc5",
            "supervisor" to "669a0c20-0008-969e-e211-fcbeb1147bc5",
            "base-service" to "669a0c20-0008-969e-e211-fcbee23b7bc5",
            "settings-service" to "669a0c20-0008-969e-e211-fcbee33b7bc5",
            "history-service" to "669a0c20-0008-969e-e211-fcbee43b7bc5",
            "control-service" to CHAR_CONTROL_VERSION.toString()
        )
        fun step(index: Int) {
            if (finished) return
            if (bluetoothGatt !== gatt || index == candidates.size) { finished = true; done(); return }
            val (name, address) = candidates[index]
            val uuid = UUID.fromString(address)
            if (findChar(gatt, uuid) == null) {
                aapsLogger.info(LTag.PUMP, "YpsoPump identity $name absent")
                step(index + 1)
                return
            }
            readOp(gatt, uuid) { owner, bytes, status ->
                if (finished) return@readOp
                if (bluetoothGatt !== gatt) { finished = true; done(); return@readOp }
                aapsLogger.info(LTag.PUMP, "YpsoPump identity $name status=$status")
                if (owner === gatt && status == BluetoothGatt.GATT_SUCCESS && bytes != null) {
                    if (name == "serial") pumpState.observedIdentitySerial = bytes.toString(Charsets.US_ASCII).trimEnd('\u0000')
                    // Service versions are dotted ASCII ("1.1\0"); only master/supervisor are firmware.
                    // Control is consumed by the status decoder, so accept only the independently
                    // observed canonical wire value. Other service versions remain diagnostic only.
                    val serviceVersion = if (name == "control-service") {
                        "1.3".takeIf { bytes.contentEquals(SUPPORTED_CONTROL_SERVICE_VERSION) }.orEmpty()
                    } else {
                        bytes.toString(Charsets.US_ASCII).trimEnd('\u0000')
                            .takeIf { it.matches(Regex("[0-9]+\\.[0-9]+")) }.orEmpty()
                    }
                    when (name) {
                        "base-service"     -> pumpState.baseServiceVersion = serviceVersion
                        "settings-service" -> pumpState.settingsServiceVersion = serviceVersion
                        "history-service"  -> pumpState.historyServiceVersion = serviceVersion
                        "control-service"  -> pumpState.controlServiceVersion = serviceVersion
                    }
                    val version = YpsoFirmwareVersion.fromWire(bytes)?.toString().orEmpty()
                    if (name == "master") {
                        pumpState.masterVersion = version
                        pumpState.firmwareVersion = version
                    }
                    if (name == "supervisor") pumpState.supervisorVersion = version
                }
                if (owner === gatt && status == BluetoothGatt.GATT_SUCCESS) step(index + 1) else {
                    finished = true
                    done()
                }
            }
        }
        step(0)
    }

    /** Explicit local bench capture; unavailable in non-debuggable installed artifacts. */
    private val protocolCaptureEnabled: Boolean
        get() = (context.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) ?: 0) != 0 &&
            ypsoPrefs.getBoolean("ypso_protocol_capture", false)

    /** Test seam around the explicit, debug-build-only protocol capture preference. */
    internal var diagnosticLoggingEnabled: () -> Boolean = { protocolCaptureEnabled }

    class StatusReadAttempt internal constructor() {
        private val active = AtomicBoolean(true)

        internal val isActive: Boolean get() = active.get()
        internal var onCancel: () -> Unit = {}

        internal fun tryComplete(): Boolean = active.compareAndSet(true, false)
        fun cancel(): Boolean {
            if (!active.compareAndSet(true, false)) return false
            onCancel()
            return true
        }
    }

    private data class ReadOwnership(val gatt: BluetoothGatt?, val generation: String?, val attemptId: String?)

    @SuppressLint("MissingPermission")
    fun disconnect(preserveStatus: Boolean = false) {
        val (gatt, failed) = synchronized(opLock) {
            val ownedGatt = bluetoothGatt
            bluetoothGatt = null
            session?.quiesce()
            sessionToken = null
            pumpState.connectionState = ConnectionState.DISCONNECTED
            if (!preserveStatus) pumpState.invalidateStatus()
            ownedGatt to drainPendingOperationsLocked()
        }
        // A deliberate local teardown is not a pump failure: suppress callback reports while draining.
        teardownReporting.incrementAndGet()
        try {
            failOperations(failed)
        } finally {
            teardownReporting.decrementAndGet()
        }
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
    }

    // ---- serial GATT op queue ----
    // Ops are enqueued from the AAPS queue-worker thread ([readStatus]) and completed from the BLE
    // binder/callback thread, so the queue state is guarded by [opLock].
    private class Op(
        val gatt: BluetoothGatt,
        val uuid: UUID,
        val action: (BluetoothGatt, Op) -> Unit,
        val onResult: (BluetoothGatt?, ByteArray?, Int) -> Unit
    )
    private val opLock = Any()
    // Depth of active teardown drains: the teardown already recorded the durable failure, so callbacks
    // forced out by the drain must not report a second one. A counter keeps nested/overlapping teardowns
    // from re-enabling reporting while another drain is still running.
    private val teardownReporting = java.util.concurrent.atomic.AtomicInteger()
    private val queue = ArrayDeque<Op>()
    private var current: Op? = null
    private var currentGatt: BluetoothGatt? = null
    private var currentTimeout: Runnable? = null
    // A dropped callback used to leave the active operation and multi-frame transaction latched forever.
    // Time each operation out so its result path tears down the transaction and connection cleanly.
    private val opHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    // Injectable scheduling seams keep timeout/callback races deterministic in local unit tests without
    // exposing the BLE queue itself. Production retains Android's main-looper scheduling.
    internal var scheduleOpTimeout: (Runnable, Long) -> Unit = { timeout, delay ->
        opHandler.postDelayed(timeout, delay)
    }
    internal var cancelOpTimeout: (Runnable) -> Unit = { timeout -> opHandler.removeCallbacks(timeout) }
    internal var sdkInt: Int = Build.VERSION.SDK_INT
    private var handshakeTimeout: Runnable? = null

    private fun armHandshakeTimeout(gatt: BluetoothGatt, phase: ConnectionState) {
        handshakeTimeout?.let(cancelOpTimeout)
        val timeout = Runnable {
            synchronized(opLock) {
                if (bluetoothGatt !== gatt || pumpState.connectionState != phase) return@Runnable
                fail(gatt, "$phase timed out", cause = PumpSession.AvailabilityCause.TRANSPORT)
            }
        }
        handshakeTimeout = timeout
        scheduleOpTimeout(timeout, OP_TIMEOUT_MS)
    }

    private fun enqueue(op: Op) { synchronized(opLock) { queue.addLast(op) }; pumpOps() }
    private fun pumpOps() {
        val start = synchronized(opLock) {
            if (current != null) return
            val op = queue.removeFirstOrNull() ?: return
            val gatt = bluetoothGatt
            if (gatt == null || gatt !== op.gatt) return@synchronized Triple(op, null, null)
            val timeout = Runnable {
                aapsLogger.error(LTag.PUMP, "YpsoPump: BLE op ${op.uuid} timed out after ${OP_TIMEOUT_MS}ms")
                complete(op, gatt, op.uuid, null, OP_TIMEOUT_STATUS)
            }
            current = op
            currentGatt = gatt
            currentTimeout = timeout
            Triple(op, gatt, timeout)
        } ?: return
        val (op, gatt, timeout) = start
        if (gatt == null || timeout == null) {
            runCatching { op.onResult(null, null, -1) }
                .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump operation callback threw: ${it.message}") }
            pumpOps()
            return
        }
        synchronized(opLock) {
            if (current !== op || currentGatt !== gatt || bluetoothGatt !== gatt) return@synchronized
            runCatching {
                scheduleOpTimeout(timeout, OP_TIMEOUT_MS)
                op.action(gatt, op)
            }
                .onFailure {
                    aapsLogger.error(LTag.PUMP, "YpsoPump operation dispatch threw: ${it.message}")
                    complete(op, gatt, op.uuid, null, -1)
                }
        }
    }
    private fun complete(op: Op, gatt: BluetoothGatt, uuid: UUID, value: ByteArray?, status: Int) {
        var timeout: Runnable? = null
        var callbackFailure: Throwable? = null
        synchronized(opLock) {
            if (current !== op || currentGatt !== gatt || bluetoothGatt !== gatt || op.uuid != uuid) return
            current = null
            currentGatt = null
            timeout = currentTimeout
            currentTimeout = null
            // Detach and deliver in one critical section. If the callback ran after releasing the lock,
            // a teardown could drain (and record) in between, and this already-detached callback would
            // then record the same failure again — or turn a deliberate local teardown into a failure.
            try {
                op.onResult(gatt, value, status)
            } catch (t: Throwable) {
                callbackFailure = t
            }
        }
        timeout?.let { runCatching { cancelOpTimeout(it) } }
        callbackFailure?.let { aapsLogger.error(LTag.PUMP, "YpsoPump operation callback threw: ${it.message}") }
        pumpOps()
    }

    private fun completeCurrent(gatt: BluetoothGatt, uuid: UUID, value: ByteArray?, status: Int) {
        // Android callbacks expose no operation ID. Same-UUID read chains are additionally protected by
        // frame sequence validation; same-UUID command write chains are blocked in the status-only build.
        val op = synchronized(opLock) {
            if (bluetoothGatt !== gatt || currentGatt !== gatt || current?.uuid != uuid) return
            current
        } ?: return
        complete(op, gatt, uuid, value, status)
    }

    @SuppressLint("MissingPermission")
    private fun readOp(gatt: BluetoothGatt, uuid: UUID, onResult: (BluetoothGatt?, ByteArray?, Int) -> Unit) =
        enqueue(Op(gatt, uuid, { g, op ->
            val characteristic = findChar(g, uuid)
            if (characteristic == null || !g.readCharacteristic(characteristic)) {
                complete(op, g, uuid, null, -1)
            }
        }, onResult))

    // The pump's EXTREAD characteristic is a single shared cursor, so only ONE multi-frame read may
    // be in flight at a time — overlapping reads interleave EXTREAD frames and corrupt both. This
    // guard rejects (rather than silently corrupting) an overlapping read; all internal flows chain
    // sequentially via callbacks.
    private var multiframeOwner: Any? = null
    private fun readMultiframe(
        uuid: UUID,
        expectedGatt: BluetoothGatt? = bluetoothGatt,
        failureOwner: ReadOwnership? = null,
        onFailure: () -> Unit = {},
        done: (BluetoothGatt, ByteArray) -> Unit
    ) {
        val token = Any()
        val originGatt = synchronized(opLock) {
            if (multiframeOwner != null || bluetoothGatt !== expectedGatt) null else expectedGatt?.also { multiframeOwner = token }
        }
        if (originGatt == null) {
            aapsLogger.error(LTag.PUMP, "YpsoPump: multi-frame read on $uuid rejected (disconnected or another read is active)")
            onFailure()
            return
        }
        val frames = ArrayList<ByteArray>()
        val active = AtomicBoolean(true)
        var totalFrames = 0
        fun finishTransaction(): Boolean {
            if (!active.compareAndSet(true, false)) return false
            synchronized(opLock) {
                if (multiframeOwner === token) multiframeOwner = null
            }
            return true
        }
        fun step(now: UUID, expectedFrame: Int): Unit = readOp(originGatt, now) { gatt, v, s ->
            if (diagnosticLoggingEnabled())
                aapsLogger.debug(LTag.PUMP, "YpsoPump frame[${frames.size}] from $now: status=$s ${v?.joinToString("") { "%02x".format(it) } ?: "null"}")
            val reportedTotal = v?.let { YpsoFraming.validateFrame(it, expectedFrame, totalFrames) }
            val invalidFrame = v != null && reportedTotal == null
            if (gatt !== originGatt || s != BluetoothGatt.GATT_SUCCESS || v == null || invalidFrame) {
                if (!finishTransaction()) return@readOp
                // Name 0x8C explicitly. Reported as a bare "status=140" it reads like a transient BLE
                // fault and invites hours of restarting things that cannot possibly help; it actually
                // means the shared key is gone and only a re-key will fix it.
                val message = if (s == ERR_NO_SHARED_KEY)
                    "Pump returned code 140 (0x8C) on $now; the configured key/session is probably no longer accepted and external re-provisioning is required."
                else if (invalidFrame)
                    "read $now returned an invalid frame; expected frame $expectedFrame${if (totalFrames == 0) "" else "/$totalFrames"}"
                else
                    "read $now failed (status=$s, got ${frames.size} frames)"
                fail(originGatt, message, cause = null)
                if (teardownReporting.get() == 0) {
                    val causes = if (s == ERR_NO_SHARED_KEY) setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED)
                    else setOf(PumpSession.AvailabilityCause.TRANSPORT)
                    val owner = failureOwner
                    if (owner != null) {
                        if (s == ERR_NO_SHARED_KEY) {
                            provisioning.failCandidateOrRecord(
                                owner.generation, owner.attemptId, causes, operation = now.toString(),
                                code = s, firmware = pumpState.masterVersion.takeIf(String::isNotBlank)
                            )
                        } else {
                            provisioning.recordCandidateOrUnavailable(
                                owner.generation, owner.attemptId, causes, operation = now.toString(),
                                code = s.takeIf { it >= 0 }, firmware = pumpState.masterVersion.takeIf(String::isNotBlank)
                            )
                        }
                    } else {
                        provisioning.recordUnavailable(
                            causes = causes, code = s.takeIf { it >= 0 }, operation = now.toString(),
                            firmware = pumpState.masterVersion.takeIf(String::isNotBlank)
                        )
                    }
                }
                onFailure()
                return@readOp
            }
            frames.add(v)
            if (totalFrames == 0) totalFrames = reportedTotal ?: 1
            if (frames.size < totalFrames) step(CHAR_EXTREAD, expectedFrame + 1) else {
                if (!finishTransaction()) return@readOp
                synchronized(opLock) {
                    if (bluetoothGatt === originGatt) done(originGatt, reassemble(frames)) else onFailure()
                }
            }
        }
        step(uuid, 1)
    }

    private fun reassemble(frames: List<ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        for (f in frames) if (f.size > 1) for (i in 1 until f.size) out.add(f[i])
        return out.toByteArray()
    }

    private fun findChar(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        val observedService = when (uuid) {
            CHAR_STATUS, CHAR_BOLUS_STATUS, CHAR_CONTROL_VERSION -> SERVICE_CONTROL
            CHAR_EXTREAD -> SERVICE_EXTREAD
            else         -> null
        }
        if (observedService != null) return g.getService(observedService)?.getCharacteristic(uuid)
        for (s in g.services) s.getCharacteristic(uuid)?.let { return it }
        return null
    }

    private fun hasCompatibleStatusProtocol(): Boolean =
        YpsoFirmwareVersion.parse(pumpState.masterVersion)?.meetsMinimum == true &&
            YpsoFirmwareVersion.parse(pumpState.supervisorVersion)?.meetsMinimum == true &&
            pumpState.controlServiceVersion == "1.3"

    private fun decryptOwned(payload: ByteArray): ByteArray = synchronized(opLock) {
        val owner = checkNotNull(session) { "No durable session" }
        val origin = checkNotNull(sessionToken) { "No session generation" }
        val transaction = owner.begin(origin)
        try {
            owner.decrypt(origin, transaction, payload, sessionCrypto,
                allowObservedReboot = hasCompatibleStatusProtocol())
        } catch (e: PumpSession.RebootAdoptedException) {
            disconnect()
            throw e
        } finally {
            owner.finish(origin, transaction)
        }
    }

    /** ADB migration input is private to the app and is never consumed by distributed artifacts. */
    private fun importDebugBaseline(owner: PumpSession, mac: String, key: ByteArray) {
        if (((context.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val input = java.io.File(context.filesDir, "ypso-read-baseline.json")
        if (!input.exists()) return
        val baseline = org.json.JSONObject(input.readText())
        require(baseline.getString("pump").equals(mac, ignoreCase = true)) { "Baseline pump mismatch" }
        require(baseline.getString("keyId") == PumpSession.fingerprint(key)) { "Baseline key mismatch" }
        owner.provisionReadBaseline(mac.uppercase(java.util.Locale.ROOT), key, baseline.getInt("reboot"), baseline.getLong("read"))
        check(input.delete()) { "Cannot remove consumed baseline" }
        aapsLogger.info(LTag.PUMP, "YpsoPump independently captured read baseline imported")
    }

    private fun authPassword(mac: String): ByteArray = YpsoAuthentication.password(mac)

    private fun readStatusInternal(originGatt: BluetoothGatt, ownership: ReadOwnership, attempt: StatusReadAttempt, onDone: (Boolean) -> Unit) {
        readMultiframe(CHAR_STATUS, expectedGatt = originGatt, failureOwner = ownership, onFailure = { if (attempt.tryComplete()) onDone(false) }) { gatt, frame ->
            var decodeFailure: Throwable? = null
            var publicationFailure: Throwable? = null
            var completionClaimed = false
            val decoded = synchronized(opLock) {
                if (bluetoothGatt !== gatt) {
                    completionClaimed = attempt.tryComplete()
                    return@synchronized null
                }
                if (!attempt.isActive) return@synchronized null
                val decoded = runCatching {
                    val body = decryptOwned(frame)
                    val payload = YpsoCrc.validatedPayload(body)
                        ?: throw SecurityException(
                            if (diagnosticLoggingEnabled()) "invalid status CRC raw=${body.toHex()}" else "invalid status CRC"
                        )
                    val status = StatusCommand().apply { decode(payload) }
                    if (!status.success) throw IllegalArgumentException(
                        if (diagnosticLoggingEnabled()) "status decode failed (${payload.size}B) raw=${payload.toHex()}"
                        else "status decode failed (${payload.size}B)"
                    )
                    if (!hasCompatibleStatusProtocol())
                        throw IllegalArgumentException("Unknown or unsupported pump firmware/control protocol")
                    status to payload
                }.onFailure { decodeFailure = it }.getOrNull()
                completionClaimed = attempt.tryComplete()
                if (!completionClaimed || decoded == null) return@synchronized null
                decoded
            }
            val success = decoded?.let { (status, payload) ->
                runCatching {
                    synchronized(opLock) {
                        check(bluetoothGatt === gatt && sessionToken?.generation == ownership.generation) { "Stale status callback" }
                    }
                    val promotedCandidate = provisioning.markVerified(
                        checkNotNull(ownership.generation), ownership.attemptId,
                        pumpState.observedIdentitySerial.takeIf(String::isNotBlank)
                    )
                    pumpState.publishStatus(
                        reservoirUnits = status.reservoirUnits,
                        batteryPercent = status.batteryPercent,
                        isSuspended = status.isSuspended,
                        activeTbrPercent = status.activeTbrPercent,
                        timestamp = System.currentTimeMillis(),
                        batteryBars = status.batteryBars
                    )
                    aapsLogger.info(LTag.PUMP, "YpsoPump encrypted status accepted")
                    if (diagnosticLoggingEnabled()) {
                        // Explicit local protocol capture only: decrypted values and bytes stay out of normal logs.
                        aapsLogger.debug(
                            LTag.PUMP,
                            "YpsoPump diagnostic status: reservoir=${status.reservoirUnits}U batteryBars=${status.batteryBars} " +
                                "basal=${status.basalRate}U/h mode=${status.deliveryMode}(${status.deliveryModeName}) " +
                                "suspended=${status.isSuspended} raw=${payload.toHex()}"
                        )
                    }
                    if (promotedCandidate) disconnect(preserveStatus = true)
                    true
                }.onFailure { publicationFailure = it }.isSuccess
            } ?: false
            if (!completionClaimed) return@readMultiframe
            // Identity failures record their own specific cause inside markVerified; only a decode
            // failure is an unattributed encrypted-status failure. Key rejection is terminal; other
            // decode failures keep the candidate with bounded retry.
            decodeFailure?.let {
                if (it is SessionCrypto.AuthenticationFailedException) {
                    provisioning.failCandidateOrRecord(
                        ownership.generation, ownership.attemptId,
                        setOf(PumpSession.AvailabilityCause.KEY_REJECTED),
                        operation = "encrypted-status", firmware = pumpState.masterVersion.takeIf(String::isNotBlank)
                    )
                } else {
                    provisioning.recordCandidateOrUnavailable(
                        ownership.generation, ownership.attemptId,
                        setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE),
                        operation = "encrypted-status", firmware = pumpState.masterVersion.takeIf(String::isNotBlank)
                    )
                }
            }
            val failure = decodeFailure ?: publicationFailure
            failure?.let {
                aapsLogger.error(LTag.PUMP, "YpsoPump status rejected: ${it.message}")
                fail(gatt, "status rejected: ${it.message}", cause = null)
            }
            // Stay connected — the AAPS command queue disconnects when idle.
            runCatching { onDone(success) }
                .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump status callback failed: ${it.message}") }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** Normal AAPS has no selector-write diagnostic; use the separate non-therapy bench artifact. */
    fun validateWriteTransport(onResult: (String) -> Unit) {
        onResult("write transport unavailable in status-only AAPS; use the dedicated non-therapy bench artifact")
    }

    /** Single-frame read (event count) — isolates KEY validity from multi-frame reliability. */
    fun readEventCount(onResult: (Int?) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { onResult(null); return }
        readMultiframe(CHAR_EVENT_COUNT, onFailure = { onResult(null) }) { _, fc ->
            val count = runCatching { app.aaps.pump.ypsopump.comm.YpsoGlb.find(decryptOwned(fc)) }.getOrElse {
                aapsLogger.error(LTag.PUMP, "YpsoPump event-count decrypt error: ${it.message}"); null
            }
            aapsLogger.debug(LTag.PUMP, "YpsoPump event-count read = $count (key ${if (count != null) "VALID" else "FAILED"})")
            onResult(count)
        }
    }

    /** Read CHAR_BOLUS_STATUS and parse the immediate-delivery block via [BolusCommand.decode]. */
    fun readBolusStatus(onResult: (BolusCommand?) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { onResult(null); return }
        readMultiframe(CHAR_BOLUS_STATUS, onFailure = { onResult(null) }) { _, f ->
            val cmd = runCatching {
                val body = decryptOwned(f)
                val p = YpsoCrc.validatedPayload(body) ?: throw SecurityException("invalid bolus-status CRC")
                if (diagnosticLoggingEnabled()) aapsLogger.debug(LTag.PUMP, "YpsoPump diagnostic bolus-status (${p.size}B): ${p.toHex()}")
                BolusCommand(0.0).apply { decode(p); require(success) { "invalid bolus-status layout" } }
            }.getOrNull()
            onResult(cmd)
        }
    }

    /** Status-only compatibility stub. History selection exists only in the separate bench app. */
    fun readLastFastBolusEvent(maxScan: Int = 4, onResult: (YpsoHistoryEntry?) -> Unit) {
        onResult(null)
    }

    /** Status-only compatibility stub; normal AAPS cannot deliver therapy. */
    fun deliverBolus(units: Double, durationMinutes: Int, immediateUnits: Double, onResult: (String) -> Unit) {
        onResult("bolus unavailable in status-only mode")
    }

    /** Status-only compatibility stub; no canary or therapy write is attempted. */
    fun testBolusCanary(units: Double, seedW: Long, onResult: (Boolean, String) -> Unit) =
        startBolus(units, seedW) { outcome, msg -> onResult(outcome == BolusStart.SENT, msg) }

    /**
     * How far a bolus attempt got. The caller needs this to decide how long to keep confirming: only
     * [NOT_SENT] is a *certain* no-op, and it is the state a stopped/empty pump lands in — knowing that
     * is what lets the bolus fail in seconds instead of polling a dead pump for five minutes.
     */
    enum class BolusStart {
        /** The bolus characteristic was written and the pump acked it. Confirm-by-read as usual. */
        SENT,

        /** We aborted BEFORE writing the bolus characteristic — the pump cannot have delivered. */
        NOT_SENT,

        /** The bolus characteristic WAS written but the ack didn't come back clean. The pump may have
         *  delivered; the caller MUST confirm by reading the pump's own bolus status/history. */
        UNCERTAIN
    }

    /** Status-only compatibility stub with a typed certain-not-sent result. */
    fun startBolus(units: Double, seedW: Long, onResult: (BolusStart, String) -> Unit) {
        onResult(BolusStart.NOT_SENT, "bolus unavailable in status-only mode")
    }

    /**
     * Cancel a running bolus: canary-lock the write counter (same benign event-index gate as the bolus),
     * then write the all-zero START_STOP payload EXACTLY ONCE. Idempotent-ish (cancelling an already-finished
     * bolus is harmless), so — unlike delivery — a dropped ack here is not dangerous. [onResult]=(sent, msg).
     */
    fun cancelBolus(seedW: Long, extended: Boolean, onResult: (Boolean, String) -> Unit) {
        onResult(false, "bolus cancellation unavailable in status-only mode")
    }

    /**
     * SAFE TBR (production + test) via the same canary as the bolus: lock the write counter on the BENIGN
     * event-index char, then write the TBR command EXACTLY ONCE at the confirmed next counter. Aborts
     * with no TBR write if the canary can't be confirmed. [percent] 0=suspend, 100=normal; [durationMinutes]
     * MUST be a 15-min step (15/30/…). Counter persisted after each accepted write. [onResult]=(accepted,msg).
     */
    fun testTbrCanary(percent: Int, durationMinutes: Int, seedW: Long, onResult: (Boolean, String) -> Unit) {
        onResult(false, "TBR unavailable in status-only mode")
    }

    @SuppressLint("MissingPermission")
    internal val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED    -> {
                    val failure = synchronized(opLock) {
                        if (!ownsGattLocked(g)) return
                        if (pumpState.connectionState != ConnectionState.CONNECTING) return
                        if (status != BluetoothGatt.GATT_SUCCESS) return@synchronized "connection failed ($status)"
                        pumpState.connectionState = ConnectionState.DISCOVERING
                        armHandshakeTimeout(g, ConnectionState.DISCOVERING)
                        runCatching { g.discoverServices() }
                            .fold(
                                onSuccess = { dispatched -> if (dispatched) null else "service discovery could not be started" },
                                onFailure = { "service discovery dispatch failed: ${it.message}" }
                            )
                    }
                    failure?.let { fail(g, it, cause = PumpSession.AvailabilityCause.TRANSPORT) }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val (failed, ownership) = synchronized(opLock) {
                        if (!ownsGattLocked(g)) return
                        val owned = ReadOwnership(g, sessionToken?.generation, configuredAttemptId)
                        bluetoothGatt = null
                        pumpState.connectionState = ConnectionState.DISCONNECTED
                        pumpState.invalidateStatus()
                        drainPendingOperationsLocked() to owned
                    }
                    teardownReporting.incrementAndGet()
                    try {
                        failOperations(failed)
                    } finally {
                        teardownReporting.decrementAndGet()
                    }
                    // Remote teardown records exactly one transport failure; drained callbacks are
                    // suppressed by the teardown marker so a cancellation/disconnect cannot count twice.
                    provisioning.recordCandidateOrUnavailable(
                        ownership.generation, ownership.attemptId,
                        setOf(PumpSession.AvailabilityCause.TRANSPORT), operation = "gatt-disconnected"
                    )
                    runCatching { g.close() }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            var cause: PumpSession.AvailabilityCause? = PumpSession.AvailabilityCause.TRANSPORT
            val failure = runCatching { synchronized(opLock) {
                if (!ownsGattLocked(g)) return
                if (pumpState.connectionState != ConnectionState.DISCOVERING) return
                if (status != BluetoothGatt.GATT_SUCCESS) return@synchronized "service discovery failed ($status)"
                g.services
                    .mapNotNull { service ->
                        val customCharacteristics = service.characteristics
                            .map { it.uuid }
                            .filter { it.toString().startsWith("669a0c20", ignoreCase = true) }
                        customCharacteristics.takeIf { it.isNotEmpty() }?.let { service.uuid to it }
                    }
                    .forEach { (serviceUuid, characteristicUuids) ->
                        aapsLogger.info(LTag.PUMP, "YpsoPump discovered service $serviceUuid custom characteristics=$characteristicUuids")
                    }
                val auth = findChar(g, CHAR_AUTH) ?: return@synchronized "AUTH characteristic not found"
                if (!YpsoWritePolicy.allows(YpsoRemoteWrite.AUTHENTICATION, YpsoArtifactPolicy.STATUS_ONLY)) {
                    cause = null
                    return@synchronized "authentication write blocked by safety policy"
                }
                pumpState.connectionState = ConnectionState.READY
                armHandshakeTimeout(g, ConnectionState.READY)
                aapsLogger.info(LTag.PUMP, "YpsoPump connected; writing MD5 auth")
                if (!writeCharacteristic(g, auth, authPassword(pumpState.pumpAddress), YpsoRemoteWrite.AUTHENTICATION)) {
                    return@synchronized "auth write could not be dispatched"
                }
                null
            } }.getOrElse { "authentication dispatch failed: ${it.message}" }
            failure?.let { fail(g, it, cause = cause) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (ch.uuid == CHAR_AUTH) {
                var ownership: ReadOwnership? = null
                val failure = synchronized(opLock) {
                    if (!ownsGattLocked(g)) return
                    ownership = ReadOwnership(g, sessionToken?.generation ?: configuredGeneration, configuredAttemptId)
                    if (pumpState.connectionState != ConnectionState.READY) return
                    aapsLogger.debug(LTag.PUMP, "auth write status=$status")
                    if (status != BluetoothGatt.GATT_SUCCESS) return@synchronized "auth write failed ($status)"
                    // The MD5 auth characteristic is the sole remote write compiled into normal AAPS.
                    // Control notifications and selector writes exist only in the separate bench app.
                    aapsLogger.info(LTag.PUMP, "YpsoPump authenticated in read-only mode; control notifications disabled")
                    markConnected(controlNotificationsEnabled = false)
                    null
                }
                failure?.let {
                    if (status == ERR_NO_SHARED_KEY) {
                        fail(
                            g, it, cause = PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED,
                            code = ERR_NO_SHARED_KEY, operation = CHAR_AUTH.toString(), ownership = ownership
                        )
                    } else {
                        fail(
                            g, it, cause = PumpSession.AvailabilityCause.AUTHENTICATION,
                            code = status.takeIf { value -> value >= 0 }, ownership = ownership
                        )
                    }
                }
            } else completeCurrent(g, ch.uuid, null, status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            synchronized(opLock) {
                if (!ownsGattLocked(g)) return
                aapsLogger.debug(LTag.PUMP, "YpsoPump notify ${ch.uuid}: ${value.joinToString("") { "%02x".format(it) }}")
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            completeCurrent(g, ch.uuid, value.copyOf(), status)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            completeCurrent(g, ch.uuid, ch.value?.copyOf(), status)
        }
    }

    private fun ownsGattLocked(g: BluetoothGatt): Boolean {
        if (g === bluetoothGatt) return true
        aapsLogger.warn(LTag.PUMP, "YpsoPump ignored callback from stale GATT instance")
        return false
    }

    /**
     * Route a connection failure to the exact verification attempt when one is configured. A stale
     * snapshot records nothing: it must not mutate whatever session superseded it. Identity failures
     * are terminal for the attempt; transport/session-open failures keep it for bounded retry.
     */
    private fun reportConnectionFailure(
        configured: YpsoProvisioningService.ConnectionSession?,
        causes: Set<PumpSession.AvailabilityCause>,
        operation: String,
        code: Int? = null,
        terminal: Boolean = false
    ) {
        val firmware = pumpState.masterVersion.takeIf(String::isNotBlank)
        if (configured == null) {
            provisioning.recordUnavailable(causes, code = code, operation = operation, firmware = firmware)
            return
        }
        if (terminal) {
            provisioning.failCandidateOrRecord(configured.generation, configured.attemptId, causes, operation, firmware = firmware, code = code)
        } else {
            provisioning.recordCandidateOrUnavailable(configured.generation, configured.attemptId, causes, operation, firmware = firmware, code = code)
        }
    }

    /**
     * Tear down a connection and, when [cause] is non-null, record exactly one durable availability
     * failure against the connection ownership captured with the teardown. [ownership] may be passed
     * when the caller captured it before the failure could race a reconfigure.
     */
    @SuppressLint("MissingPermission")
    private fun fail(
        g: BluetoothGatt,
        message: String,
        cause: PumpSession.AvailabilityCause? = null,
        code: Int? = null,
        operation: String = "ble",
        ownership: ReadOwnership? = null
    ) {
        val (failed, owned) = synchronized(opLock) {
            if (bluetoothGatt !== g) return
            val captured = ownership ?: ReadOwnership(g, sessionToken?.generation ?: configuredGeneration, configuredAttemptId)
            bluetoothGatt = null
            pumpState.connectionState = ConnectionState.DISCONNECTED
            pumpState.invalidateStatus()
            drainPendingOperationsLocked() to captured
        }
        aapsLogger.error(LTag.PUMP, "YpsoPump: $message")
        if (cause != null) {
            val terminal = cause == PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED
            runCatching {
                if (terminal) {
                    provisioning.failCandidateOrRecord(
                        owned.generation, owned.attemptId, setOf(cause), operation = operation,
                        firmware = pumpState.masterVersion.takeIf(String::isNotBlank), code = code
                    )
                } else {
                    provisioning.recordCandidateOrUnavailable(
                        owned.generation, owned.attemptId, setOf(cause), operation = operation,
                        firmware = pumpState.masterVersion.takeIf(String::isNotBlank), code = code
                    )
                }
            }
        }
        teardownReporting.incrementAndGet()
        try {
            failOperations(failed)
        } finally {
            teardownReporting.decrementAndGet()
        }
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private fun drainPendingOperationsLocked(): List<Op> {
        session?.quiesce()
        sessionToken = null
        handshakeTimeout?.let { runCatching { cancelOpTimeout(it) } }
        handshakeTimeout = null
        val operations = buildList {
            current?.let(::add)
            addAll(queue)
        }
        current = null
        currentGatt = null
        currentTimeout?.let { runCatching { cancelOpTimeout(it) } }
        currentTimeout = null
        queue.clear()
        multiframeOwner = null
        return operations
    }

    private fun failOperations(operations: List<Op>) = operations.forEach { op ->
        runCatching { op.onResult(null, null, -1) }
            .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump failed-operation callback threw: ${it.message}") }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "NewApi") // sdkInt is the injectable Build.VERSION.SDK_INT adapter seam.
    @YpsoGuardedWrite
    internal fun writeCharacteristic(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        remoteWrite: YpsoRemoteWrite
    ): Boolean {
        val authorized = synchronized(opLock) {
            bluetoothGatt === g && YpsoWritePolicy.allowsCharacteristic(
                YpsoArtifactPolicy.STATUS_ONLY, remoteWrite, characteristic.uuid, value,
                runCatching { authPassword(pumpState.pumpAddress) }.getOrDefault(byteArrayOf()),
                pumpState.connectionState == ConnectionState.READY
            )
        }
        if (!authorized) {
            aapsLogger.error(LTag.PUMP, "YpsoPump READ_ONLY_MODE blocked $remoteWrite write to ${characteristic.uuid}")
            return false
        }
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = value
            g.writeCharacteristic(characteristic)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "NewApi") // Same runtime SDK guard as characteristic dispatch.
    @YpsoGuardedWrite
    internal fun writeDescriptor(
        g: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
        remoteWrite: YpsoRemoteWrite
    ): Boolean {
        // AUTH is a characteristic, never a descriptor. No descriptor destination is authorized
        // in this artifact, including a caller deliberately labelling its payload AUTHENTICATION.
        if (!YpsoWritePolicy.allowsDescriptor(
                YpsoArtifactPolicy.STATUS_ONLY,
                remoteWrite,
                descriptor.characteristic?.uuid,
                descriptor.uuid,
                value
            )) {
            aapsLogger.error(LTag.PUMP, "YpsoPump READ_ONLY_MODE blocked $remoteWrite descriptor write")
            return false
        }
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            g.writeDescriptor(descriptor)
        }
    }

    private fun markConnected(controlNotificationsEnabled: Boolean) {
        handshakeTimeout?.let(cancelOpTimeout)
        handshakeTimeout = null
        pumpState.connectionState = ConnectionState.CONNECTED
        aapsLogger.info(LTag.PUMP, "YpsoPump ready (authenticated, control notifications enabled=$controlNotificationsEnabled)")
    }

}

/**
 * A parsed pump event-history entry (17 bytes, tech-doc §10.6). For fast-bolus events (types 1/2/3)
 * [v1] is the units in hundredths (delivered for completed/cancelled; requested for started) and, for
 * a cancelled bolus, [v2] is the requested amount. [timestamp] is pump-clock Unix seconds.
 */
data class YpsoHistoryEntry(
    val timestamp: Long,
    val eventType: Int,
    val v1: Int,
    val v2: Int,
    val v3: Int,
    val sequence: Long,
    val index: Int
) {
    /** Units in v1, converted from hundredths. */
    val v1Units: Double get() = v1 / 100.0
    /** Units in v2 (requested, for a cancelled bolus), converted from hundredths. */
    val v2Units: Double get() = v2 / 100.0
}
