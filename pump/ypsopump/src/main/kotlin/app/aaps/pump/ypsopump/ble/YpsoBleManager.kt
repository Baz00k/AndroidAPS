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
import app.aaps.pump.ypsopump.comm.YpsoBolusNotification
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.comm.commands.StatusCommand
import app.aaps.pump.ypsopump.bolus.YpsoBolusBlock
import app.aaps.pump.ypsopump.bolus.YpsoValidatedBolusRequest
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionJournal
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.data.YpsoFirmwareVersion
import app.aaps.pump.ypsopump.data.YpsoBasalSchedule
import app.aaps.pump.ypsopump.data.YpsoProfileReadback
import app.aaps.pump.ypsopump.history.YpsoHistoryCursor
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoHistorySnapshot
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.provisioning.PumpIdentity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE manager for authenticated status, profile/history selector acquisition and bolus dispatch.
 * An unknown write floor is reconciled from zero through pump-confirmed counter errors. Pump
 * configuration and temporary-basal mutations remain blocked by policy.
 *
 * Set the captured session key with [setSharedKey] before connecting.
 */
@Singleton
class YpsoBleManager @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val sessionCrypto: SessionCrypto,
    private val pumpState: YpsoPumpState,
    private val provisioning: YpsoProvisioningService
) {
    internal fun noBackupDirectory(): java.io.File = context.noBackupFilesDir

    init {
        provisioning.quiesceConnection = { disconnect() }
    }

    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, DISCOVERING, READY, CONNECTED }

    @Volatile private var bluetoothGatt: BluetoothGatt? = null
    val isConnected: Boolean get() = pumpState.connectionState == ConnectionState.CONNECTED

    /** Receives every decoded bolus state change the pump pushes on CONTROL_NOTIFY. */
    @Volatile
    var onBolusNotification: ((YpsoBolusNotification) -> Unit)? = null
    /**
     * A verified session with an authenticated read floor can acquire selectors. An unknown write
     * floor is normal: the first write reconciles it from zero through the pump-confirmed search.
     */
    val canReadProfile: Boolean
        get() = session?.snapshot()?.let { it.reboot != null && it.read != null } == true
    val canReadHistory: Boolean
        get() = canReadProfile && !profileReadActive.get() && !historyReadActive.get()
    internal fun writeReadinessFailure(): String? {
        val gatt = bluetoothGatt
        val token = sessionToken
        val record = session?.snapshot()
        return when {
            !isConnected -> "pump connection state is ${pumpState.connectionState}"
            gatt == null -> "GATT connection owner is unavailable"
            token == null -> "authenticated session token is unavailable"
            !hasCompatibleStatusProtocol() ->
                "unsupported status protocol: master=${pumpState.masterVersion.ifBlank { "missing" }}," +
                    "supervisor=${pumpState.supervisorVersion.ifBlank { "missing" }}," +
                    "control=${pumpState.controlServiceVersion.ifBlank { "missing" }}"
            record == null -> "durable session record is unavailable"
            record.reboot == null -> "authenticated pump reboot counter is unavailable"
            record.read == null -> "authenticated read counter is unavailable"
            record.reservation?.phase != null && record.reservation.phase != PumpSession.Phase.VERIFIED ->
                "unresolved write reservation ${record.reservation.operationId ?: record.reservation.id} is ${record.reservation.phase}"
            // Only a write still in flight on this very connection can conflict. A record stranded by a
            // replaced connection is unanswerable and must never hard-block therapy.
            profileWriteTransportInstance?.hasUnresolvedWriteOn(gatt) == true -> "another pump setting is still being written"
            historyWriteTransportInstance?.hasUnresolvedWriteOn(gatt) == true -> "pump history is still being read"
            bolusWriteTransportInstance?.hasUnresolvedWriteOn(gatt) == true -> "a previous bolus command is still being sent"
            else -> null
        }
    }
    internal fun readinessStatus(): String {
        val gatt = bluetoothGatt
        val record = session?.snapshot()
        fun capability(uuid: UUID, property: Int): Boolean =
            gatt?.let { findChar(it, uuid)?.properties?.and(property) != 0 } == true
        return "connection=${pumpState.connectionState},gatt=${gatt != null},token=${sessionToken != null}," +
            "protocol=${hasCompatibleStatusProtocol()},setting_selector_read=" +
            capability(YpsoWritePolicy.SETTING_ID_UUID, BluetoothGattCharacteristic.PROPERTY_READ) +
            ",setting_selector_write=" + capability(YpsoWritePolicy.SETTING_ID_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE) +
            ",history_selector_read=" + capability(YpsoWritePolicy.EVENT_INDEX_UUID, BluetoothGattCharacteristic.PROPERTY_READ) +
            ",history_selector_write=" + capability(YpsoWritePolicy.EVENT_INDEX_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE) +
            ",bolus_write=" + capability(YpsoWritePolicy.BOLUS_START_STOP_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE) +
            ",reboot=${record?.reboot},read=${record?.read},write=${record?.write}," +
            "bootstrap=${record?.writeBootstrapState},recovery_exponent=${record?.counterRecoveryExponent}," +
            "reservation=${record?.reservation?.phase ?: "none"}," +
            "failure=${writeReadinessFailure() ?: "none"}"
    }
    @Volatile internal var session: PumpSession? = null
    @Volatile private var sessionToken: PumpSession.Token? = null
    @Volatile private var configuredKey: ByteArray? = null
    @Volatile private var configuredGeneration: String? = null
    @Volatile private var configuredAttemptId: String? = null
    @Volatile private var configuredConnection: YpsoProvisioningService.ConnectionSession? = null
    private var bondedIdentitySerial: String? = null
    private val readCounter: Long get() = session?.snapshot()?.read ?: 0L
    private val statusReadActive = AtomicBoolean(false)
    private val profileReadActive = AtomicBoolean(false)
    private val historyReadActive = AtomicBoolean(false)
    private val bolusWriteActive = AtomicBoolean(false)

    /** Claim one whole logical pump operation before it can enqueue anything on Android's GATT lane. */
    private fun acquirePumpOperation(claim: AtomicBoolean): Boolean = synchronized(opLock) {
        if (statusReadActive.get() || profileReadActive.get() || historyReadActive.get() || bolusWriteActive.get()) {
            false
        } else {
            claim.set(true)
            true
        }
    }
    @Volatile private var controlNotificationsEnabled = false

    companion object {
        private const val OP_TIMEOUT_MS = 8000L   // 2026-07-13: a BLE op with no callback in this long is treated as stalled and force-failed (unwedges the queue + multiframe latch)
        private const val EXPECTED_PROFILE_SELECTOR_FRAME_COUNT = 4
        private const val OP_TIMEOUT_STATUS = -2   // sentinel status for a timed-out op (!= GATT_SUCCESS, distinct from -1 no-gatt)
        private const val LOCAL_CANCEL_STATUS = -3 // local preemption; release ownership without reporting transport failure
        private val SUPPORTED_CONTROL_SERVICE_VERSION = "1.3\u0000".toByteArray(Charsets.US_ASCII)
        private val SERVICE_CONTROL: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0000")
        private val SERVICE_EXTREAD: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0002")
        private val CHAR_AUTH: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb2147bc5")
        private val CHAR_STATUS: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee48b7bc5")
        private val CHAR_EXTREAD: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff000000ff")
        private val CHAR_SETTING_VALUE: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb4147bc5")
        private val CHAR_SYSTEM_DATE: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbedc3b7bc5")
        private val CHAR_SYSTEM_TIME: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbedd3b7bc5")
        // History (events) — used for the zero-therapy write-transport validation.
        private val CHAR_EVENT_COUNT: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecb3b7bc5")
        private val CHAR_EVENT_VALUE: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecd3b7bc5")
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
            val cached = profileStore.load(installed.generation)
            pumpState.invalidateProfileEvidence()
            if (cached != null) pumpState.publishProfileEvidence(cached)
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
    private val profileStore by lazy {
        app.aaps.pump.ypsopump.data.YpsoProfileConfigurationStore(context.getSharedPreferences("ypso_profile_configuration", Context.MODE_PRIVATE))
    }
    internal var persistProfile: (YpsoProfileReadback.VerifiedReadback) -> Unit = { profileStore.save(it) }

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
        if (!acquirePumpOperation(statusReadActive)) {
            attempt.tryComplete()
            onDone(false)
            return attempt
        }
        val ownership = synchronized(opLock) {
            pumpState.invalidateStatus()
            ReadOwnership(bluetoothGatt, sessionToken?.generation, configuredAttemptId)
        }
        val originGatt = ownership.gatt
        attempt.onCancel = {
            statusReadActive.set(false)
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
            statusReadActive.set(false)
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

    class ProfileReadAttempt internal constructor() {
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

    class HistoryReadAttempt internal constructor() {
        private val active = AtomicBoolean(true)
        private val yieldRequested = AtomicBoolean(false)
        internal val isActive: Boolean get() = active.get()
        internal val shouldYield: Boolean get() = yieldRequested.get()
        internal var onCancel: () -> Unit = {}
        internal fun tryComplete(): Boolean = active.compareAndSet(true, false)
        /** Stop at the next selector-safe boundary, after any dispatched write is reconciled. */
        fun requestYield(): Boolean {
            if (!active.get()) return false
            yieldRequested.set(true)
            return true
        }
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
            ownedGatt?.let { profileSelectorCoordinatorInstance?.ownerDisconnected(it, "local disconnect") }
            ownedGatt?.let { historySelectorCoordinatorInstance?.ownerDisconnected(it, "local disconnect") }
            ownedGatt?.let { bolusWriteCoordinatorInstance?.ownerDisconnected(it, "local disconnect") }
            bluetoothGatt = null
            session?.quiesce()
            sessionToken = null
            pumpState.connectionState = ConnectionState.DISCONNECTED
            statusReadActive.set(false)
            profileReadActive.set(false)
            historyReadActive.set(false)
            bolusWriteActive.set(false)
            controlNotificationsEnabled = false
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
        val bolusOwner: Boolean = false,
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
    private val opScheduler = java.util.concurrent.ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "ypso-ble-watchdog").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val scheduledOps = java.util.concurrent.ConcurrentHashMap<Runnable, java.util.concurrent.ScheduledFuture<*>>()

    // Injectable scheduling seams keep timeout/callback races deterministic in local unit tests without
    // exposing the BLE queue itself. Production uses a dedicated watchdog, independent of UI load.
    internal var scheduleOpTimeout: (Runnable, Long) -> Unit = { timeout, delay ->
        scheduledOps.remove(timeout)?.cancel(false)
        scheduledOps[timeout] = opScheduler.schedule({
            scheduledOps.remove(timeout)
            timeout.run()
        }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    internal var cancelOpTimeout: (Runnable) -> Unit = { timeout -> scheduledOps.remove(timeout)?.cancel(false) }
    internal var scheduleProfileContinuation: (Runnable) -> Unit = { runnable -> opScheduler.execute(runnable) }
    internal var sdkInt: Int = Build.VERSION.SDK_INT
    private var handshakeTimeout: Runnable? = null
    private var profileWriteTransportInstance: YpsoSerializedWriteTransport? = null
    private var profileSelectorCoordinatorInstance: YpsoProfileSelectorCoordinator? = null
    private var profileSelectorSession: PumpSession? = null
    private var historyWriteTransportInstance: YpsoSerializedWriteTransport? = null
    private var historySelectorCoordinatorInstance: YpsoHistorySelectorCoordinator? = null
    private var historySelectorSession: PumpSession? = null
    private var bolusWriteTransportInstance: YpsoSerializedWriteTransport? = null
    private var bolusWriteCoordinatorInstance: YpsoBolusWriteCoordinator? = null
    private var bolusWriteSession: PumpSession? = null
    private val profileWriteTransport: YpsoSerializedWriteTransport
        get() = profileWriteTransportInstance ?: YpsoSerializedWriteTransport(scheduleOpTimeout, cancelOpTimeout).also {
            profileWriteTransportInstance = it
        }
    private val profileSelectorCoordinator: YpsoProfileSelectorCoordinator
        get() {
            val owner = checkNotNull(session)
            if (profileSelectorCoordinatorInstance == null || profileSelectorSession !== owner) {
                profileSelectorSession = owner
                profileSelectorCoordinatorInstance = YpsoProfileSelectorCoordinator(owner, sessionCrypto, profileWriteTransport)
            }
            return checkNotNull(profileSelectorCoordinatorInstance)
        }

    private val historyWriteTransport: YpsoSerializedWriteTransport
        get() = historyWriteTransportInstance ?: YpsoSerializedWriteTransport(scheduleOpTimeout, cancelOpTimeout).also {
            historyWriteTransportInstance = it
        }
    private val historySelectorCoordinator: YpsoHistorySelectorCoordinator
        get() {
            val owner = checkNotNull(session)
            if (historySelectorCoordinatorInstance == null || historySelectorSession !== owner) {
                historySelectorSession = owner
                historySelectorCoordinatorInstance = YpsoHistorySelectorCoordinator(owner, sessionCrypto, historyWriteTransport)
            }
            return checkNotNull(historySelectorCoordinatorInstance)
        }

    private val bolusWriteTransport: YpsoSerializedWriteTransport
        get() = bolusWriteTransportInstance ?: YpsoSerializedWriteTransport(scheduleOpTimeout, cancelOpTimeout).also {
            bolusWriteTransportInstance = it
        }
    private val bolusWriteCoordinator: YpsoBolusWriteCoordinator
        get() {
            val owner = checkNotNull(session)
            if (bolusWriteCoordinatorInstance == null || bolusWriteSession !== owner) {
                bolusWriteSession = owner
                bolusWriteCoordinatorInstance = YpsoBolusWriteCoordinator(owner, sessionCrypto, bolusWriteTransport)
            }
            return checkNotNull(bolusWriteCoordinatorInstance)
        }

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
            val op = if (bolusWriteActive.get()) {
                val index = queue.indexOfFirst { it.bolusOwner }
                if (index < 0) return
                queue.removeAt(index)
            } else queue.removeFirstOrNull() ?: return
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
        synchronized(opLock) {
            if (current !== op || currentGatt !== gatt || bluetoothGatt !== gatt || op.uuid != uuid) return
            current = null
            currentGatt = null
            timeout = currentTimeout
            currentTimeout = null
        }
        timeout?.let { runCatching { cancelOpTimeout(it) } }
        // Domain callbacks can publish availability and synchronously enter the pump plugin. Never do
        // that while holding opLock: deliverTreatment may be waiting for this exact callback, and the
        // reverse lock order otherwise deadlocks both the command worker and Android's main thread.
        // The operation was detached above, so teardown cannot drain or deliver it a second time.
        runCatching { op.onResult(gatt, value, status) }
            .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump operation callback threw: ${it.message}") }
        pumpOps()
    }

    private fun completeCurrent(gatt: BluetoothGatt, uuid: UUID, value: ByteArray?, status: Int) {
        // Android callbacks expose no operation ID. Same-UUID read chains are protected by frame
        // sequence validation; profile selector writes have a separate whole-write owner.
        val op = synchronized(opLock) {
            if (bluetoothGatt !== gatt || currentGatt !== gatt || current?.uuid != uuid) return
            current
        } ?: return
        complete(op, gatt, uuid, value, status)
    }

    private fun cancelCurrentOperation(gatt: BluetoothGatt) {
        val op = synchronized(opLock) { current?.takeIf { currentGatt === gatt } } ?: return
        complete(op, gatt, op.uuid, null, LOCAL_CANCEL_STATUS)
    }

    @SuppressLint("MissingPermission")
    private fun readOp(
        gatt: BluetoothGatt,
        uuid: UUID,
        bolusOwner: Boolean = false,
        onResult: (BluetoothGatt?, ByteArray?, Int) -> Unit,
    ) = enqueue(Op(gatt, uuid, bolusOwner, { g, op ->
            val characteristic = findChar(g, uuid)
            if (characteristic == null || !g.readCharacteristic(characteristic)) {
                complete(op, g, uuid, null, -1)
            }
        }, onResult))

    // Plaintext IDs are allowlisted by the coordinator before encryption/reservation. The final
    // GATT boundary admits only the exact frame during that coordinator's synchronous dispatch.
    private var authorizedProfileFrame: ByteArray? = null
    private var authorizedHistoryFrame: ByteArray? = null
    private var authorizedBolusFrame: ByteArray? = null

    private fun writeProfileFrame(gatt: BluetoothGatt, value: ByteArray): Boolean = synchronized(opLock) {
        if (bluetoothGatt !== gatt || current != null || !profileReadActive.get()) return@synchronized false
        val characteristic = findChar(gatt, YpsoWritePolicy.SETTING_ID_UUID) ?: return@synchronized false
        authorizedProfileFrame = value
        try {
            writeCharacteristic(gatt, characteristic, value, YpsoRemoteWrite.SETTINGS_SELECTOR)
        } finally {
            authorizedProfileFrame = null
        }
    }

    private fun writeHistoryFrame(gatt: BluetoothGatt, value: ByteArray): Boolean = synchronized(opLock) {
        if (bluetoothGatt !== gatt || current != null || !historyReadActive.get()) return@synchronized false
        val characteristic = findChar(gatt, YpsoWritePolicy.EVENT_INDEX_UUID) ?: return@synchronized false
        authorizedHistoryFrame = value
        try {
            writeCharacteristic(gatt, characteristic, value, YpsoRemoteWrite.HISTORY_SELECTOR)
        } finally {
            authorizedHistoryFrame = null
        }
    }

    private fun writeBolusFrame(gatt: BluetoothGatt, value: ByteArray): Boolean = synchronized(opLock) {
        if (bluetoothGatt !== gatt || current != null || !bolusWriteActive.get()) return@synchronized false
        val characteristic = findChar(gatt, YpsoWritePolicy.BOLUS_START_STOP_UUID) ?: return@synchronized false
        authorizedBolusFrame = value
        try {
            writeCharacteristic(gatt, characteristic, value, YpsoRemoteWrite.THERAPY_COMMAND)
        } finally {
            authorizedBolusFrame = null
        }
    }

    private fun releaseBolusWrite() {
        bolusWriteActive.set(false)
        pumpOps()
    }

    @SuppressLint("MissingPermission")
    private fun enableProfileSetup(gatt: BluetoothGatt, done: (Boolean) -> Unit) {
        if (controlNotificationsEnabled) {
            done(true)
            return
        }
        val characteristic = findChar(gatt, YpsoWritePolicy.CONTROL_NOTIFY_UUID)
        val descriptor = characteristic?.getDescriptor(YpsoWritePolicy.CCCD_UUID)
        if (characteristic == null || descriptor == null || !gatt.setCharacteristicNotification(characteristic, true)) {
            done(false)
            return
        }
        enqueue(
            Op(
                gatt,
                descriptor.uuid,
                bolusOwner = bolusWriteActive.get(),
                { owner, op ->
                    if (!writeDescriptor(owner, descriptor, byteArrayOf(1, 0), YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR)) {
                        complete(op, owner, descriptor.uuid, null, -1)
                    }
                },
                { owner, _, status ->
                    val success = owner === gatt && status == BluetoothGatt.GATT_SUCCESS
                    if (success) controlNotificationsEnabled = true
                    done(success)
                },
            ),
        )
    }

    // The pump's EXTREAD characteristic is a single shared cursor, so only ONE multi-frame read may
    // be in flight at a time — overlapping reads interleave EXTREAD frames and corrupt both. This
    // guard rejects (rather than silently corrupting) an overlapping read; all internal flows chain
    // sequentially via callbacks.
    private var multiframeOwner: Any? = null
    private fun readMultiframe(
        uuid: UUID,
        expectedGatt: BluetoothGatt? = bluetoothGatt,
        failureOwner: ReadOwnership? = null,
        bolusOwner: Boolean = false,
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
        fun step(now: UUID, expectedFrame: Int): Unit = readOp(originGatt, now, bolusOwner) { gatt, v, s ->
            if (diagnosticLoggingEnabled())
                aapsLogger.debug(LTag.PUMP, "YpsoPump frame[${frames.size}] from $now: status=$s ${v?.joinToString("") { "%02x".format(it) } ?: "null"}")
            val reportedTotal = v?.let { YpsoFraming.validateFrame(it, expectedFrame, totalFrames) }
            val invalidFrame = v != null && reportedTotal == null
            if (gatt !== originGatt || s != BluetoothGatt.GATT_SUCCESS || v == null || invalidFrame) {
                if (!finishTransaction()) return@readOp
                if (s == LOCAL_CANCEL_STATUS) {
                    onFailure()
                    return@readOp
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
                val stillOwned = synchronized(opLock) { bluetoothGatt === originGatt }
                if (stillOwned) done(originGatt, reassemble(frames)) else onFailure()
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
        readMultiframe(
            CHAR_STATUS,
            expectedGatt = originGatt,
            failureOwner = ownership,
            onFailure = {
                statusReadActive.set(false)
                if (attempt.tryComplete()) onDone(false)
            },
        ) { gatt, frame ->
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
                        batteryBars = status.batteryBars,
                        activeBasalRate = status.basalRate,
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
            statusReadActive.set(false)
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

    /**
     * Acquire active-before, all A/B rows, active-after and pump clock on one authenticated GATT.
     * Every selector is independently journaled and semantically reconciled only after the same-link
     * selector characteristic reads back the requested setting ID. The value is then read separately.
     * A failure publishes nothing and never retries an uncertain write.
     */
    fun readProfile(onDone: (Boolean) -> Unit = {}): ProfileReadAttempt = readProfileConfiguration(false, { false }, onDone)

    fun readProfileConfiguration(activeOnly: Boolean, shouldYield: () -> Boolean, onDone: (Boolean) -> Unit): ProfileReadAttempt {
        val attempt = ProfileReadAttempt()
        if (!acquirePumpOperation(profileReadActive)) {
            attempt.tryComplete()
            onDone(false)
            return attempt
        }
        val captured = synchronized(opLock) {
            Triple(bluetoothGatt, sessionToken, UUID.randomUUID().toString())
        }
        val gatt = captured.first
        val token = captured.second
        val connectionId = captured.third
        attempt.onCancel = {
            synchronized(opLock) {
                if (bluetoothGatt === gatt) disconnect()
            }
            runCatching { onDone(false) }
        }
        if (!isConnected || gatt == null || token == null || !hasCompatibleStatusProtocol()) {
            profileReadActive.set(false)
            if (attempt.tryComplete()) onDone(false)
            return attempt
        }
        val settingIdCharacteristic = findChar(gatt, YpsoWritePolicy.SETTING_ID_UUID)
        if (settingIdCharacteristic == null ||
            settingIdCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0 ||
            listOf(CHAR_SETTING_VALUE, CHAR_SYSTEM_DATE, CHAR_SYSTEM_TIME, CHAR_EXTREAD).any { findChar(gatt, it) == null }
        ) {
            aapsLogger.warn(LTag.PUMP, "YpsoPump profile read blocked: selector identity read-back is unavailable")
            profileReadActive.set(false)
            if (attempt.tryComplete()) onDone(false)
            return attempt
        }
        val initial = session?.snapshot()
        val reboot = initial?.reboot
        if (initial == null || reboot == null) {
            aapsLogger.warn(LTag.PUMP, "YpsoPump profile read blocked: durable pump session floor is unavailable")
            profileReadActive.set(false)
            if (attempt.tryComplete()) onDone(false)
            return attempt
        }
        val owner = YpsoProfileSelectorCoordinator.Owner(gatt, connectionId, token)
        val startedElapsed = android.os.SystemClock.elapsedRealtime()
        var readback: YpsoProfileReadback? = null
        var selectedSettingId: Int? = null
        var eventCountBefore: Int? = null

        fun owned(): Boolean = synchronized(opLock) {
            bluetoothGatt === gatt && sessionToken == token && session?.snapshot()?.reboot == reboot
        }
        fun failProfile(detail: String) {
            if (!attempt.tryComplete()) return
            readback?.invalidate()
            aapsLogger.error(LTag.PUMP, "YpsoPump profile read failed: $detail")
            profileReadActive.set(false)
            onDone(false)
        }
        fun readEncrypted(uuid: UUID, done: (ByteArray) -> Unit) {
            if (!attempt.isActive || !owned()) return failProfile("profile ownership changed before read")
            readMultiframe(uuid, expectedGatt = gatt, onFailure = { failProfile("read $uuid failed") }) { ownerGatt, frame ->
                if (!attempt.isActive || ownerGatt !== gatt || !owned()) return@readMultiframe failProfile("profile ownership changed")
                val body = runCatching { decryptOwned(frame) }.getOrElse { return@readMultiframe failProfile(it.message ?: "decrypt failed") }
                done(body)
            }
        }
        fun readExactGlb(uuid: UUID, label: String, done: (Int, ByteArray) -> Unit) = readEncrypted(uuid) { body ->
            val value = app.aaps.pump.ypsopump.comm.YpsoGlb.decodeExact(body)
                ?: return@readEncrypted failProfile("$label is not exact GLB")
            done(value, body)
        }
        fun finish(date: ByteArray, time: ByteArray, activeAfter: ByteArray, eventCountAfter: Int) {
            if (eventCountAfter != eventCountBefore) return failProfile("event count changed during profile acquisition")
            val local = YpsoProfileReadback.decodeClock(date, time) ?: return failProfile("pump clock is malformed")
            val evidence = readback?.finish(
                token.generation,
                reboot,
                connectionId,
                android.os.SystemClock.elapsedRealtime(),
                activeAfter,
                local,
                Instant.now(),
                ZoneId.systemDefault(),
                maxAcquisitionMs = 5 * 60 * 1000L,
                maxClockSkew = Duration.ofSeconds(30),
                eventCount = checkNotNull(eventCountBefore),
            ) ?: return failProfile("coherence validation failed")
            synchronized(opLock) {
                if (!owned()) return failProfile("profile ownership changed before publication")
                if (!attempt.tryComplete()) return
                runCatching { persistProfile(evidence) }.getOrElse {
                    profileReadActive.set(false)
                    onDone(false)
                    return
                }
                pumpState.publishProfileEvidence(evidence)
                profileReadActive.set(false)
            }
            aapsLogger.info(LTag.PUMP, "YpsoPump coherent profile accepted: program=${evidence.activeProgram}, eventCount=${evidence.eventCount}, elapsedMs=${evidence.acquiredElapsedMs - startedElapsed}")
            onDone(true)
        }
        fun readClock(activeAfter: ByteArray) = readEncrypted(CHAR_SYSTEM_DATE) { date ->
            readEncrypted(CHAR_SYSTEM_TIME) { time ->
                readExactGlb(CHAR_EVENT_COUNT, "event count after profile acquisition") { eventCountAfter, _ ->
                    if (eventCountAfter < 0) return@readExactGlb failProfile("event count after profile acquisition is negative")
                    finish(date, time, activeAfter, eventCountAfter)
                }
            }
        }
        fun select(settingId: Int, done: (ByteArray) -> Unit) {
            if (!attempt.isActive || !owned()) return failProfile("profile ownership changed before selector $settingId")
            if (shouldYield()) return failProfile("configuration read yielded to queued command")
            if (selectedSettingId == settingId) return failProfile("selector $settingId lacks changed-identity evidence")
            val writeId = "profile-$connectionId-$settingId-${UUID.randomUUID()}"
            val started = profileSelectorCoordinator.write(
                writeId,
                owner,
                settingId,
                pumpState.masterVersion.takeIf(String::isNotBlank),
                OP_TIMEOUT_MS,
                dispatch = { frame -> writeProfileFrame(gatt, frame) },
            ) { outcome ->
                if (outcome is YpsoWriteOutcome.Verified) return@write
                val canReconcile = outcome is YpsoWriteOutcome.AcceptedUnverified ||
                    outcome is YpsoWriteOutcome.PossiblyApplied &&
                    outcome.failure.layer == YpsoWriteFailure.Layer.GATT_CALLBACK &&
                    outcome.failure.frame == EXPECTED_PROFILE_SELECTOR_FRAME_COUNT
                if (!canReconcile) return@write failProfile("selector $settingId was not semantically observable: $outcome")
                readExactGlb(YpsoWritePolicy.SETTING_ID_UUID, "selector identity") { selectedId, selectedIdBody ->
                    if (selectedId != settingId) return@readExactGlb failProfile("selector identity mismatch: requested $settingId, read $selectedId")
                    val detail = "same-link exact-GLB selector identity read-back matched setting $settingId"
                    val evidenceHash = YpsoProfileSelectorCoordinator.sha256(
                        "${owner.token.generation}|$reboot|$connectionId|$settingId|${YpsoProfileSelectorCoordinator.sha256(selectedIdBody)}".toByteArray(),
                    )
                    val reconciled = runCatching {
                        profileSelectorCoordinator.reconcileAccepted(writeId, owner, evidenceHash, detail)
                    }.getOrDefault(false)
                    if (!reconciled) return@readExactGlb failProfile("selector $settingId reconciliation failed")
                    selectedSettingId = selectedId
                    readExactGlb(CHAR_SETTING_VALUE, "setting $settingId") { _, body ->
                        done(body)
                    }
                }
            }
            if (!started) failProfile("selector $settingId could not start")
        }
        fun readRows(settingId: Int) {
            if (settingId > 61) {
                select(1) { activeAfter -> readClock(activeAfter) }
                return
            }
            select(settingId) { body ->
                if (readback?.add(settingId, body) != true) failProfile("setting $settingId could not be added")
                else scheduleProfileContinuation(Runnable { readRows(settingId + 1) })
            }
        }
        enableProfileSetup(gatt) { setup ->
            if (!setup) return@enableProfileSetup failProfile("Could not prepare the pump. Please try again.")
            scheduleProfileContinuation(Runnable {
                readExactGlb(CHAR_EVENT_COUNT, "event count before profile acquisition") { initialEventCount, _ ->
                    if (initialEventCount < 0) return@readExactGlb failProfile("event count before profile acquisition is negative")
                    eventCountBefore = initialEventCount
                    readExactGlb(YpsoWritePolicy.SETTING_ID_UUID, "initial selector identity") { initialSettingId, _ ->
                        selectedSettingId = initialSettingId
                        fun readActiveBefore() = select(1) { activeBefore ->
                            val program = YpsoBasalSchedule.Program.decode(activeBefore) ?: return@select failProfile("active program is unsupported")
                            if (activeOnly) {
                                readExactGlb(CHAR_EVENT_COUNT, "event count after active-program check") { eventCountAfter, _ ->
                                    if (eventCountAfter != eventCountBefore) return@readExactGlb failProfile("event count changed during active-program check")
                                    val previous = pumpState.profileEvidence ?: return@readExactGlb failProfile("read complete profiles first")
                                    if (previous.generation != token.generation) return@readExactGlb failProfile("configuration belongs to another pump session")
                                    if (previous.zone != ZoneId.systemDefault()) return@readExactGlb failProfile("configuration belongs to another time zone")
                                    val updated = YpsoProfileReadback.VerifiedReadback(
                                        previous.generation, reboot, connectionId, program, previous.profileA, previous.profileB,
                                        previous.acquiredElapsedMs, previous.zone, eventCountAfter, Instant.now(),
                                        activeProgramObservedElapsedMs = android.os.SystemClock.elapsedRealtime(),
                                    )
                                    synchronized(opLock) {
                                        if (!owned()) return@readExactGlb failProfile("profile ownership changed before active-program publication")
                                        if (!attempt.tryComplete()) return@readExactGlb
                                        val saved = runCatching { persistProfile(updated) }.isSuccess
                                        if (saved) pumpState.publishProfileEvidence(updated)
                                        profileReadActive.set(false)
                                        if (!saved) { onDone(false); return@readExactGlb }
                                    }
                                    onDone(true)
                                }
                                return@select
                            }
                            readback = YpsoProfileReadback(token.generation, reboot, connectionId, startedElapsed, program)
                            scheduleProfileContinuation(Runnable { readRows(14) })
                        }
                        if (initialSettingId == 1) {
                            // Establish a changed selector identity before setting 1. The witness is not
                            // part of the coherent profile bracket and its value is deliberately discarded.
                            select(14) { scheduleProfileContinuation(Runnable { readActiveBefore() }) }
                        } else {
                            readActiveBefore()
                        }
                    }
                }
            })
        }
        return attempt
    }

    /** Single-frame read (event count) — isolates KEY validity from multi-frame reliability. */
    fun readEventCount(onResult: (Int?) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { onResult(null); return }
        readMultiframe(CHAR_EVENT_COUNT, onFailure = { onResult(null) }) { _, fc ->
            val count = runCatching {
                app.aaps.pump.ypsopump.comm.YpsoGlb.decodeExact(decryptOwned(fc))?.takeIf { it >= 0 }
            }
                .getOrElse {
                    aapsLogger.error(LTag.PUMP, "YpsoPump event-count decrypt error: ${it.message}"); null
                }
            aapsLogger.debug(LTag.PUMP, "YpsoPump event-count read = $count (key ${if (count != null) "VALID" else "FAILED"})")
            onResult(count)
        }
    }

    /**
     * Immutable checkpoint scoped to a provisioned pump and reboot epoch. A later scan reads the new
     * head through the old head, verifies the shifted tail, and refreshes mutable rows before use.
     * Reconnects and local deadlines do not change event identity.
     */
    private class PartialScan(
        val generation: String,
        val reboot: Long,
        val head: YpsoHistoryEntry,
        val rows: List<YpsoHistoryEntry>,
    )

    @Volatile
    private var partialScan: PartialScan? = null

    private fun retainPartialScan(generation: String, reboot: Long, count: Int, head: YpsoHistoryEntry?, rows: List<YpsoHistoryEntry>) {
        if (head == null || count <= 0 || rows.size < 2) {
            partialScan = null
            return
        }
        if (rows.withIndex().any { (index, row) -> row.index != index } ||
            rows.zipWithNext().any { (newer, older) ->
                ((newer.sequence - older.sequence) and 0xffffffffL) !in 1..0x7fffffffL
            }
        ) return
        partialScan = PartialScan(generation, reboot, head, rows.toList())
    }

    internal fun clearPartialHistoryScan() {
        partialScan = null
    }

    fun readStableHistory(
        cursor: YpsoHistoryCursor?,
        maxRows: Int = 128,
        onResult: (YpsoHistorySnapshot?) -> Unit,
    ): HistoryReadAttempt {
        require(maxRows > 0)
        val attempt = HistoryReadAttempt()
        if (!acquirePumpOperation(historyReadActive)) {
            attempt.tryComplete()
            onResult(null)
            return attempt
        }
        val captured = synchronized(opLock) { Triple(bluetoothGatt, sessionToken, UUID.randomUUID().toString()) }
        val gatt = captured.first
        val token = captured.second
        val connectionId = captured.third
        // Prefixes are published by the row callback, never copied concurrently by the cancelling
        // thread. A late callback from this attempt must not erase a successor's checkpoint.
        attempt.onCancel = {
            if (gatt != null) cancelCurrentOperation(gatt)
            historyReadActive.set(false)
            runCatching { onResult(null) }
        }
        if (!isConnected || gatt == null || token == null || !hasCompatibleStatusProtocol()) {
            historyReadActive.set(false)
            if (attempt.tryComplete()) onResult(null)
            return attempt
        }
        val initial = session?.snapshot()
        val reboot = initial?.reboot
        val selector = findChar(gatt, YpsoWritePolicy.EVENT_INDEX_UUID)
        if (reboot == null ||
            selector == null || selector.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0 ||
            findChar(gatt, CHAR_EVENT_COUNT) == null || findChar(gatt, CHAR_EVENT_VALUE) == null
        ) {
            historyReadActive.set(false)
            if (attempt.tryComplete()) onResult(null)
            return attempt
        }
        val owner = YpsoHistorySelectorCoordinator.Owner(gatt, connectionId, token)
        var countBefore = -1
        var headBefore: YpsoHistoryEntry? = null
        val rows = mutableListOf<YpsoHistoryEntry>()
        val cached = partialScan?.takeIf { it.generation == token.generation && it.reboot == reboot.toLong() }
        var overlapPending = cached != null
        /** Selector index proven by same-link read-back within this scan; null until first proven. */
        var verifiedSelectorIndex: Int? = null

        fun owned(): Boolean = synchronized(opLock) {
            bluetoothGatt === gatt && sessionToken == token && session?.snapshot()?.reboot == reboot
        }
        fun finish(value: YpsoHistorySnapshot?) {
            if (!attempt.tryComplete()) return
            // Only a stable completed scan supersedes its checkpoint. Movement at the final anchor
            // check must leave progress available for the next overlap scan.
            if (value != null && value.countBefore == value.countAfter &&
                value.headBefore?.sequence == value.headAfter?.sequence &&
                value.headBefore?.fingerprint() == value.headAfter?.fingerprint()
            ) partialScan = null
            historyReadActive.set(false)
            onResult(value)
        }
        fun yieldAtSafeBoundary(): Boolean {
            if (!attempt.isActive) return true
            if (!attempt.shouldYield) return false
            // Keep what was already read. Routine status polling interrupts long scans every few
            // minutes, and restarting at the head each time means the scan can never reach the cursor.
            if (!overlapPending) retainPartialScan(token.generation, reboot.toLong(), countBefore, headBefore, rows)
            aapsLogger.debug(
                LTag.PUMP,
                "YpsoPump history scan yielded: freshRows=${rows.size}, retainedRows=${partialScan?.rows?.size ?: 0}, " +
                    "count=$countBefore, headSeq=${headBefore?.sequence}, oldestScannedSeq=${rows.lastOrNull()?.sequence}, " +
                    "cursorSeq=${cursor?.identity?.sequence}, seekingOverlap=$overlapPending, " +
                    "cursorSequenceAt=${rows.indexOfFirst { it.sequence == cursor?.identity?.sequence }}, " +
                    "cursorExactAt=${rows.indexOfFirst { it.sequence == cursor?.identity?.sequence && it.fingerprint() == cursor?.fingerprint }}, " +
                    "trackedTbrSeq=${cursor?.activeTbr?.identity?.sequence}, " +
                    "trackedTbrExactAt=${rows.indexOfFirst { it.sequence == cursor?.activeTbr?.identity?.sequence && it.fingerprint() == cursor?.activeTbr?.fingerprint }}",
            )
            finish(null)
            return true
        }
        fun failHistory(detail: String) {
            if (!attempt.isActive) return
            aapsLogger.error(LTag.PUMP, "YpsoPump stable history failed: $detail")
            // A failed operation does not invalidate earlier decoded rows. Reuse still requires
            // independent overlap and boundary reads on the next attempt.
            verifiedSelectorIndex = null
            finish(null)
        }
        fun readEncrypted(uuid: UUID, done: (ByteArray) -> Unit) {
            if (!attempt.isActive || !owned()) return failHistory("history ownership changed before read")
            readMultiframe(uuid, expectedGatt = gatt, onFailure = { failHistory("read $uuid failed") }) { ownerGatt, frames ->
                if (!attempt.isActive || ownerGatt !== gatt || !owned()) return@readMultiframe failHistory("history ownership changed")
                val body = runCatching { decryptOwned(frames) }.getOrElse {
                    return@readMultiframe failHistory(it.message ?: "history decrypt failed")
                }
                done(body)
            }
        }
        fun readCount(done: (Int) -> Unit) = readEncrypted(CHAR_EVENT_COUNT) { body ->
            val value = app.aaps.pump.ypsopump.comm.YpsoGlb.decodeExact(body)
                ?: return@readEncrypted failHistory("event count is not exact GLB")
            if (value < 0) return@readEncrypted failHistory("event count is negative")
            done(value)
        }
        fun dispatchChangedSelection(index: Int, previousIndex: Int, done: (YpsoHistoryEntry) -> Unit) {
            if (!attempt.isActive || !owned()) return failHistory("history ownership changed before selector $index")
            if (previousIndex == index) return failHistory("selector $index lacks changed-value acceptance evidence")
            val writeId = "history-$connectionId-$index-${UUID.randomUUID()}"
            var transportOutcomeHandled = false
            val started = historySelectorCoordinator.select(
                writeId,
                owner,
                index,
                pumpState.masterVersion.takeIf(String::isNotBlank),
                OP_TIMEOUT_MS,
                dispatch = { frame -> writeHistoryFrame(gatt, frame) },
            ) { outcome ->
                if (outcome is YpsoWriteOutcome.Verified) return@select
                if (transportOutcomeHandled) return@select
                val canReconcile = outcome is YpsoWriteOutcome.AcceptedUnverified ||
                    outcome is YpsoWriteOutcome.PossiblyApplied &&
                    outcome.failure.layer == YpsoWriteFailure.Layer.GATT_CALLBACK
                if (!canReconcile) return@select failHistory("selector $index was not semantically observable: $outcome")
                transportOutcomeHandled = true
                readEncrypted(YpsoWritePolicy.EVENT_INDEX_UUID) { selectedBody ->
                    val selected = app.aaps.pump.ypsopump.comm.YpsoGlb.decodeExact(selectedBody)
                        ?: return@readEncrypted failHistory("selector identity is not exact GLB")
                    if (selected != index) {
                        verifiedSelectorIndex = null
                        return@readEncrypted failHistory("selector identity mismatch: requested $index, read $selected")
                    }
                    verifiedSelectorIndex = selected
                    val evidenceHash = YpsoHistorySelectorCoordinator.sha256(
                        "${owner.token.generation}|$reboot|$connectionId|$index|${YpsoHistorySelectorCoordinator.sha256(selectedBody)}".toByteArray(),
                    )
                    val reconciled = runCatching {
                        historySelectorCoordinator.reconcileAccepted(
                            writeId,
                            owner,
                            evidenceHash,
                            "same-link exact-GLB selector identity read-back matched event index $index",
                        )
                    }.getOrDefault(false)
                    if (!reconciled) return@readEncrypted failHistory("selector $index reconciliation failed")
                    readEncrypted(CHAR_EVENT_VALUE) { body ->
                        val entry = YpsoHistoryEntry.decodeWire(body)
                            ?: return@readEncrypted failHistory("event $index failed strict decoding")
                        if (entry.index != index) return@readEncrypted failHistory("event row embedded index mismatch")
                        done(entry)
                    }
                }
            }
            if (!started) failHistory("selector $index could not start")
        }
        fun readSelected(index: Int, done: (YpsoHistoryEntry) -> Unit) {
            readEncrypted(CHAR_EVENT_VALUE) { body ->
                val entry = YpsoHistoryEntry.decodeWire(body)
                    ?: return@readEncrypted failHistory("event $index failed strict decoding")
                if (entry.index != index) return@readEncrypted failHistory("event row embedded index mismatch")
                done(entry)
            }
        }
        fun select(index: Int, done: (YpsoHistoryEntry) -> Unit) {
            // The selector position is already known once this scan has verified it by read-back, so a
            // sequential walk does not re-read it before every row. That pre-read was a quarter of the
            // GATT traffic and made long scans impossible to finish.
            val known = verifiedSelectorIndex
            if (known != null) {
                if (known == index) {
                    // Already selected is read-only evidence. Never dispatch a no-op selector and
                    // mistake unchanged read-back for proof that its write counter was consumed.
                    readSelected(index, done)
                } else {
                    dispatchChangedSelection(index, known, done)
                }
                return
            }
            readEncrypted(YpsoWritePolicy.EVENT_INDEX_UUID) { beforeBody ->
                val before = app.aaps.pump.ypsopump.comm.YpsoGlb.decodeExact(beforeBody)
                    ?: return@readEncrypted failHistory("pre-selector identity is not exact GLB")
                verifiedSelectorIndex = before
                if (before != index) {
                    dispatchChangedSelection(index, before, done)
                    return@readEncrypted
                }
                readSelected(index, done)
            }
        }
        fun finishScan() {
            if (yieldAtSafeBoundary()) return
            readCount { countAfter ->
                if (countAfter == 0) {
                    if (countBefore != 0) return@readCount failHistory("event count moved to empty")
                    finish(YpsoHistorySnapshot(0, 0, reboot.toLong(), reboot.toLong(), null, null, emptyList(), true))
                    return@readCount
                }
                select(0) { headAfter ->
                    finish(
                        YpsoHistorySnapshot(
                            countBefore,
                            countAfter,
                            reboot.toLong(),
                            reboot.toLong(),
                            headBefore,
                            headAfter,
                            rows.toList(),
                            fullCoverage = rows.size == countBefore,
                        ),
                    )
                }
            }
        }
        fun checkpoint() {
            if (attempt.isActive && !overlapPending) {
                retainPartialScan(token.generation, reboot.toLong(), countBefore, headBefore, rows)
            }
        }
        fun hasRequiredRows(): Boolean = cursor != null &&
            rows.any { it.sequence == cursor.identity.sequence && it.fingerprint() == cursor.fingerprint } &&
            (cursor.activeTbr == null || rows.any {
                it.sequence == cursor.activeTbr.identity.sequence && it.fingerprint() == cursor.activeTbr.fingerprint
            })

        // Cached running events can have been rewritten even if the ring's head did not change.
        // Refresh these rows before handing a snapshot to accounting.
        fun refreshMutableRows(position: Int = 0) {
            if (yieldAtSafeBoundary()) return
            val next = (position until rows.size).firstOrNull { rows[it].eventType in setOf(1, 9, 10, 17, 19, 27) }
                ?: return finishScan()
            select(next) { fresh ->
                if (fresh.sequence != rows[next].sequence || fresh.factorySeconds != rows[next].factorySeconds) {
                    partialScan = null
                    failHistory("ring moved while refreshing event $next")
                } else {
                    rows[next] = fresh
                    checkpoint()
                    refreshMutableRows(next + 1)
                }
            }
        }
        fun readRows(index: Int, limit: Int) {
            if (yieldAtSafeBoundary()) return
            if (index >= limit || hasRequiredRows()) return refreshMutableRows()
            select(index) { row ->
                rows += row
                checkpoint()
                readRows(index + 1, limit)
            }
        }
        fun seekOverlap(limit: Int) {
            if (yieldAtSafeBoundary()) return
            val old = cached
            val overlap = rows.last()
            if (old != null && overlap.sequence == old.head.sequence && overlap.fingerprint() == old.head.fingerprint()) {
                val shift = overlap.index
                val available = old.rows.take(minOf(old.rows.size, limit - shift))
                if (available.size > 1) {
                    // Re-read the far boundary: count/head alone cannot prove that an old prefix
                    // still occupies these positions after insertion or ring wrap.
                    val tail = available.last()
                    select(shift + available.lastIndex) { freshTail ->
                        overlapPending = false
                        if (freshTail.sequence == tail.sequence && freshTail.fingerprint() == tail.fingerprint()) {
                            rows += available.drop(1).map { it.copy(index = it.index + shift) }
                            rows[rows.lastIndex] = freshTail
                            aapsLogger.debug(LTag.PUMP, "YpsoPump history scan resumed with ${rows.size} cached rows (head shifted $shift)")
                        } else {
                            aapsLogger.debug(LTag.PUMP, "YpsoPump history cached boundary changed; continuing from ${rows.size} fresh rows")
                        }
                        checkpoint()
                        readRows(rows.size, limit)
                    }
                    return
                }
            }
            val passedOldHead = old != null &&
                ((old.head.sequence - overlap.sequence) and 0xffffffffL) in 1..0x7fffffffL
            if (old == null || passedOldHead || overlap.sequence == old.head.sequence || rows.size >= limit || hasRequiredRows()) {
                overlapPending = false
                checkpoint()
                readRows(rows.size, limit)
            } else {
                select(rows.size) { row ->
                    rows += row
                    seekOverlap(limit)
                }
            }
        }
        enableProfileSetup(gatt) { setup ->
            if (!setup) return@enableProfileSetup failHistory("Could not prepare the pump. Please try again.")
            if (yieldAtSafeBoundary()) return@enableProfileSetup
            readCount { count ->
                countBefore = count
                if (count == 0) {
                    finish(YpsoHistorySnapshot(0, 0, reboot.toLong(), reboot.toLong(), null, null, emptyList(), true))
                    return@readCount
                }
                select(0) { head ->
                    headBefore = head
                    val limit = minOf(count, maxRows)
                    rows += head
                    seekOverlap(limit)
                }
            }
        }
        return attempt
    }

    /**
     * One selector-only attempt to recover ownership from a durable lower bound. It never reads or
     * mutates therapy state and never treats transport ACK as acceptance; exact same-link selector
     * identity read-back is mandatory.
     */
    internal enum class LowerBoundRecoveryResult { RECOVERED, COUNTER_TOO_LOW, STOPPED }

    internal fun recoverHistorySelectorLowerBound(onResult: (LowerBoundRecoveryResult) -> Unit) {
        if (!acquirePumpOperation(historyReadActive)) {
            onResult(LowerBoundRecoveryResult.STOPPED)
            return
        }
        val captured = synchronized(opLock) { Triple(bluetoothGatt, sessionToken, UUID.randomUUID().toString()) }
        val gatt = captured.first
        val token = captured.second
        val connectionId = captured.third
        val record = session?.snapshot()
        val reboot = record?.reboot
        val selector = gatt?.let { findChar(it, YpsoWritePolicy.EVENT_INDEX_UUID) }
        if (!isConnected || gatt == null || token == null || reboot == null ||
            record.writeBootstrapState != PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND ||
            selector == null || selector.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0 ||
            findChar(gatt, CHAR_EVENT_COUNT) == null
        ) {
            historyReadActive.set(false)
            onResult(LowerBoundRecoveryResult.STOPPED)
            return
        }
        val owner = YpsoHistorySelectorCoordinator.Owner(gatt, connectionId, token)
        var completed = false
        fun finish(result: LowerBoundRecoveryResult) {
            if (completed) return
            completed = true
            historyReadActive.set(false)
            onResult(result)
        }
        fun stop(detail: String) {
            aapsLogger.info(LTag.PUMP, "YpsoPump lower-bound selector recovery stopped: $detail")
            finish(LowerBoundRecoveryResult.STOPPED)
        }
        fun owned(): Boolean = synchronized(opLock) {
            bluetoothGatt === gatt && sessionToken == token && session?.snapshot()?.reboot == reboot
        }
        fun readEncrypted(uuid: UUID, done: (ByteArray) -> Unit) {
            if (!owned()) return stop("connection owner changed before encrypted read $uuid")
            readMultiframe(uuid, expectedGatt = gatt, onFailure = { stop("encrypted read failed for $uuid") }) { ownerGatt, frames ->
                if (ownerGatt !== gatt || !owned()) return@readMultiframe stop("connection owner changed during encrypted read $uuid")
                val body = runCatching { decryptOwned(frames) }.getOrElse {
                    return@readMultiframe stop("authenticated decrypt failed for $uuid: ${it.message}")
                }
                done(body)
            }
        }
        enableProfileSetup(gatt) { setup ->
            if (!setup) return@enableProfileSetup stop("Could not prepare the pump. Please try again.")
            readEncrypted(CHAR_EVENT_COUNT) { countBody ->
                val count = app.aaps.pump.ypsopump.comm.YpsoGlb.decodeExact(countBody)
                    ?: return@readEncrypted stop("event count is not exact GLB (${countBody.size} bytes)")
                if (count < 2) return@readEncrypted stop("event count $count cannot prove a selector transition")
                readEncrypted(YpsoWritePolicy.EVENT_INDEX_UUID) { beforeBody ->
                    val before = app.aaps.pump.ypsopump.comm.YpsoGlb.decodeExact(beforeBody)
                        ?: return@readEncrypted stop("event selector is not exact GLB (${beforeBody.size} bytes)")
                    val target = if (before == 0) 1 else 0
                    if (before !in 0 until count || target >= count) {
                        return@readEncrypted stop("event selector $before is outside event count $count")
                    }
                    val writeId = "history-lower-bound-$connectionId-$target-${UUID.randomUUID()}"
                    val started = historySelectorCoordinator.recoverLowerBound(
                        writeId,
                        owner,
                        target,
                        pumpState.masterVersion.takeIf(String::isNotBlank),
                        OP_TIMEOUT_MS,
                        dispatch = { frame -> writeHistoryFrame(gatt, frame) },
                    ) { outcome ->
                        // Semantic reconciliation publishes the terminal Verified outcome through the
                        // same callback. The read-back branch below owns final RECOVERED publication.
                        if (outcome is YpsoWriteOutcome.Verified) return@recoverLowerBound
                        if (outcome is YpsoWriteOutcome.ProvenRejected && outcome.failure.isPumpCounterError()) {
                            return@recoverLowerBound finish(LowerBoundRecoveryResult.COUNTER_TOO_LOW)
                        }
                        val canReconcile = outcome is YpsoWriteOutcome.AcceptedUnverified ||
                            outcome is YpsoWriteOutcome.PossiblyApplied &&
                            outcome.failure.layer == YpsoWriteFailure.Layer.GATT_CALLBACK
                        if (!canReconcile) {
                            val detail = when (outcome) {
                                is YpsoWriteOutcome.NotSent -> "${outcome.failure.layer}: ${outcome.failure.detail}"
                                is YpsoWriteOutcome.ProvenRejected -> "${outcome.failure.layer}: ${outcome.failure.detail}"
                                is YpsoWriteOutcome.PossiblyApplied -> "${outcome.failure.layer}: ${outcome.failure.detail}"
                                else -> outcome.javaClass.simpleName
                            }
                            return@recoverLowerBound stop(
                                "selector write stopped at $detail",
                            )
                        }
                        readEncrypted(YpsoWritePolicy.EVENT_INDEX_UUID) { selectedBody ->
                            val selected = app.aaps.pump.ypsopump.comm.YpsoGlb.decodeExact(selectedBody)
                                ?: return@readEncrypted stop("selector read-back is not exact GLB (${selectedBody.size} bytes)")
                            if (selected != target) return@readEncrypted stop("selector read-back $selected did not match target $target")
                            val evidenceHash = YpsoHistorySelectorCoordinator.sha256(
                                "${owner.token.generation}|$reboot|$connectionId|$target|${YpsoHistorySelectorCoordinator.sha256(selectedBody)}".toByteArray(),
                            )
                            val reconciled = runCatching {
                                historySelectorCoordinator.reconcileLowerBoundAccepted(
                                    writeId,
                                    owner,
                                    evidenceHash,
                                    "same-link exact-GLB selector identity read-back recovered event index $target from durable lower bound",
                                )
                            }.getOrDefault(false)
                            if (reconciled) provisioning.refreshState()
                            finish(if (reconciled) LowerBoundRecoveryResult.RECOVERED else LowerBoundRecoveryResult.STOPPED)
                        }
                    }
                    if (!started) stop("selector recovery write was not started")
                }
            }
        }
    }

    /** Read CHAR_BOLUS_STATUS and parse the immediate-delivery block via [BolusCommand.decode]. */
    fun readBolusStatus(onResult: (BolusCommand?) -> Unit) {
        // These guards used to fail silently, which made a failed cancellation recovery indistinguishable
        // from one that was never attempted. Name the blocking reason so pump logs can tell them apart.
        if (bolusWriteActive.get()) {
            aapsLogger.debug(LTag.PUMP, "YpsoPump bolus-status read skipped: a bolus write owns the link")
            onResult(null); return
        }
        if (!isConnected || bluetoothGatt == null) {
            aapsLogger.debug(LTag.PUMP, "YpsoPump bolus-status read skipped: not connected")
            onResult(null); return
        }
        readMultiframe(
            CHAR_BOLUS_STATUS,
            onFailure = {
                aapsLogger.error(LTag.PUMP, "YpsoPump bolus-status read failed")
                onResult(null)
            },
        ) { _, f ->
            val cmd = runCatching {
                val body = decryptOwned(f)
                val p = YpsoCrc.validatedPayload(body) ?: throw SecurityException("invalid bolus-status CRC")
                if (diagnosticLoggingEnabled()) aapsLogger.debug(LTag.PUMP, "YpsoPump diagnostic bolus-status (${p.size}B): ${p.toHex()}")
                BolusCommand(0.0).apply { decode(p); require(success) { "invalid bolus-status layout" } }
            }.onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump bolus-status decode failed: ${it.message}") }
                .getOrNull()
            onResult(cmd)
        }
    }

    internal fun readBolusStatus(owner: BolusCommandOwner, onResult: (BolusCommand?) -> Unit) {
        val expected = owner.owner
        val validOwner = synchronized(opLock) {
            bluetoothGatt === expected.gatt && sessionToken?.generation == expected.token.generation
        }
        if (!validOwner) {
            onResult(null)
            return
        }
        readMultiframe(
            CHAR_BOLUS_STATUS,
            expectedGatt = expected.gatt as BluetoothGatt,
            bolusOwner = true,
            onFailure = { onResult(null) },
        ) { gatt, frames ->
            if (gatt !== expected.gatt || sessionToken?.generation != expected.token.generation) {
                onResult(null)
                return@readMultiframe
            }
            val command = runCatching {
                val body = decryptOwned(frames)
                val payload = YpsoCrc.validatedPayload(body) ?: error("invalid bolus-status CRC")
                BolusCommand(0.0).apply { decode(payload); require(success) }
            }.getOrNull()
            onResult(command)
        }
    }

    class BolusCommandOwner internal constructor(
        internal val owner: YpsoBolusWriteCoordinator.Owner,
    )

    internal fun currentBolusConnectionKey(): String? = synchronized(opLock) {
        val gatt = bluetoothGatt ?: return@synchronized null
        val token = sessionToken ?: return@synchronized null
        "${System.identityHashCode(gatt)}:${token.generation}"
    }

    internal fun connectionKey(owner: BolusCommandOwner): String =
        "${System.identityHashCode(owner.owner.gatt)}:${owner.owner.token.generation}"

    /**
     * Dispatch one validated bolus command on the currently authenticated connection. The required
     * [beforeDispatch] hook is the durable domain boundary and runs before the first frame can leave.
     */
    internal fun startBolus(
        writeId: String,
        request: YpsoValidatedBolusRequest,
        expectedConnectionKey: String,
        beforeDispatch: (PumpSession.Reservation) -> Unit,
        onOutcome: (YpsoWriteOutcome, BolusCommandOwner?) -> Unit,
    ) = dispatchBolus(writeId, request, null, expectedConnectionKey, beforeDispatch, onOutcome)

    internal fun cancelBolus(
        writeId: String,
        block: YpsoBolusBlock,
        expectedConnectionKey: String,
        beforeDispatch: (PumpSession.Reservation) -> Unit,
        onOutcome: (YpsoWriteOutcome, BolusCommandOwner?) -> Unit,
    ) = dispatchBolus(writeId, null, block, expectedConnectionKey, beforeDispatch, onOutcome)

    private fun dispatchBolus(
        writeId: String,
        request: YpsoValidatedBolusRequest?,
        cancelBlock: YpsoBolusBlock?,
        expectedConnectionKey: String?,
        beforeDispatch: (PumpSession.Reservation) -> Unit,
        onOutcome: (YpsoWriteOutcome, BolusCommandOwner?) -> Unit,
    ) {
        if (YpsoPumpConst.READ_ONLY_MODE) {
            onOutcome(
                YpsoWriteOutcome.NotSent(
                    writeId,
                    null,
                    YpsoWriteFailure(
                        YpsoWriteFailure.Layer.POLICY,
                        YpsoWritePolicy.BOLUS_START_STOP_UUID,
                        pumpState.masterVersion.takeIf(String::isNotBlank),
                        detail = "therapy is disabled by READ_ONLY_MODE",
                    ),
                ),
                null,
            )
            return
        }
        val captured = synchronized(opLock) { Triple(bluetoothGatt, sessionToken, UUID.randomUUID().toString()) }
        val gatt = captured.first
        val token = captured.second
        fun notSent(detail: String) = onOutcome(
            YpsoWriteOutcome.NotSent(
                writeId,
                null,
                YpsoWriteFailure(
                    YpsoWriteFailure.Layer.READINESS,
                    YpsoWritePolicy.BOLUS_START_STOP_UUID,
                    pumpState.masterVersion.takeIf(String::isNotBlank),
                    detail = detail,
                ),
            ),
            null,
        )
        writeReadinessFailure()?.let {
            notSent(it)
            return
        }
        val readyGatt = checkNotNull(gatt)
        val readyToken = checkNotNull(token)
        if (expectedConnectionKey != null && "${System.identityHashCode(readyGatt)}:${readyToken.generation}" != expectedConnectionKey) {
            notSent("The pump connection dropped. No insulin was given.")
            return
        }
        val therapyCharacteristic = findChar(readyGatt, YpsoWritePolicy.BOLUS_START_STOP_UUID)
        if (therapyCharacteristic == null ||
            therapyCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            notSent("This pump does not accept bolus commands.")
            return
        }
        if (!acquirePumpOperation(bolusWriteActive)) {
            notSent("The pump is busy. Please try again in a moment.")
            return
        }
        val owner = YpsoBolusWriteCoordinator.Owner(readyGatt, captured.third, readyToken)
        val publicOwner = BolusCommandOwner(owner)
        enableProfileSetup(readyGatt) { setup ->
            if (!setup || bluetoothGatt !== readyGatt || sessionToken?.generation != readyToken.generation) {
                releaseBolusWrite()
                notSent("Could not prepare the pump. Please try again.")
                return@enableProfileSetup
            }
            val outcome: (YpsoWriteOutcome) -> Unit = {
                if (it !is YpsoWriteOutcome.AcceptedUnverified && it !is YpsoWriteOutcome.PossiblyApplied) releaseBolusWrite()
                onOutcome(it, publicOwner)
            }
            val started = if (request != null) {
                bolusWriteCoordinator.start(
                    writeId,
                    owner,
                    request,
                    pumpState.masterVersion.takeIf(String::isNotBlank),
                    30_000,
                    beforeDispatch,
                    { frame -> writeBolusFrame(readyGatt, frame) },
                    outcome,
                )
            } else {
                bolusWriteCoordinator.cancel(
                    writeId,
                    owner,
                    checkNotNull(cancelBlock),
                    pumpState.masterVersion.takeIf(String::isNotBlank),
                    30_000,
                    beforeDispatch,
                    { frame -> writeBolusFrame(readyGatt, frame) },
                    outcome,
                )
            }
            if (!started) releaseBolusWrite()
        }
    }

    internal fun verifyBolusAccepted(owner: BolusCommandOwner, writeId: String, evidenceHash: String, detail: String): Boolean {
        val verified = bolusWriteCoordinator.reconcileAccepted(writeId, owner.owner, evidenceHash, detail)
        if (verified) releaseBolusWrite()
        return verified
    }

    internal fun recordBolusUnresolved(owner: BolusCommandOwner, writeId: String, evidenceHash: String, detail: String): Boolean {
        val recorded = bolusWriteCoordinator.recordUnresolved(writeId, owner.owner, evidenceHash, detail)
        releaseBolusWrite()
        return recorded
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
                        profileSelectorCoordinatorInstance?.ownerDisconnected(g, "remote disconnect")
                        historySelectorCoordinatorInstance?.ownerDisconnected(g, "remote disconnect")
                        bolusWriteCoordinatorInstance?.ownerDisconnected(g, "remote disconnect")
                        statusReadActive.set(false)
                        profileReadActive.set(false)
                        historyReadActive.set(false)
                        bolusWriteActive.set(false)
                        controlNotificationsEnabled = false
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
                    markConnected(controlNotificationsEnabled = false)
                    aapsLogger.info(
                        LTag.PUMP,
                        if (YpsoPumpConst.READ_ONLY_MODE) "YpsoPump authenticated; therapy writes remain disabled"
                        else writeReadinessFailure()?.let { "YpsoPump authenticated; writes are not ready: $it" }
                            ?: "YpsoPump authenticated; durable write prerequisites are currently satisfied",
                    )
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
            } else if (ch.uuid == YpsoWritePolicy.SETTING_ID_UUID && profileReadActive.get()) {
                scheduleProfileContinuation(Runnable { profileWriteTransport.onCharacteristicWrite(g, ch.uuid, status) })
            } else if (ch.uuid == YpsoWritePolicy.EVENT_INDEX_UUID && historyReadActive.get()) {
                scheduleProfileContinuation(Runnable { historyWriteTransport.onCharacteristicWrite(g, ch.uuid, status) })
            } else if (ch.uuid == YpsoWritePolicy.BOLUS_START_STOP_UUID && bolusWriteActive.get()) {
                scheduleProfileContinuation(Runnable { bolusWriteTransport.onCharacteristicWrite(g, ch.uuid, status) })
            } else completeCurrent(g, ch.uuid, null, status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            val notification = synchronized(opLock) {
                if (!ownsGattLocked(g)) return
                aapsLogger.debug(LTag.PUMP, "YpsoPump notify ${ch.uuid}: ${value.joinToString("") { "%02x".format(it) }}")
                if (ch.uuid != YpsoWritePolicy.CONTROL_NOTIFY_UUID) null
                else YpsoBolusNotification.decode(value)
            } ?: return
            // Dispatch outside the transport lock: observers persist durable bolus state.
            aapsLogger.debug(
                LTag.PUMP,
                "YpsoPump bolus notification fast=${notification.fastStatusCode}/${notification.fastSequence} " +
                    "slow=${notification.slowStatusCode}/${notification.slowSequence}",
            )
            runCatching { onBolusNotification?.invoke(notification) }
                .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump bolus notification observer failed: ${it.message}") }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            completeCurrent(g, ch.uuid, value.copyOf(), status)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            completeCurrent(g, ch.uuid, ch.value?.copyOf(), status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            completeCurrent(g, descriptor.uuid, null, status)
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
            profileSelectorCoordinatorInstance?.ownerDisconnected(g, message)
            historySelectorCoordinatorInstance?.ownerDisconnected(g, message)
            bolusWriteCoordinatorInstance?.ownerDisconnected(g, message)
            statusReadActive.set(false)
            bolusWriteActive.set(false)
            profileReadActive.set(false)
            historyReadActive.set(false)
            controlNotificationsEnabled = false
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
            bluetoothGatt === g && if (remoteWrite == YpsoRemoteWrite.SETTINGS_SELECTOR) {
                characteristic.uuid == YpsoWritePolicy.SETTING_ID_UUID && authorizedProfileFrame === value
            } else if (remoteWrite == YpsoRemoteWrite.HISTORY_SELECTOR) {
                characteristic.uuid == YpsoWritePolicy.EVENT_INDEX_UUID && authorizedHistoryFrame === value
            } else if (remoteWrite == YpsoRemoteWrite.THERAPY_COMMAND) {
                !YpsoPumpConst.READ_ONLY_MODE && characteristic.uuid == YpsoWritePolicy.BOLUS_START_STOP_UUID &&
                    authorizedBolusFrame === value
            } else YpsoWritePolicy.allowsCharacteristic(
                remoteWrite, characteristic.uuid, value,
                runCatching { authPassword(pumpState.pumpAddress) }.getOrDefault(byteArrayOf()),
                pumpState.connectionState == ConnectionState.READY
            )
        }
        if (!authorized) {
            aapsLogger.error(LTag.PUMP, "YpsoPump policy blocked $remoteWrite write to ${characteristic.uuid}")
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
                remoteWrite,
                descriptor.characteristic?.uuid,
                descriptor.uuid,
                value
            )) {
            aapsLogger.error(LTag.PUMP, "YpsoPump policy blocked $remoteWrite descriptor write")
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
