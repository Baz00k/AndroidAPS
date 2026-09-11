package app.aaps.ypso.writebench

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import app.aaps.pump.ypsopump.ble.YpsoArtifactPolicy
import app.aaps.pump.ypsopump.ble.YpsoAuthentication
import app.aaps.pump.ypsopump.ble.YpsoBenchWriteCoordinator
import app.aaps.pump.ypsopump.ble.YpsoCommandReadiness
import app.aaps.pump.ypsopump.ble.YpsoRemoteWrite
import app.aaps.pump.ypsopump.ble.YpsoSemanticEvidence
import app.aaps.pump.ypsopump.ble.YpsoSerializedWriteTransport
import app.aaps.pump.ypsopump.ble.YpsoWriteFailure
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.crypto.SessionJournal
import app.aaps.pump.ypsopump.data.YpsoFirmwareVersion
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocument
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocumentParser
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Separate-UID Bluetooth bench. Its only non-auth characteristic writes are exact GLB selectors,
 * and its only descriptor write is the reviewed control-notification CCCD enable.
 */
class BenchActivity : Activity() {
    private val recorder by lazy { BenchEvidenceRecorder(this) }
    private val session by lazy { PumpSession(SessionJournal(this)) }
    private val crypto = SessionCrypto()
    private val readiness = YpsoCommandReadiness()
    private val handler by lazy { android.os.Handler(mainLooper) }
    private val deadlines = mutableSetOf<Runnable>()
    private val transport by lazy {
        YpsoSerializedWriteTransport(
            { action, delay ->
                synchronized(deadlines) { deadlines += action }
                handler.postDelayed(action, delay)
            },
            { action ->
                synchronized(deadlines) { deadlines -= action }
                handler.removeCallbacks(action)
            },
            recorder,
        )
    }
    private val coordinator by lazy { YpsoBenchWriteCoordinator(session, crypto, readiness, transport) }
    private var document: YpsoSessionDocument? = null
    private var token: PumpSession.Token? = null
    private var gatt: BluetoothGatt? = null
    private var connectionId = ""
    private var pendingRead: ReadTransaction? = null
    private var pendingFirmwareRead: BluetoothGatt? = null
    private var firmware: String? = null
    private var selector: Selector? = null
    private var writeId = ""
    private var injectedDisconnectAfterFrame: Int? = null
    private var injectedIgnoredCallbackFrame: Int? = null
    private var dispatchedFrames = 0
    private var observedWriteCallbacks = 0
    private var expectedDescriptor: BluetoothGattDescriptor? = null
    private var runLeaseHeld = false
    private var handshakePhase = HandshakePhase.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            when (intent.getStringExtra("action") ?: "inspect") {
                "install" -> install()
                "inspect" -> inspect()
                "run-selector" -> runSelector()
                "reconcile" -> reconcile()
                else -> error("Unknown action")
            }
        }.onFailure {
            cleanupAfterFailure()
            report("ERROR:${it.javaClass.simpleName}:${it.message}")
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        recorder.fact(
            "ConcurrentInvocationRejected",
            JSONObject().put("write_id", writeId.ifBlank { JSONObject.NULL }),
        )
        android.util.Log.w("YpsoWriteBench", "Concurrent invocation rejected while this activity owns the process")
    }

    override fun onDestroy() {
        if (gatt != null) cleanupAfterFailure()
        super.onDestroy()
    }

    private fun install() {
        val doc = loadDocument()
        try {
            val baseline = JSONObject(File(filesDir, "write-baseline.json").readText())
            require(baseline.keys().asSequence().toSet() == setOf("pump", "key_id", "reboot", "read", "write", "source"))
            require(baseline.getString("pump").equals(doc.mac, true)) { "Baseline pump mismatch" }
            require(baseline.getString("key_id") == PumpSession.fingerprint(doc.sharedKey)) { "Baseline key mismatch" }
            val reboot = baseline.getInt("reboot")
            doc.rebootCounter?.let { require(it == reboot) { "Document reboot hint conflicts with measured baseline" } }
            val read = baseline.getLong("read")
            val write = baseline.getLong("write")
            require(read >= 0 && write >= 0 && baseline.getString("source").isNotBlank())
            session.provisionReadBaseline(doc.mac, doc.sharedKey, reboot, read)
            session.provisionBenchWriteBaseline(doc.mac, doc.sharedKey, reboot, write)
            recorder.fact(
                "BaselineInstalled",
                JSONObject()
                    .put("pump_hash", hash(doc.mac.toByteArray()))
                    .put("key_id", baseline.getString("key_id"))
                    .put("reboot", reboot)
                    .put("read", read)
                    .put("write", write)
                    .put("source", baseline.getString("source")),
            )
            report("INSTALLED:reboot=$reboot,read=$read,write=$write")
        } finally {
            doc.sharedKey.fill(0)
        }
    }

    private fun inspect() {
        val doc = loadDocument()
        try {
            val opened = session.open(doc.mac, doc.sharedKey)
            val record = checkNotNull(session.snapshot())
            val reservation = record.reservation
            report(
                "SESSION:generation=${opened.generation},reboot=${record.reboot},read=${record.read},write=${record.write}," +
                    "pending=${reservation?.operationId ?: "none"},phase=${reservation?.phase ?: "none"}",
            )
        } finally {
            doc.sharedKey.fill(0)
            session.quiesce()
        }
    }

    @SuppressLint("MissingPermission")
    private fun runSelector() {
        check(runLease.compareAndSet(false, true)) { "Another selector run already owns this process" }
        runLeaseHeld = true
        require(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            "Grant BLUETOOTH_CONNECT before running the bench"
        }
        val doc = loadDocument().also { document = it }
        token = session.open(doc.mac, doc.sharedKey)
        require(session.snapshot()?.reservation == null || session.snapshot()?.reservation?.phase == PumpSession.Phase.VERIFIED) {
            "An earlier write requires reconciliation"
        }
        selector =
            Selector.parse(
                intent.getStringExtra("selector_type") ?: error("selector_type required"),
                intent.getIntExtra("selector", Int.MIN_VALUE),
            )
        writeId = intent.getStringExtra("write_id")?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        injectedDisconnectAfterFrame = intent.intExtraOrNull("disconnect_after_frame")
        injectedIgnoredCallbackFrame = intent.intExtraOrNull("ignore_callback_frame")
        recorder.fact(
            "RunRequested",
            JSONObject()
                .put("write_id", writeId)
                .put("selector_type", selector!!.name)
                .put("selector", selector!!.value)
                .put("disconnect_after_frame", injectedDisconnectAfterFrame ?: JSONObject.NULL)
                .put("ignore_callback_frame", injectedIgnoredCallbackFrame ?: JSONObject.NULL),
        )
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        check(manager.adapter?.isEnabled == true) { "Bluetooth unavailable" }
        val device = manager.adapter.getRemoteDevice(doc.mac)
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "Existing OS bond required" }
        connectionId = UUID.randomUUID().toString()
        handshakePhase = HandshakePhase.CONNECTING
        gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
        checkNotNull(gatt) { "connectGatt returned null" }
        report("CONNECTING:write_id=$writeId")
    }

    private fun reconcile() {
        val doc = loadDocument()
        try {
            val opened = session.open(doc.mac, doc.sharedKey)
            val id = intent.getStringExtra("write_id") ?: error("write_id required")
            val semantic =
                YpsoSemanticEvidence.valueOf(
                    (intent.getStringExtra("semantic") ?: error("semantic required")).uppercase(),
                )
            val counter =
                when (intent.getStringExtra("counter")) {
                    null, "none" -> null
                    "accepted" -> PumpSession.WriteResolution.ACCEPTED
                    "consumed" -> PumpSession.WriteResolution.REJECTED_COUNTER_CONSUMED
                    "not-consumed" -> PumpSession.WriteResolution.REJECTED_COUNTER_NOT_CONSUMED
                    else -> error("counter must be accepted, consumed, not-consumed or none")
                }
            val detail =
                intent.getStringExtra("detail")?.takeIf(String::isNotBlank)
                    ?: error("measured evidence detail required")
            val expectedEvidenceHash =
                intent
                    .getStringExtra("evidence_sha256")
                    ?.lowercase()
                    ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                    ?: error("evidence_sha256 must be the 64-character SHA-256 of the reviewed evidence bundle")
            val evidenceBundle = File(filesDir, "reconciliation-evidence.bin")
            require(evidenceBundle.isFile && evidenceBundle.length() in 1..MAX_EVIDENCE_BYTES) {
                "Copy a non-empty reconciliation-evidence.bin (maximum $MAX_EVIDENCE_BYTES bytes) into app files"
            }
            val evidenceHash = hash(evidenceBundle)
            require(evidenceHash == expectedEvidenceHash) { "Reviewed evidence bundle SHA-256 mismatch" }
            recorder.fact(
                "ReconciliationRequested",
                JSONObject()
                    .put("write_id", id)
                    .put("semantic", semantic.name)
                    .put("counter_resolution", counter?.name ?: JSONObject.NULL)
                    .put("evidence_sha256", evidenceHash)
                    .put("detail", detail),
            )
            val outcome =
                coordinator.reconcilePersisted(
                    YpsoBenchWriteCoordinator.Owner(Any(), "offline-reconciliation", opened),
                    id,
                    YpsoBenchWriteCoordinator.Reconciliation(semantic, counter, evidenceHash, detail),
                ) ?: error("No matching pending write")
            recorder.fact(
                "PersistedReconciliation",
                JSONObject()
                    .put("write_id", id)
                    .put("semantic", semantic.name)
                    .put("counter_resolution", counter?.name ?: JSONObject.NULL)
                    .put("evidence_sha256", evidenceHash)
                    .put("detail", detail)
                    .put("outcome", outcome.javaClass.simpleName),
            )
            report("RECONCILED:${outcome.javaClass.simpleName}")
        } finally {
            doc.sharedKey.fill(0)
            session.quiesce()
        }
    }

    @SuppressLint("MissingPermission")
    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                owner: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (owner !== gatt) return
                if (newState == BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS &&
                    handshakePhase == HandshakePhase.CONNECTING
                ) {
                    handshakePhase = HandshakePhase.DISCOVERING
                    val readyOwner = readinessOwner(owner)
                    readiness.connected(readyOwner)
                    if (!owner.discoverServices()) fail("service discovery dispatch refused")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    recorder.fact(
                        "GattDisconnected",
                        JSONObject().put("write_id", writeId.ifBlank { JSONObject.NULL }).put("status", status),
                    )
                    val ownedWrite = coordinator.ownerDisconnected(owner, "GATT disconnected (status=$status)")
                    readiness.disconnected(owner)
                    close(owner)
                    if (!ownedWrite) report("DISCONNECTED:status=$status")
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("connection status=$status")
                }
            }

            override fun onServicesDiscovered(
                owner: BluetoothGatt,
                status: Int,
            ) {
                if (owner !== gatt || handshakePhase != HandshakePhase.DISCOVERING) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("service discovery status=$status")
                    return
                }
                val auth =
                    find(owner, YpsoWritePolicy.AUTH_UUID) ?: run {
                        fail("AUTH characteristic missing")
                        return
                    }
                val password = YpsoAuthentication.password(checkNotNull(document).mac)
                check(
                    YpsoWritePolicy.allowsCharacteristic(
                        YpsoArtifactPolicy.NON_THERAPY_BENCH,
                        YpsoRemoteWrite.AUTHENTICATION,
                        auth.uuid,
                        password,
                        password,
                        true,
                    ),
                )
                handshakePhase = HandshakePhase.AUTHENTICATING
                if (!writeCharacteristic(owner, auth, password)) fail("AUTH dispatch refused")
            }

            override fun onCharacteristicWrite(
                owner: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (owner !== gatt) return
                if (characteristic.uuid == YpsoWritePolicy.AUTH_UUID) {
                    if (handshakePhase != HandshakePhase.AUTHENTICATING) return
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail("AUTH status=$status", YpsoWriteFailure.Layer.GATT_CALLBACK, YpsoWritePolicy.AUTH_UUID, status)
                        return
                    }
                    handshakePhase = HandshakePhase.ENABLING_SETUP
                    readiness.authenticated(readinessOwner(owner))
                    enableRequiredSetup(owner)
                    return
                }
                if (!transport.owns(owner, characteristic.uuid, checkNotNull(selector).category)) return
                observedWriteCallbacks++
                if (injectedIgnoredCallbackFrame == observedWriteCallbacks) {
                    recorder.fact(
                        "InjectedIgnoredWriteCallback",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("frame", observedWriteCallbacks)
                            .put("status", status),
                    )
                    return
                }
                transport.onCharacteristicWrite(owner, characteristic.uuid, status)
            }

            override fun onDescriptorWrite(
                owner: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (owner !== gatt ||
                    handshakePhase != HandshakePhase.ENABLING_SETUP ||
                    descriptor !== expectedDescriptor ||
                    descriptor.uuid != YpsoWritePolicy.CCCD_UUID ||
                    descriptor.characteristic?.uuid != YpsoWritePolicy.CONTROL_NOTIFY_UUID
                ) {
                    return
                }
                expectedDescriptor = null
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(
                        "required CCCD status=$status",
                        YpsoWriteFailure.Layer.GATT_CALLBACK,
                        YpsoWritePolicy.CONTROL_NOTIFY_UUID,
                        status,
                    )
                    return
                }
                handshakePhase = HandshakePhase.READING_FIRMWARE
                readiness.requiredSetupVerified(readinessOwner(owner))
                readFirmware(owner)
            }

            override fun onCharacteristicRead(
                owner: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (consumeFirmwareRead(owner, characteristic.uuid, value.copyOf(), status)) return
                consumeRead(owner, characteristic.uuid, value.copyOf(), status)
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(
                owner: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (consumeFirmwareRead(owner, characteristic.uuid, characteristic.value?.copyOf(), status)) return
                consumeRead(owner, characteristic.uuid, characteristic.value?.copyOf(), status)
            }
        }

    @SuppressLint("MissingPermission")
    private fun readFirmware(owner: BluetoothGatt) {
        val characteristic =
            find(owner, MASTER_VERSION_UUID) ?: run {
                fail("master firmware characteristic missing")
                return
            }
        pendingFirmwareRead = owner
        if (!owner.readCharacteristic(characteristic)) {
            pendingFirmwareRead = null
            fail("master firmware read dispatch refused")
        }
    }

    private fun consumeFirmwareRead(
        owner: BluetoothGatt,
        uuid: UUID,
        value: ByteArray?,
        status: Int,
    ): Boolean {
        if (pendingFirmwareRead !== owner || uuid != MASTER_VERSION_UUID) return false
        pendingFirmwareRead = null
        if (status != BluetoothGatt.GATT_SUCCESS || value == null) {
            fail(
                "master firmware read status=$status",
                YpsoWriteFailure.Layer.GATT_CALLBACK,
                MASTER_VERSION_UUID,
                status,
            )
            return true
        }
        firmware = YpsoFirmwareVersion.fromWire(value)?.toString() ?: run {
            fail("master firmware value malformed")
            return true
        }
        recorder.fact("FirmwareRead", JSONObject().put("write_id", writeId).put("firmware", firmware))
        handshakePhase = HandshakePhase.PRIMING_READ
        primeRead(owner)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun enableRequiredSetup(owner: BluetoothGatt) {
        val characteristic =
            find(owner, YpsoWritePolicy.CONTROL_NOTIFY_UUID) ?: run {
                fail("control notification characteristic missing")
                return
            }
        val descriptor =
            characteristic.getDescriptor(YpsoWritePolicy.CCCD_UUID) ?: run {
                fail("control notification CCCD missing")
                return
            }
        val enableNotification = byteArrayOf(1, 0)
        check(
            YpsoWritePolicy.allowsDescriptor(
                YpsoArtifactPolicy.NON_THERAPY_BENCH,
                YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR,
                characteristic.uuid,
                descriptor.uuid,
                enableNotification,
            ),
        )
        expectedDescriptor = descriptor
        if (!owner.setCharacteristicNotification(characteristic, true) || !writeDescriptor(owner, descriptor, enableNotification)) {
            expectedDescriptor = null
            fail("required CCCD dispatch refused")
        }
    }

    private fun primeRead(owner: BluetoothGatt) =
        readEncrypted(owner, EVENT_COUNT_UUID) { result ->
            result.fold(
                onSuccess = { body ->
                    val count = YpsoGlb.find(body)
                    val crcValid = YpsoCrc.isValid(body)
                    recorder.fact(
                        if (crcValid) "PrimeReadVerified" else "PrimeReadRejected",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("body_size", body.size)
                            .put("body_sha256", hash(body))
                            .put("glb", count ?: JSONObject.NULL)
                            .put("crc_valid", crcValid),
                    )
                    if (!crcValid) {
                        fail("prime read CRC invalid")
                        return@fold
                    }
                    handshakePhase = HandshakePhase.READY
                    readiness.readVerified(readinessOwner(owner))
                    startSelector(owner)
                },
                onFailure = { fail("prime read failed: ${it.message}") },
            )
        }

    private fun startSelector(owner: BluetoothGatt) {
        val selected = checkNotNull(selector)
        val operationOwner = YpsoBenchWriteCoordinator.Owner(owner, connectionId, checkNotNull(token))
        val started =
            coordinator.writeSelector(
                writeId,
                operationOwner,
                selected.category,
                selected.indexUuid,
                YpsoGlb.encode(selected.value),
                firmware = checkNotNull(firmware),
                deadlineMs = intent.getLongExtra("deadline_ms", 8_000L),
                dispatch = { frame ->
                    dispatchedFrames++
                    val accepted = find(owner, selected.indexUuid)?.let { writeCharacteristic(owner, it, frame) } == true
                    if (accepted && injectedDisconnectAfterFrame == dispatchedFrames) {
                        recorder.fact(
                            "InjectedDisconnectAfterDispatch",
                            JSONObject().put("write_id", writeId).put("frame", dispatchedFrames),
                        )
                        owner.disconnect()
                    }
                    accepted
                },
                onOutcome = { outcome ->
                    when (outcome) {
                        is YpsoWriteOutcome.AcceptedUnverified -> readBack(owner, selected)
                        is YpsoWriteOutcome.NotSent -> {
                            close(owner)
                            report("OUTCOME:NotSent")
                        }
                        is YpsoWriteOutcome.ProvenRejected -> {
                            close(owner)
                            report("OUTCOME:ProvenRejected; reconciliation required")
                        }
                        is YpsoWriteOutcome.PossiblyApplied -> {
                            close(owner)
                            report("OUTCOME:PossiblyApplied; reconciliation required")
                        }
                        is YpsoWriteOutcome.Verified -> {
                            close(owner)
                            report("OUTCOME:Verified")
                        }
                    }
                },
            )
        if (!started) close(owner)
    }

    private fun readBack(
        owner: BluetoothGatt,
        selected: Selector,
    ) = readEncrypted(owner, selected.valueUuid) { result ->
        result.fold(
            onSuccess = { body ->
                recorder.fact(
                    "SelectorReadBack",
                    JSONObject()
                        .put("write_id", writeId)
                        .put("selector_type", selected.name)
                        .put("selector", selected.value)
                        .put("body_size", body.size)
                        .put("body_sha256", hash(body))
                        .put("glb", YpsoGlb.find(body) ?: JSONObject.NULL)
                        .put("crc_valid", YpsoCrc.isValid(body))
                        .put("embedded_history_index", historyIndex(body) ?: JSONObject.NULL),
                )
                close(owner)
                report("OUTCOME:AcceptedUnverified; read-back captured; explicit reconciliation required")
            },
            onFailure = {
                recorder.fact("SelectorReadBackFailed", JSONObject().put("write_id", writeId).put("detail", it.message))
                close(owner)
                report("OUTCOME:AcceptedUnverified; read-back failed; explicit reconciliation required")
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun readEncrypted(
        owner: BluetoothGatt,
        uuid: UUID,
        done: (Result<ByteArray>) -> Unit,
    ) {
        check(pendingRead == null) { "Overlapping EXTREAD transaction" }
        pendingRead = ReadTransaction(owner, uuid, done)
        val characteristic = find(owner, uuid)
        if (characteristic == null || !owner.readCharacteristic(characteristic)) {
            finishRead(Result.failure(IllegalStateException("read dispatch refused for $uuid")))
        }
    }

    @SuppressLint("MissingPermission")
    private fun consumeRead(
        owner: BluetoothGatt,
        uuid: UUID,
        value: ByteArray?,
        status: Int,
    ) {
        val read = pendingRead ?: return
        if (read.owner !== owner || uuid != read.expectedUuid) return
        if (status != BluetoothGatt.GATT_SUCCESS || value == null) {
            recorder.fact(
                "EncryptedReadFailed",
                JSONObject()
                    .put("write_id", writeId)
                    .put("layer", YpsoWriteFailure.Layer.GATT_CALLBACK.name)
                    .put("characteristic", uuid.toString())
                    .put("firmware", firmware ?: JSONObject.NULL)
                    .put("code", status),
            )
            finishRead(Result.failure(IllegalStateException("read $uuid status=$status")))
            return
        }
        val total =
            YpsoFraming.validateFrame(value, read.frames.size + 1, read.total)
                ?: run {
                    finishRead(Result.failure(IllegalStateException("invalid frame ${read.frames.size + 1}")))
                    return
                }
        read.frames += value
        if (read.total == 0) read.total = total
        if (read.frames.size < read.total) {
            read.expectedUuid = EXTENDED_READ_UUID
            val next = find(owner, EXTENDED_READ_UUID)
            if (next == null || !owner.readCharacteristic(next)) {
                finishRead(Result.failure(IllegalStateException("EXTREAD dispatch refused")))
            }
            return
        }
        val result =
            runCatching {
                val encrypted = YpsoFraming.parseMultiFrameRead(read.frames)
                val operation = session.begin(checkNotNull(token))
                try {
                    session.decrypt(checkNotNull(token), operation, encrypted, crypto)
                } finally {
                    session.finish(checkNotNull(token), operation)
                }
            }
        finishRead(result)
    }

    private fun finishRead(result: Result<ByteArray>) {
        val done = pendingRead?.done ?: return
        pendingRead = null
        done(result)
    }

    @SuppressLint("MissingPermission")
    private fun fail(
        detail: String,
        layer: YpsoWriteFailure.Layer? = null,
        characteristic: UUID? = null,
        code: Int? = null,
    ) {
        recorder.fact(
            "BenchFailure",
            JSONObject()
                .put("write_id", writeId.ifBlank { JSONObject.NULL })
                .put("layer", layer?.name ?: JSONObject.NULL)
                .put("characteristic", characteristic?.toString() ?: JSONObject.NULL)
                .put("firmware", firmware ?: JSONObject.NULL)
                .put("code", code ?: JSONObject.NULL)
                .put("detail", detail),
        )
        gatt?.let {
            coordinator.ownerDisconnected(it, detail)
            close(it)
        }
        report("ERROR:$detail")
    }

    @SuppressLint("MissingPermission")
    private fun close(owner: BluetoothGatt) {
        if (gatt !== owner) return
        gatt = null
        pendingRead = null
        pendingFirmwareRead = null
        firmware = null
        expectedDescriptor = null
        handshakePhase = HandshakePhase.IDLE
        readiness.disconnected(owner)
        coordinator.releaseOwner(owner)
        runCatching { owner.disconnect() }
        runCatching { owner.close() }
        session.quiesce()
        token = null
        document?.sharedKey?.fill(0)
        document = null
        releaseRunLease()
    }

    @SuppressLint("MissingPermission")
    private fun cleanupAfterFailure() {
        val owner = gatt
        if (owner != null) {
            coordinator.ownerDisconnected(owner, "activity action failed")
            close(owner)
            return
        }
        pendingRead = null
        pendingFirmwareRead = null
        firmware = null
        expectedDescriptor = null
        handshakePhase = HandshakePhase.IDLE
        session.quiesce()
        token = null
        document?.sharedKey?.fill(0)
        document = null
        releaseRunLease()
    }

    private fun releaseRunLease() {
        if (!runLeaseHeld) return
        runLeaseHeld = false
        runLease.set(false)
    }

    private fun readinessOwner(owner: BluetoothGatt) = YpsoCommandReadiness.Owner(owner, connectionId, checkNotNull(token).generation)

    private fun find(
        owner: BluetoothGatt,
        uuid: UUID,
    ): BluetoothGattCharacteristic? = owner.services.firstNotNullOfOrNull { it.getCharacteristic(uuid) }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "NewApi")
    private fun writeCharacteristic(
        owner: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            owner.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = value
            owner.writeCharacteristic(characteristic)
        }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "NewApi")
    private fun writeDescriptor(
        owner: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            owner.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            owner.writeDescriptor(descriptor)
        }

    private fun loadDocument(): YpsoSessionDocument = YpsoSessionDocumentParser.parse(File(filesDir, "ypso-keys.json").readBytes())

    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun historyIndex(body: ByteArray): Int? {
        val payload = if (YpsoCrc.isValid(body)) body.copyOfRange(0, body.size - 2) else body
        if (payload.size < 17) return null
        return (payload[15].toInt() and 0xff) or ((payload[16].toInt() and 0xff) shl 8)
    }

    private fun android.content.Intent.intExtraOrNull(name: String): Int? =
        takeIf { hasExtra(name) }?.getIntExtra(name, -1)?.takeIf { it > 0 }

    private fun report(value: String) {
        File(filesDir, "result.txt").outputStream().use { out ->
            out.write(value.toByteArray())
            out.fd.sync()
        }
        android.util.Log.i("YpsoWriteBench", value)
        if (!value.startsWith("CONNECTING:")) finish()
    }

    private data class ReadTransaction(
        val owner: BluetoothGatt,
        var expectedUuid: UUID,
        val done: (Result<ByteArray>) -> Unit,
        val frames: MutableList<ByteArray> = mutableListOf(),
        var total: Int = 0,
    )

    private enum class HandshakePhase {
        IDLE,
        CONNECTING,
        DISCOVERING,
        AUTHENTICATING,
        ENABLING_SETUP,
        READING_FIRMWARE,
        PRIMING_READ,
        READY,
    }

    private data class Selector(
        val name: String,
        val value: Int,
        val category: YpsoRemoteWrite,
        val indexUuid: UUID,
        val valueUuid: UUID,
    ) {
        companion object {
            fun parse(
                name: String,
                value: Int,
            ): Selector {
                require(value >= 0) { "selector must be a non-negative signed integer" }
                return when (name.lowercase()) {
                    "event" ->
                        Selector(
                            "event",
                            value,
                            YpsoRemoteWrite.HISTORY_SELECTOR,
                            YpsoWritePolicy.EVENT_INDEX_UUID,
                            EVENT_VALUE_UUID,
                        )
                    "alarm" ->
                        Selector(
                            "alarm",
                            value,
                            YpsoRemoteWrite.HISTORY_SELECTOR,
                            YpsoWritePolicy.ALARM_INDEX_UUID,
                            ALARM_VALUE_UUID,
                        )
                    "system" ->
                        Selector(
                            "system",
                            value,
                            YpsoRemoteWrite.HISTORY_SELECTOR,
                            YpsoWritePolicy.SYSTEM_INDEX_UUID,
                            SYSTEM_VALUE_UUID,
                        )
                    "setting" ->
                        Selector(
                            "setting",
                            value,
                            YpsoRemoteWrite.SETTINGS_SELECTOR,
                            YpsoWritePolicy.SETTING_ID_UUID,
                            SETTING_VALUE_UUID,
                        )
                    else -> error("selector_type must be event, alarm, system or setting")
                }
            }
        }
    }

    private companion object {
        val EVENT_COUNT_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecb3b7bc5")
        val MASTER_VERSION_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb0147bc5")
        val EVENT_VALUE_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecd3b7bc5")
        val ALARM_VALUE_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeca3b7bc5")
        val SYSTEM_VALUE_UUID: UUID = UUID.fromString("ae3022af-2ec8-bf88-e64c-da68c9a3891a")
        val SETTING_VALUE_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb4147bc5")
        val EXTENDED_READ_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff000000ff")
        const val MAX_EVIDENCE_BYTES = 64L * 1024 * 1024
        val runLease = AtomicBoolean(false)
    }
}
