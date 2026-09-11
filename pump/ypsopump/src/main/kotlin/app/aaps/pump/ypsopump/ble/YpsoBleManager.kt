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
import app.aaps.pump.ypsopump.YpsoPumpConst
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.comm.commands.StatusCommand
import app.aaps.pump.ypsopump.comm.commands.TbrCommand
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionJournal
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.data.YpsoFirmwareVersion
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.provisioning.PumpIdentity
import java.security.MessageDigest
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
        private const val READ_ONLY_BLOCKED_STATUS = -3
        private val SUPPORTED_CONTROL_SERVICE_VERSION = "1.3\u0000".toByteArray(Charsets.US_ASCII)
        private val SERVICE_CONTROL: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0000")
        private val SERVICE_EXTREAD: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0002")
        private val CHAR_AUTH: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb2147bc5")
        private val CHAR_STATUS: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee48b7bc5")
        private val CHAR_EXTREAD: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff000000ff")
        // History (events) — used for the zero-therapy write-transport validation.
        private val CHAR_EVENT_COUNT: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecb3b7bc5")
        private val CHAR_EVENT_INDEX: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecc3b7bc5")
        private val CHAR_EVENT_VALUE: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecd3b7bc5")
        // Provisional control UUIDs. Therapy remains blocked pending independent target validation.
        private val CHAR_BOLUS_START_STOP: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee18b7bc5")
        private val CHAR_BOLUS_STATUS: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee28b7bc5")
        private val CHAR_TBR_START_STOP: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee38b7bc5")
        private val CHAR_CONTROL_VERSION: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee08b7bc5")
        // Provisional control-notification UUID. Non-auth write transport is unsupported in this artifact.
        private val CHAR_CTRL_NOTIFY: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee58b7bc5")
        private val AUTH_SALT = byteArrayOf(
            0x4F, 0xC2.toByte(), 0x45, 0x4D, 0x9B.toByte(), 0x81.toByte(), 0x59, 0xA4.toByte(), 0x93.toByte(), 0xBB.toByte()
        )
        // ATT application-error 0x8A the pump returns on a rejected WRITE — a GENERIC "command invalid".
        // On-device testing disproved both earlier theories: it is NOT a write-counter mismatch (every
        // counter probed gave 0x8A) and NOT a missing notification subscription (writes still got 0x8A
        // with CHAR_CTRL_NOTIFY enabled). The actual cause was a MALFORMED command — a CRC wrongly
        // appended to the 8-byte GLB index command (fixed: index writes now send bare glbEncode). Whether
        // a wrong write-counter also surfaces as 0x8A vs a distinct code is still TBD on the pump.
        private const val ERR_WRITE_REJECTED = 138
        // App-error 0x8B (139) = the write counter is BEHIND the pump's (the pump advanced on a write whose
        // BLE ack we never saw — a dropped Write-Response or a transient disconnect — so our persisted
        // counter is off by >=1 and the pump requires strictly-greater). Recover by scanning the counter
        // FORWARD (benign zero-therapy canary) until accepted; see [COUNTER_RESYNC_SCAN].
        private const val ERR_COUNTER_BEHIND = 139
        // App-error 0x8C (140) is documented as NO_SHARED_KEY / key exchange required. It was observed
        // after prolonged access on V05.00.52, but the exact invalidating event and lifetime remain
        // unresolved. Treat it as suspected re-key/session loss and preserve target evidence rather than
        // claiming a fixed 28-day expiry.
        private const val ERR_NO_SHARED_KEY = 140
        // How far to scan the write counter forward when it is behind (each step = one benign canary write).
        // Desync is normally +1/+2; a wide-ish bound covers multiple lost acks without unbounded runaway.
        private const val COUNTER_RESYNC_SCAN = 32
        // App-error 0x86 (134) on a TBR write = the pump already has an ACTIVE temp basal and refuses to
        // START a new one until the current one is STOPped (CHAR_TBR_START_STOP is a start/stop char; a
        // 0%/suspend TBR triggers this too). Recover by cancelling (100%/0) then re-sending. [confirmed
        // on-device 2026-07-02: 333% and 0% both rejected 0x86 while a prior 0% TBR was active]
        private const val ERR_TBR_ACTIVE = 134

        // Event-history entry types (tech-doc §10.6). Fast-bolus events carry the units in v1 (hundredths U);
        // a CANCELLED event additionally carries the requested amount in v2. Used by [readLastFastBolusEvent]
        // to reconcile a delivered dose against the pump's OWN record when the live confirm-by-read is lost.
        const val EVT_FAST_BOLUS_STARTED = 1
        const val EVT_FAST_BOLUS_COMPLETED = 2
        const val EVT_FAST_BOLUS_CANCELLED = 3
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
    private fun persistWriteCounter() = Unit // Legacy gated callers cannot establish counter certainty.

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
        failOperations(failed)
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
        val timeout = synchronized(opLock) {
            if (current !== op || currentGatt !== gatt || bluetoothGatt !== gatt || op.uuid != uuid) return
            current = null
            currentGatt = null
            currentTimeout.also { currentTimeout = null }
        }
        timeout?.let { runCatching { cancelOpTimeout(it) } }
        runCatching { synchronized(opLock) {
            if (bluetoothGatt === gatt) op.onResult(gatt, value, status) else op.onResult(null, null, -1)
        } }
            .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump operation callback threw: ${it.message}") }
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

    // ---- write transport (proven on real hardware via the history-index write) ----
    @SuppressLint("MissingPermission")
    private fun writeOp(uuid: UUID, value: ByteArray, onResult: (ByteArray?, Int) -> Unit) {
        if (!YpsoWritePolicy.allows(YpsoRemoteWrite.COMMAND_CHARACTERISTIC)) {
            aapsLogger.error(LTag.PUMP, "YpsoPump READ_ONLY_MODE blocked characteristic write to $uuid")
            onResult(null, READ_ONLY_BLOCKED_STATUS)
            return
        }
        val gatt = synchronized(opLock) { bluetoothGatt }
        if (gatt == null) {
            onResult(null, -1)
            return
        }
        enqueue(Op(gatt, uuid, { g, op ->
            val characteristic = findChar(g, uuid)
            if (characteristic == null || !writeCharacteristic(g, characteristic, value, YpsoRemoteWrite.COMMAND_CHARACTERISTIC)) {
                complete(op, g, uuid, null, -1)
            }
        }, { _, value, status -> onResult(value, status) }))
    }

    /** Write every frame of [payload]; report the LAST frame's status (0 = ok, 138 = counter mismatch). */
    private fun writeFrames(uuid: UUID, payload: ByteArray, onComplete: (Int) -> Unit) {
        val frames = YpsoFraming.chunkPayload(payload)
        fun step(i: Int) {
            if (i >= frames.size) { onComplete(0); return }
            writeOp(uuid, frames[i]) { _, s -> if (s != BluetoothGatt.GATT_SUCCESS) onComplete(s) else step(i + 1) }
        }
        step(0)
    }

    /**
     * Encrypt [command] (already CRC-wrapped or complement-protected by the caller) and write it ONCE at
     * the cryptor's current (pre-incremented) writeCounter. The pump's write check is forward-gap tolerant,
     * so we must NOT retry/scan on rejection — a higher counter would be accepted but corrupt the shared
     * counter space and break mylife. The caller is responsible for having locked the exact write counter
     * first (e.g. via [establishCounter]). [onResult] true on accept, false on reject.
     */
    private fun writeEncrypted(uuid: UUID, command: ByteArray, onResult: (Boolean) -> Unit) {
        if (!YpsoWritePolicy.allows(YpsoRemoteWrite.COMMAND_CHARACTERISTIC)) {
            aapsLogger.error(LTag.PUMP, "YpsoPump READ_ONLY_MODE blocked encrypted write to $uuid")
            onResult(false)
            return
        }
        if (!isConnected || bluetoothGatt == null) { aapsLogger.warn(LTag.PUMP, "YpsoPump writeEncrypted: not connected"); onResult(false); return }
        // Transport must adopt the reservation journal and measured acceptance contract before use.
        onResult(false)
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

    private fun authPassword(mac: String): ByteArray {
        val macBytes = mac.replace(":", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.getInstance("MD5").digest(macBytes + AUTH_SALT)
    }

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

    // ---- GLB safe variable: value(u32 LE) || ~value(u32 LE) ----
    private fun glbEncode(value: Int): ByteArray {
        val b = ByteArray(8)
        le32(value, b, 0); le32(value.inv(), b, 4); return b
    }

    private fun glbFind(data: ByteArray): Int? {
        if (data.size < 8) return null
        for (s in 0..data.size - 8) {
            val v = u32le(data, s); val c = u32le(data, s + 4)
            if (v == c.inv()) return v
        }
        return null
    }

    private fun le32(v: Int, b: ByteArray, o: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
    }
    private fun u32le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun u16le(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /**
     * ZERO-THERAPY validation of the encrypted WRITE path against the real pump: read the event
     * history COUNT, write the event INDEX of the newest entry (a read-selection write that does NOT
     * change therapy), then read that entry back. Proves encrypt + multiframe-write + counter
     * auto-sync work end-to-end before any dosing command is built. Requires counters seeded.
     */
    fun validateWriteTransport(onResult: (String) -> Unit) {
        if (YpsoPumpConst.READ_ONLY_MODE) { onResult("write transport unavailable in status-only mode"); return }
        if (!isConnected || bluetoothGatt == null) { onResult("not connected"); return }
        readMultiframe(CHAR_EVENT_COUNT) { _, fc ->
            val count = runCatching { glbFind(decryptOwned(fc)) }.getOrNull()
            aapsLogger.debug(LTag.PUMP, "YpsoPump event history count = $count (pump readCounter=$readCounter)")
            if (count == null || count <= 0) { onResult("count read failed ($count)"); return@readMultiframe }
            if (writeCounter <= 0) {
                onResult("no write counter seeded — set CAPTURED_WRITE_COUNTER to the pump's CURRENT write counter")
                return@readMultiframe
            }
            // Unsupported diagnostic path retained behind compile-time gates. Counter behavior and safe
            // ownership are not established here.
            val base = writeCounter
            // Selector encoding and transport are not qualified for use.
            val payload = glbEncode(count - 1)
            aapsLogger.debug(LTag.PUMP, "YpsoPump write-validate: single index write at writeCounter=${base + 1} (readCounter=$readCounter is NOT used for writes)")
            writeOnceAt(CHAR_EVENT_INDEX, payload, base + 1) { st ->
                when (st) {
                    BluetoothGatt.GATT_SUCCESS -> onResult("WRITE ACCEPTED at writeCounter=${base + 1}")
                    ERR_WRITE_REJECTED         -> onResult("write rejected (138) at writeCounter=${base + 1} — seed stale; reseed CAPTURED_WRITE_COUNTER from the pump's current write counter (NOT scanning, would corrupt the counter)")
                    else                       -> onResult("write failed status=$st at writeCounter=${base + 1}")
                }
            }
        }
    }

    /**
     * Lock the write counter on the SAFE history-index characteristic (zero-therapy) with ONE write at
     * writeCounter+1, so the dosing write that follows pre-increments to exactly +2 and hits the bolus
     * char once. The write counter MUST be seeded accurately (the pump's current write counter): the
     * forward-gap-tolerant check means a too-high value would be accepted but corrupt the shared counter
     * space and break mylife — so on reject we ABORT rather than scan. [see validateWriteTransport]
     */
    private fun establishCounter(onResult: (Boolean) -> Unit) {
        readMultiframe(CHAR_EVENT_COUNT) { _, fc ->
            val count = runCatching { glbFind(decryptOwned(fc)) }.getOrNull()
            if (count == null || count <= 0) { aapsLogger.error(LTag.PUMP, "YpsoPump establishCounter: count read failed ($count)"); onResult(false); return@readMultiframe }
            if (writeCounter <= 0) { aapsLogger.error(LTag.PUMP, "YpsoPump establishCounter: no write counter seeded"); onResult(false); return@readMultiframe }
            val base = writeCounter
            aapsLogger.debug(LTag.PUMP, "YpsoPump establishCounter: single index write at writeCounter=${base + 1}")
            writeOnceAt(CHAR_EVENT_INDEX, glbEncode(count - 1), base + 1) { st ->   // GLB command: no CRC
                if (st == BluetoothGatt.GATT_SUCCESS) onResult(true)                // cryptor.writeCounter now = base+1
                else { aapsLogger.error(LTag.PUMP, "YpsoPump establishCounter rejected (status=$st) — reseed write counter; NOT scanning"); onResult(false) }
            }
        }
    }

    /** Single-frame read (event count) — isolates KEY validity from multi-frame reliability. */
    fun readEventCount(onResult: (Int?) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { onResult(null); return }
        readMultiframe(CHAR_EVENT_COUNT, onFailure = { onResult(null) }) { _, fc ->
            val count = runCatching { glbFind(decryptOwned(fc)) }.getOrElse {
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

    /**
     * RECONCILIATION — the pump's OWN record of the most recent FAST bolus (history event types
     * 1=started / 2=completed / 3=cancelled; delivered units in v1, hundredths U). This is ground
     * truth for recovering a bolus whose live confirm-by-read was lost (dropped ack / BLE blip):
     * the pump advanced and delivered, but AAPS never saw the confirmation.
     *
     * Reads the event COUNT, then scans at most [maxScan] NEWEST entries (each entry = one BENIGN
     * event-index write, forward-only at writeCounter+1 exactly like the dosing canary, + one value
     * read), returning the newest COMPLETED/CANCELLED fast-bolus entry. Stops at the first match.
     *
     * SAFETY: purely ADDITIVE and read-only w.r.t. therapy — it only ever *reads* the pump's history.
     * On ANY failure (not connected, no seeded counter, count read fails, a rejected index write, a
     * decrypt/parse error, or no fast-bolus entry in range) it returns null and the caller keeps its
     * existing behaviour. It NEVER scans the write counter (a rejected index write aborts), so it can
     * neither double-dose nor corrupt the shared counter.
     */
    fun readLastFastBolusEvent(maxScan: Int = 4, onResult: (YpsoHistoryEntry?) -> Unit) {
        if (YpsoPumpConst.READ_ONLY_MODE) { onResult(null); return }
        if (!isConnected || bluetoothGatt == null) { onResult(null); return }
        if (writeCounter <= 0) { aapsLogger.warn(LTag.PUMP, "YpsoPump reconcile: no write counter seeded"); onResult(null); return }
        readMultiframe(CHAR_EVENT_COUNT) { _, fc ->
            val count = runCatching { glbFind(decryptOwned(fc)) }.getOrNull()
            if (count == null || count <= 0) { aapsLogger.warn(LTag.PUMP, "YpsoPump reconcile: event count read failed ($count)"); onResult(null); return@readMultiframe }
            val newest = count - 1
            val floor = maxOf(0, count - maxScan)
            fun scan(idx: Int) {
                if (idx < floor) { aapsLogger.debug(LTag.PUMP, "YpsoPump reconcile: no fast-bolus event in newest ${count - floor} entries"); onResult(null); return }
                // Select entry [idx] with ONE benign forward-only index write at writeCounter+1 (the pump's
                // check is forward-gap tolerant; persist on accept so AAPS stays in sync). Reject → abort.
                val c = writeCounter + 1
                writeOnceAt(CHAR_EVENT_INDEX, glbEncode(idx), c) { st ->
                    if (st != BluetoothGatt.GATT_SUCCESS) { aapsLogger.warn(LTag.PUMP, "YpsoPump reconcile: index write @$c rejected (status=$st) — abort"); onResult(null); return@writeOnceAt }
                    persistWriteCounter()
                    readMultiframe(CHAR_EVENT_VALUE) { _, vf ->
                        val entry = runCatching {
                            val body = decryptOwned(vf)
                            val p = if (YpsoCrc.isValid(body)) body.copyOfRange(0, body.size - 2) else body
                            parseHistoryEntry(p)
                        }.getOrNull()
                        aapsLogger.debug(LTag.PUMP, "YpsoPump reconcile: entry idx=$idx type=${entry?.eventType} v1=${entry?.v1} v2=${entry?.v2} ts=${entry?.timestamp}")
                        if (entry != null && (entry.eventType == EVT_FAST_BOLUS_COMPLETED || entry.eventType == EVT_FAST_BOLUS_CANCELLED)) onResult(entry)
                        else scan(idx - 1)
                    }
                }
            }
            scan(newest)
        }
    }

    /** Parse a 17-byte history entry (tech-doc §10.6). Returns null if too short. */
    private fun parseHistoryEntry(p: ByteArray): YpsoHistoryEntry? {
        if (p.size < 17) return null
        return YpsoHistoryEntry(
            timestamp = u32le(p, 0).toLong() and 0xFFFFFFFFL,
            eventType = p[4].toInt() and 0xFF,
            v1 = u16le(p, 5), v2 = u16le(p, 7), v3 = u16le(p, 9),
            sequence = u32le(p, 11).toLong() and 0xFFFFFFFFL,
            index = u16le(p, 15)
        )
    }

    /**
     * SAFETY-CRITICAL — deliver a real bolus. Establishes the write counter on the safe index char
     * first (so the bolus char is written EXACTLY ONCE, never scanned), sends one encrypted
     * START_STOP_BOLUS, then reads the bolus status to confirm. Only ever called behind an explicit
     * test flag + capture-verify + user consent. [units] standard if [durationMinutes]==0, else
     * extended over that duration with [immediateUnits] up front.
     */
    fun deliverBolus(units: Double, durationMinutes: Int, immediateUnits: Double, onResult: (String) -> Unit) {
        if (YpsoPumpConst.READ_ONLY_MODE) { onResult("bolus unavailable in status-only mode"); return }
        if (!isConnected || bluetoothGatt == null) { onResult("not connected"); return }
        val cmd = BolusCommand(units, durationMinutes, immediateUnits)
        val payload = YpsoCrc.appendCrc(cmd.encode())
        aapsLogger.debug(LTag.PUMP, "YpsoPump BOLUS request ${units}U dur=$durationMinutes imm=$immediateUnits raw=${cmd.encode().joinToString("") { "%02x".format(it) }}")
        establishCounter { ok ->
            if (!ok) { onResult("counter discovery failed — BOLUS NOT SENT"); return@establishCounter }
            aapsLogger.debug(LTag.PUMP, "YpsoPump SENDING BOLUS to control char at wc=${writeCounter + 1}")
            writeEncrypted(CHAR_BOLUS_START_STOP, payload) { accepted ->
                if (!accepted) { onResult("bolus write rejected"); return@writeEncrypted }
                readBolusStatus { st ->
                    pumpState.lastConnectionTime = System.currentTimeMillis()
                    onResult("BOLUS ACCEPTED status=${st?.bolusStatusCode} injected=${st?.deliveredUnits}U total=${st?.totalProgrammedUnits}U")
                }
            }
        }
    }

    /** One encrypted write at EXACTLY [counter] (no auto-sync). onStatus gets the raw GATT status. */
    private fun writeOnceAt(uuid: UUID, command: ByteArray, counter: Long, onStatus: (Int) -> Unit) {
        if (!YpsoWritePolicy.allows(YpsoRemoteWrite.COMMAND_CHARACTERISTIC)) {
            aapsLogger.error(LTag.PUMP, "YpsoPump READ_ONLY_MODE blocked counter write to $uuid")
            onStatus(READ_ONLY_BLOCKED_STATUS)
            return
        }
        onStatus(READ_ONLY_BLOCKED_STATUS)
    }

    /**
     * SAFE bolus (production + test). Locks the write counter on the BENIGN event-index char first
     * (canary: tries seed+1, seed, seed+2 — a wrong counter is rejected with NO pump effect), then sends
     * the bolus EXACTLY ONCE at the confirmed next counter. No auto-sync, no scanning. If the canary
     * can't be confirmed it ABORTS and the bolus char is never written. The write counter is persisted
     * after each accepted write so AAPS owns it across reconnects. [onResult] = (accepted, message).
     */
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

    /** [testBolusCanary] with the outcome distinguished — see [BolusStart]. */
    fun startBolus(units: Double, seedW: Long, onResult: (BolusStart, String) -> Unit) {
        if (YpsoPumpConst.READ_ONLY_MODE) { onResult(BolusStart.NOT_SENT, "bolus unavailable in status-only mode"); return }
        if (!isConnected || bluetoothGatt == null) { onResult(BolusStart.NOT_SENT, "not connected"); return }
        val canary = glbEncode(0)                              // select event index 0 — zero therapy (GLB, no CRC)
        // SAFETY: unlike the TBR path, the BOLUS canary does NOT scan the counter forward. A dropped ack on a
        // bolus write can mean the pump ALREADY DELIVERED; self-healing the counter would let a retry double
        // dose. So on a counter error we FAIL CLOSED (abort). Proper fix (TODO): on ambiguous counter, read
        // CHAR_BOLUS_STATUS to confirm whether the prior bolus landed before allowing another.
        val candidates = listOf(seedW + 1, seedW, seedW + 2)
        fun tryCanary(i: Int) {
            if (i >= candidates.size) { onResult(BolusStart.NOT_SENT, "canary failed (tried $candidates) — counter off, NO BOLUS sent (fail-closed; reseed after confirming no bolus was delivered)"); return }
            val c = candidates[i]
            aapsLogger.debug(LTag.PUMP, "YpsoPump canary index-write @counter=$c")
            writeOnceAt(CHAR_EVENT_INDEX, canary, c) { status ->
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        persistWriteCounter()
                        aapsLogger.debug(LTag.PUMP, "YpsoPump CANARY ACCEPTED @$c — counter locked; bolus will be @${c + 1}")
                        sendBolus(units, c, onResult)
                    }
                    ERR_WRITE_REJECTED         -> tryCanary(i + 1)
                    else                       -> onResult(BolusStart.NOT_SENT, "canary write status=$status — ABORT, NO BOLUS")
                }
            }
        }
        tryCanary(0)
    }

    private fun sendBolus(units: Double, lockedCounter: Long, onResult: (BolusStart, String) -> Unit) {
        val cmd = BolusCommand(units)                          // standard/immediate bolus
        val boIns = cmd.encode()
        aapsLogger.debug(LTag.PUMP, "YpsoPump >>> SENDING BOLUS ${units}U @counter=${lockedCounter + 1} raw=${boIns.joinToString("") { "%02x".format(it) }}")
        writeOnceAt(CHAR_BOLUS_START_STOP, YpsoCrc.appendCrc(boIns), lockedCounter + 1) { status ->
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // The bolus char WAS written. An app-level rejection (138/139/…) means the pump refused
                // it outright, but a link-level failure can also be a dropped ack on a delivered dose —
                // so this is UNCERTAIN, never "didn't happen". The caller confirms by reading the pump.
                onResult(BolusStart.UNCERTAIN, "bolus write not acked (status=$status) @${lockedCounter + 1} — confirming against the pump")
                return@writeOnceAt
            }
            persistWriteCounter()
            aapsLogger.debug(LTag.PUMP, "YpsoPump >>> BOLUS ACCEPTED @${lockedCounter + 1}")
            readBolusStatus { st ->
                pumpState.lastConnectionTime = System.currentTimeMillis()
                onResult(BolusStart.SENT, "bolus accepted ${units}U; bolusStatus=${st?.bolusStatusCode} injected=${st?.deliveredUnits}U total=${st?.totalProgrammedUnits}U")
            }
        }
    }

    /**
     * Cancel a running bolus: canary-lock the write counter (same benign event-index gate as the bolus),
     * then write the all-zero START_STOP payload EXACTLY ONCE. Idempotent-ish (cancelling an already-finished
     * bolus is harmless), so — unlike delivery — a dropped ack here is not dangerous. [onResult]=(sent, msg).
     */
    fun cancelBolus(seedW: Long, extended: Boolean, onResult: (Boolean, String) -> Unit) {
        if (YpsoPumpConst.READ_ONLY_MODE) { onResult(false, "bolus cancellation unavailable in status-only mode"); return }
        if (!isConnected || bluetoothGatt == null) { onResult(false, "not connected"); return }
        val canary = glbEncode(0)
        val candidates = listOf(seedW + 1, seedW, seedW + 2)
        fun tryCanary(i: Int) {
            if (i >= candidates.size) { onResult(false, "cancel canary failed (tried $candidates)"); return }
            val c = candidates[i]
            writeOnceAt(CHAR_EVENT_INDEX, canary, c) { status ->
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        persistWriteCounter()
                        writeOnceAt(CHAR_BOLUS_START_STOP, YpsoCrc.appendCrc(BolusCommand.cancelPayload(extended)), c + 1) { st ->
                            if (st == BluetoothGatt.GATT_SUCCESS) { persistWriteCounter(); onResult(true, "bolus cancel sent @${c + 1}") }
                            else onResult(false, "cancel write status=$st @${c + 1}")
                        }
                    }
                    ERR_WRITE_REJECTED         -> tryCanary(i + 1)
                    else                       -> onResult(false, "cancel canary status=$status")
                }
            }
        }
        tryCanary(0)
    }

    /**
     * SAFE TBR (production + test) via the same canary as the bolus: lock the write counter on the BENIGN
     * event-index char, then write the TBR command EXACTLY ONCE at the confirmed next counter. Aborts
     * with no TBR write if the canary can't be confirmed. [percent] 0=suspend, 100=normal; [durationMinutes]
     * MUST be a 15-min step (15/30/…). Counter persisted after each accepted write. [onResult]=(accepted,msg).
     */
    fun testTbrCanary(percent: Int, durationMinutes: Int, seedW: Long, onResult: (Boolean, String) -> Unit) {
        if (YpsoPumpConst.READ_ONLY_MODE) { onResult(false, "TBR unavailable in status-only mode"); return }
        if (!isConnected || bluetoothGatt == null) { onResult(false, "not connected"); return }
        val canary = glbEncode(0)                              // select event index 0 — zero therapy (GLB, no CRC)
        // Scan the counter FORWARD from seedW+1 to self-heal a dropped-ack desync (see testBolusCanary /
        // ERR_COUNTER_BEHIND). Benign zero-therapy writes; the TBR is still sent ONCE at the confirmed counter.
        val candidates = (1..COUNTER_RESYNC_SCAN).map { seedW + it }
        fun tryCanary(i: Int) {
            if (i >= candidates.size) { onResult(false, "canary failed (scanned +1..+$COUNTER_RESYNC_SCAN from $seedW) — NO TBR sent"); return }
            val c = candidates[i]
            aapsLogger.debug(LTag.PUMP, "YpsoPump TBR canary index-write @counter=$c")
            writeOnceAt(CHAR_EVENT_INDEX, canary, c) { status ->
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        persistWriteCounter()
                        if (i > 0) aapsLogger.warn(LTag.PUMP, "YpsoPump counter was BEHIND by $i (dropped ack) — resynced to $c")
                        aapsLogger.debug(LTag.PUMP, "YpsoPump TBR CANARY ACCEPTED @$c — counter locked; TBR will be @${c + 1}")
                        sendTestTbr(percent, durationMinutes, c, onResult)
                    }
                    ERR_WRITE_REJECTED, ERR_COUNTER_BEHIND -> tryCanary(i + 1)   // 0x8A/0x8B: counter off → scan forward
                    else                       -> onResult(false, "TBR canary write status=$status — ABORT, NO TBR")
                }
            }
        }
        // Read the event count once before the canary so the TBR path is read-before-write CONSISTENT with
        // the bolus path (deliverBolus -> establishCounter reads EVENT_COUNT first). Ypsomed doc 18 advises a
        // pump read before writing after (re)connect; this makes the behaviour uniform and is a harmless
        // benign read (no therapy, does not touch the write counter). NOTE: this is defensive, not the cure
        // for app-error 0x8B (139) seen 2026-07-01 — that was the write counter being BEHIND the pump (the
        // check is forward-gap tolerant, so 0x8B = counter too low). Recovery for 0x8B is to re-seed the
        // persisted writeCounter WELL ABOVE the pump's current value (a forward jump), not a code change.
        readMultiframe(CHAR_EVENT_COUNT) { _, fc ->
            val count = runCatching { glbFind(decryptOwned(fc)) }.getOrNull()
            aapsLogger.debug(LTag.PUMP, "YpsoPump TBR prime-read event count=$count (readCounter=$readCounter) — session primed, sending canary")
            tryCanary(0)
        }
    }

    private fun sendTestTbr(percent: Int, durationMinutes: Int, lockedCounter: Long, onResult: (Boolean, String) -> Unit) {
        fun writeTbrAt(pct: Int, dur: Int, counter: Long, label: String, cb: (Int) -> Unit) {
            val payload = TbrCommand(pct, dur).encode()          // complement-protected fields — NO CRC (unlike bolus)
            aapsLogger.debug(LTag.PUMP, "YpsoPump >>> $label TBR ${pct}% for ${dur}min @counter=$counter raw=${payload.joinToString("") { "%02x".format(it) }}")
            writeOnceAt(CHAR_TBR_START_STOP, payload, counter, cb)
        }
        fun finish(c: Long, note: String) {
            persistWriteCounter()
            aapsLogger.debug(LTag.PUMP, "YpsoPump >>> TBR ACCEPTED @$c$note")
            readStatus { statusRead ->
                pumpState.lastConnectionTime = System.currentTimeMillis()
                onResult(true, "TBR accepted ${percent}% ${durationMinutes}min$note; statusRead=$statusRead pump activeTbrPercent=${pumpState.activeTbrPercent}")
            }
        }
        // Try to START the requested TBR directly.
        writeTbrAt(percent, durationMinutes, lockedCounter + 1, "SENDING") { status ->
            when {
                status == BluetoothGatt.GATT_SUCCESS -> finish(lockedCounter + 1, "")
                // 0x86 = a TBR is already active; the pump won't START a new one until the current is STOPped.
                // Cancel it (100%/0 — the STOP; can never itself be blocked) then re-send. The rejected write
                // consumes one counter on the pump, so cancel/re-send go at +2/+3 (all forward-gap safe).
                status == ERR_TBR_ACTIVE && percent != 100 -> {
                    aapsLogger.warn(LTag.PUMP, "YpsoPump TBR rejected 0x86 (active TBR blocks a new one) — cancelling prior TBR then re-sending")
                    writeTbrAt(100, 0, lockedCounter + 2, "CANCEL-PRIOR") { cancelStatus ->
                        if (cancelStatus != BluetoothGatt.GATT_SUCCESS) { onResult(false, "TBR pre-cancel rejected status=$cancelStatus @${lockedCounter + 2} — NO TBR"); return@writeTbrAt }
                        persistWriteCounter()
                        writeTbrAt(percent, durationMinutes, lockedCounter + 3, "RE-SENDING") { retry ->
                            if (retry != BluetoothGatt.GATT_SUCCESS) { onResult(false, "TBR re-send rejected status=$retry @${lockedCounter + 3} — NO TBR"); return@writeTbrAt }
                            finish(lockedCounter + 3, " (after cancelling prior TBR)")
                        }
                    }
                }
                else -> onResult(false, "TBR REJECTED status=$status @${lockedCounter + 1}")
            }
        }
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
                    failOperations(failed)
                    // An in-flight operation reports its own failure; only an idle connection needs the
                    // disconnect itself recorded, so one physical disconnect is one durable transition.
                    if (failed.isEmpty()) {
                        provisioning.recordCandidateOrUnavailable(
                            ownership.generation, ownership.attemptId,
                            setOf(PumpSession.AvailabilityCause.TRANSPORT), operation = "gatt-disconnected"
                        )
                    }
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
                if (!YpsoWritePolicy.allows(YpsoRemoteWrite.AUTHENTICATION)) {
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
                    // The MD5 auth characteristic is the sole remote write permitted in read-only mode; it is
                    // required before encrypted status reads. Control notifications are command-only setup.
                    if (YpsoPumpConst.READ_ONLY_MODE) {
                        aapsLogger.info(LTag.PUMP, "YpsoPump authenticated in read-only mode; control notifications disabled")
                        markConnected(controlNotificationsEnabled = false)
                    } else {
                        aapsLogger.info(LTag.PUMP, "YpsoPump authenticated; enabling control notifications")
                        enableCtrlNotify(g)
                    }
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
            } else {
                completeCurrent(g, ch.uuid, null, status)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            synchronized(opLock) {
                if (!ownsGattLocked(g)) return
                if (descriptor.characteristic?.uuid == CHAR_CTRL_NOTIFY) {
                    if (pumpState.connectionState != ConnectionState.READY) return
                    if (status != BluetoothGatt.GATT_SUCCESS)
                        aapsLogger.warn(LTag.PUMP, "YpsoPump CTRL_NOTIFY CCCD write failed ($status) — proceeding, writes may be rejected")
                    else
                        aapsLogger.info(LTag.PUMP, "YpsoPump CTRL_NOTIFY subscription active")
                    markConnected(controlNotificationsEnabled = true)
                }
            }
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
        failOperations(failed)
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

    /**
     * Enable the control-notification subscription (write CCCD 0x0001 to [CHAR_CTRL_NOTIFY]) — the
     * pump's write-handshake precondition. Defensive: if the char/CCCD is somehow absent we still go
     * CONNECTED so the (notification-independent) read path keeps working.
     */
    @SuppressLint("MissingPermission")
    private fun enableCtrlNotify(g: BluetoothGatt) {
        if (!YpsoWritePolicy.allows(YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR)) {
            aapsLogger.error(LTag.PUMP, "YpsoPump READ_ONLY_MODE blocked control-notification descriptor write")
            markConnected(controlNotificationsEnabled = false)
            return
        }
        val ch = findChar(g, CHAR_CTRL_NOTIFY)
        val cccd = ch?.getDescriptor(YpsoPumpConst.CCCD_UUID)
        if (ch == null || cccd == null) {
            aapsLogger.warn(LTag.PUMP, "YpsoPump CTRL_NOTIFY char/CCCD not found — proceeding without it (writes may be rejected)")
            markConnected(controlNotificationsEnabled = false); return
        }
        g.setCharacteristicNotification(ch, true)
        val dispatched = writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR)
        aapsLogger.info(LTag.PUMP, "YpsoPump CTRL_NOTIFY CCCD write dispatched=$dispatched")
        if (!dispatched) markConnected(controlNotificationsEnabled = false)
        // CONNECTED is set in onDescriptorWrite once the write completes.
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
                remoteWrite, characteristic.uuid, value,
                runCatching { authPassword(pumpState.pumpAddress) }.getOrDefault(byteArrayOf()),
                pumpState.connectionState == ConnectionState.READY
            )
        }
        if (!authorized) {
            aapsLogger.error(LTag.PUMP, "YpsoPump READ_ONLY_MODE blocked $remoteWrite write to ${characteristic.uuid}")
            return false
        }
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        g.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
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
        if (YpsoPumpConst.READ_ONLY_MODE || remoteWrite != YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR) {
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
