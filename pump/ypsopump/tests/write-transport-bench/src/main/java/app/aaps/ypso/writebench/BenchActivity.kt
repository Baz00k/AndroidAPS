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
import android.os.Build
import android.os.Bundle
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
import app.aaps.pump.ypsopump.data.YpsoBasalSchedule
import app.aaps.pump.ypsopump.data.YpsoProfileReadback
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocument
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocumentParser
import app.aaps.pump.ypsopump.provisioning.YpsoOwnershipHandoff
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import android.content.pm.PackageManager
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
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
    private val callbackSequencer by lazy { GattCallbackSequencer(handler::post) }
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
    private var pendingCapabilityRead: CapabilityRead? = null
    private var pendingCapabilityIndex = 0
    private var firmware: String? = null
    private var supervisorFirmware: String? = null
    private var controlVersion: String? = null
    private var selector: Selector? = null
    private var writeId = ""
    private var runKind = RunKind.SELECTOR
    private var omittedReadinessStage: String? = null
    private var forwardGap = 0
    private var injectedDisconnectAfterFrame: Int? = null
    private var injectedIgnoredCallbackFrame: Int? = null
    private var injectedDuplicateCallbackAfterFrame: Int? = null
    private var dispatchedFrames = 0
    private var observedWriteCallbacks = 0
    private var expectedAuthCharacteristic: BluetoothGattCharacteristic? = null
    private var expectedSelectorCharacteristic: BluetoothGattCharacteristic? = null
    private var controlNotificationCharacteristic: BluetoothGattCharacteristic? = null
    private var expectedDescriptor: BluetoothGattDescriptor? = null
    private var runLeaseHeld = false
    private var handshakePhase = HandshakePhase.IDLE
    private var primeEventCount: Int? = null
    private var primeEventCountBody: ByteArray? = null
    private var primePumpReboot: Int? = null
    private var profileReadback: YpsoProfileReadback? = null
    private var profileSelectedSettingId: Int? = null
    private var profileStartedElapsed = 0L
    private var profileRows = linkedMapOf<Int, Int>()
    private var profilePaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            when (intent.getStringExtra("action") ?: "inspect") {
                "install" -> install()
                "inspect" -> inspect()
                "run-selector" -> runConnection(RunKind.SELECTOR)
                "converge-ambiguity" -> runConnection(RunKind.AMBIGUITY_CONVERGENCE)
                "recover-settings-counter" -> runConnection(RunKind.SETTINGS_COUNTER_RECOVERY)
                "jump-settings-counter" -> runConnection(RunKind.SETTINGS_COUNTER_JUMP)
                "duplicate-counter-probe" -> runConnection(RunKind.DUPLICATE_COUNTER_PROBE)
                "bootstrap-new-epoch" -> runConnection(RunKind.BOOTSTRAP_NEW_EPOCH)
                "observe-selector" -> runConnection(RunKind.OBSERVE_SELECTOR)
                "read-selector-state" -> runConnection(RunKind.READ_SELECTOR_STATE)
                "observe-reboot" -> runConnection(RunKind.OBSERVE_REBOOT)
                "record-bootstrap-reference" -> runConnection(RunKind.RECORD_BOOTSTRAP_REFERENCE)
                "read-history-counts" -> runConnection(RunKind.READ_HISTORY_COUNTS)
                "capture-current-history" -> runConnection(RunKind.CAPTURE_CURRENT_HISTORY)
                "readiness-probe" -> runConnection(RunKind.READINESS_PROBE)
                "acquire-profile" -> runConnection(RunKind.PROFILE_ACQUISITION)
                "export-ownership-handoff" -> exportOwnershipHandoff()
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
            val baselineFile = File(filesDir, "write-baseline.json")
            if (!baselineFile.exists()) {
                val installation =
                    session.install(
                        PumpSession.Provisioning(
                            pump = doc.mac,
                            serial = doc.serial,
                            sharedKey = doc.sharedKey,
                            createdAt = doc.createdAt.toEpochMilli(),
                            importedAt = doc.capturedAt.toEpochMilli(),
                            source = doc.source,
                        ),
                    )
                recorder.fact(
                    "KeyOnlyInstalled",
                    JSONObject()
                        .put("pump_hash", hash(doc.mac.toByteArray()))
                        .put("key_id", PumpSession.fingerprint(doc.sharedKey))
                        .put("installation", installation.name)
                        .put("bootstrap", PumpSession.WriteBootstrapState.UNKNOWN_MID_EPOCH.name),
                )
                report("INSTALLED:key-only;bootstrap=UNKNOWN_MID_EPOCH; observe current epoch, reboot, then bootstrap")
                return
            }
            val baseline = JSONObject(baselineFile.readText())
            require(baseline.keys().asSequence().toSet() == setOf("pump", "key_id", "reboot", "read", "write", "source"))
            require(baseline.getString("pump").equals(doc.mac, true)) { "Baseline pump mismatch" }
            require(baseline.getString("key_id") == PumpSession.fingerprint(doc.sharedKey)) { "Baseline key mismatch" }
            val reboot = baseline.getInt("reboot")
            doc.rebootCounter?.let { hint ->
                val alreadyAdopted =
                    hint < Int.MAX_VALUE && hint + 1 == reboot && session.activeRecord()?.reboot == reboot
                require(hint == reboot || alreadyAdopted) { "Document reboot hint conflicts with measured baseline" }
            }
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
            val retiredLegacy = record.retiredLegacyBenchAlarmCursorRecovery
            val historyCounts = record.benchHistoryCounts.joinToString(",") { "${it.family.name}:${it.count}@${it.reboot}" }.ifEmpty { "none" }
            val selectorStates = record.benchHistorySelectorStates.joinToString(",") { "${it.family.name}:${it.index}@${it.reboot}" }.ifEmpty { "none" }
            report(
                "SESSION:generation=${opened.generation},reboot=${record.reboot},read=${record.read},write=${record.write}," +
                    "bootstrap=${record.writeBootstrapState},bootstrap_attempted=${record.benchNewEpochBootstrapAttempted}," +
                    "duplicate_attempted=${record.benchDuplicateCounterAttempted}," +
                    "convergence_attempted=${record.benchAmbiguityConvergenceAttempted}," +
                    "convergence_ready=${session.benchAmbiguityConvergenceReady()}," +
                    "history_counts=$historyCounts,selector_states=$selectorStates," +
                    "pending=${reservation?.operationId ?: "none"},phase=${reservation?.phase ?: "none"}," +
                    "retired_legacy=${retiredLegacy?.operationId ?: "none"}",
            )
        } finally {
            doc.sharedKey.fill(0)
            session.quiesce()
        }
    }

    private fun exportOwnershipHandoff() {
        val expectedEvidence =
            intent.getStringExtra("reviewed_evidence_sha256")
                ?.lowercase()
                ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                ?: error("reviewed_evidence_sha256 must be the independently reviewed SHA-256")
        val doc = loadDocument()
        try {
            session.open(doc.mac, doc.sharedKey)
            val record = checkNotNull(session.snapshot())
            require(record.reservation?.phase == PumpSession.Phase.VERIFIED) { "Current write ownership is not verified" }
            require(record.pump == doc.mac) { "Session document belongs to another pump" }
            val portableRecord = record.copy(serial = doc.serial)
            val journal = File(noBackupFilesDir, "ypso-session.json")
            val evidence = File(filesDir, "write-evidence.jsonl")
            val history = File(filesDir, "history-captures.jsonl")
            val profile = File(filesDir, "profile-captures.jsonl")
            listOf(journal, evidence, history, profile).forEach { require(it.isFile) { "Missing protected artifact ${it.name}" } }
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            val apk = File(applicationInfo.sourceDir)
            val signer =
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
                    .signingInfo
                    ?.apkContentsSigners
                    ?.singleOrNull()
                    ?: error("Bench signer identity is unavailable or ambiguous")
            val bytes =
                YpsoOwnershipHandoff.encode(
                    record = portableRecord,
                    sharedKey = doc.sharedKey,
                    createdAt = System.currentTimeMillis(),
                    reviewedEvidenceSha256 = expectedEvidence,
                    source =
                        YpsoOwnershipHandoff.SourceArtifacts(
                            packageName = packageName,
                            apkSha256 = hash(apk),
                            signerSha256 = hash(signer.toByteArray()),
                            journalSha256 = hash(journal),
                            evidenceSha256 = hash(evidence),
                            historySha256 = hash(history),
                            profileSha256 = hash(profile),
                        ),
                )
            val reviewed = YpsoOwnershipHandoff.parse(bytes, doc.sharedKey)
            require(reviewed.record == portableRecord.copy(keyHex = null)) { "Ownership handoff self-check changed the accounting record" }
            require(reviewed.reviewedEvidenceSha256 == expectedEvidence) { "Ownership handoff self-check lost the review binding" }
            val output = File(filesDir, "ownership-handoff.json")
            FileOutputStream(output).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            bytes.fill(0)
            report("OWNERSHIP_HANDOFF:sha256=${hash(output)};write=${record.write};read=${record.read};reboot=${record.reboot}")
        } finally {
            doc.sharedKey.fill(0)
            session.quiesce()
        }
    }

    @SuppressLint("MissingPermission")
    private fun runConnection(kind: RunKind) {
        check(runLease.compareAndSet(false, true)) { "Another selector run already owns this process" }
        runLeaseHeld = true
        runKind = kind
        require(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            "Grant BLUETOOTH_CONNECT before running the bench"
        }
        val doc = loadDocument().also { document = it }
        token = session.open(doc.mac, doc.sharedKey)
        if (kind !in setOf(
                RunKind.OBSERVE_SELECTOR,
                RunKind.OBSERVE_REBOOT,
                RunKind.RECORD_BOOTSTRAP_REFERENCE,
                RunKind.READ_HISTORY_COUNTS,
                // Read-only; the unresolved reservation stays visible in the capture's session snapshot.
                RunKind.CAPTURE_CURRENT_HISTORY,
                RunKind.PROFILE_ACQUISITION,
                RunKind.AMBIGUITY_CONVERGENCE,
                RunKind.SETTINGS_COUNTER_RECOVERY,
                RunKind.SETTINGS_COUNTER_JUMP,
            )
        ) {
            require(session.snapshot()?.reservation == null || session.snapshot()?.reservation?.phase == PumpSession.Phase.VERIFIED) {
                "An earlier write requires reconciliation"
            }
        }
        selector =
            if (kind in setOf(RunKind.OBSERVE_REBOOT, RunKind.READ_HISTORY_COUNTS, RunKind.CAPTURE_CURRENT_HISTORY, RunKind.PROFILE_ACQUISITION)) {
                null
            } else {
                Selector.parse(
                    intent.getStringExtra("selector_type") ?: error("selector_type required"),
                    intent.getIntExtra("selector", Int.MIN_VALUE),
                )
            }
        writeId = intent.getStringExtra("write_id")?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        omittedReadinessStage = intent.getStringExtra("omit_stage")?.lowercase()
        if (kind == RunKind.READINESS_PROBE) {
            require(omittedReadinessStage in setOf("auth", "cccd", "read")) { "omit_stage must be auth, cccd or read" }
        } else {
            require(omittedReadinessStage == null) { "omit_stage is valid only for readiness-probe" }
        }
        forwardGap = intent.getIntExtra("forward_gap", 0)
        require(forwardGap in 0..1) { "forward_gap must be exactly 0 or 1" }
        require(kind == RunKind.SELECTOR || forwardGap == 0) { "forward_gap is valid only for run-selector" }
        injectedDisconnectAfterFrame = intent.intExtraOrNull("disconnect_after_frame")
        injectedIgnoredCallbackFrame = intent.intExtraOrNull("ignore_callback_frame")
        injectedDuplicateCallbackAfterFrame = intent.intExtraOrNull("duplicate_callback_after_frame")
        if (intent.getBooleanExtra("duplicate_final_callback", false)) {
            require(injectedDuplicateCallbackAfterFrame == null) { "choose one duplicate callback injection" }
            injectedDuplicateCallbackAfterFrame = Int.MAX_VALUE
        }
        listOfNotNull(injectedDisconnectAfterFrame, injectedIgnoredCallbackFrame, injectedDuplicateCallbackAfterFrame)
            .filter { it != Int.MAX_VALUE }
            .forEach { require(it <= EXPECTED_SELECTOR_FRAME_COUNT) { "injected frame must be within 1..$EXPECTED_SELECTOR_FRAME_COUNT" } }
        require(kind == RunKind.SELECTOR ||
            injectedDisconnectAfterFrame == null && injectedIgnoredCallbackFrame == null && injectedDuplicateCallbackAfterFrame == null) {
            "transport fault injection is valid only for ordinary selector writes"
        }
        recorder.fact(
            "RunRequested",
            JSONObject()
                .put("write_id", writeId)
                .put("run_kind", kind.name)
                .put("selector_type", selector?.name ?: JSONObject.NULL)
                .put("selector", selector?.value ?: JSONObject.NULL)
                .put("omit_stage", omittedReadinessStage ?: JSONObject.NULL)
                .put("forward_gap", forwardGap)
                .put("gatt_callback_dispatch", "handler-post-after-callback")
                .put("disconnect_after_frame", injectedDisconnectAfterFrame ?: JSONObject.NULL)
                .put("ignore_callback_frame", injectedIgnoredCallbackFrame ?: JSONObject.NULL)
                .put("duplicate_callback_after_frame", injectedDuplicateCallbackAfterFrame ?: JSONObject.NULL),
        )
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        check(manager.adapter?.isEnabled == true) { "Bluetooth unavailable" }
        val device = manager.adapter.getRemoteDevice(doc.mac)
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "Existing OS bond required" }
        connectionId = UUID.randomUUID().toString()
        handshakePhase = HandshakePhase.CONNECTING
        // Keep callbacks and queued follow-up operations on one serialized queue. This makes
        // after-callback timing explicit; it is an experimental variant, not a Nordic requirement.
        gatt =
            device.connectGatt(
                this,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                handler,
            )
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
            val pendingBefore = session.snapshot()?.reservation
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
                    .put("outcome", outcome.javaClass.simpleName)
                    .put("resolved_prior_write", pendingBefore?.priorWrite ?: JSONObject.NULL)
                    .put("resolved_candidate", pendingBefore?.candidate?.name ?: JSONObject.NULL)
                    .putSessionSnapshot(),
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
                startAuthentication(owner)
            }

            override fun onCharacteristicWrite(
                owner: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                callbackSequencer.afterPlatformCallback {
                    handleCharacteristicWrite(owner, characteristic, status)
                }
            }

            private fun handleCharacteristicWrite(
                owner: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (owner !== gatt) return
                if (characteristic === expectedAuthCharacteristic) {
                    if (handshakePhase != HandshakePhase.AUTHENTICATING) return
                    expectedAuthCharacteristic = null
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail(
                            "AUTH status=$status",
                            YpsoWriteFailure.Layer.GATT_CALLBACK,
                            YpsoWritePolicy.AUTH_UUID,
                            status,
                            stage = "AUTH",
                            service = IDENTITY_SERVICE_UUID,
                        )
                        return
                    }
                    readiness.authenticated(readinessOwner(owner))
                    recorder.fact(
                        "AuthenticationVerified",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("service", characteristic.service?.uuid?.toString() ?: JSONObject.NULL)
                            .put("characteristic", characteristic.uuid.toString()),
                    )
                    handshakePhase = HandshakePhase.READING_CAPABILITIES
                    readFirmware(owner)
                    return
                }
                if (characteristic !== expectedSelectorCharacteristic ||
                    !transport.owns(owner, characteristic.uuid, checkNotNull(selector).category)
                ) {
                    return
                }
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
                val delayedDuplicateAfter = injectedDuplicateCallbackAfterFrame
                if (delayedDuplicateAfter != null &&
                    delayedDuplicateAfter != Int.MAX_VALUE &&
                    observedWriteCallbacks == delayedDuplicateAfter + 1
                ) {
                    injectedDuplicateCallbackAfterFrame = null
                    recorder.fact(
                        "InjectedDelayedDuplicateWriteCallback",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("duplicated_callback", delayedDuplicateAfter)
                            .put("delivered_before_callback", observedWriteCallbacks)
                            .put("status", status),
                    )
                    check(
                        transport.markCallbackOwnershipAmbiguous(
                            owner,
                            characteristic.uuid,
                            status,
                            "injected delayed duplicate collided with callback $observedWriteCallbacks",
                        ),
                    ) { "Injected duplicate no longer owns the active write" }
                    return
                }
                transport.onCharacteristicWrite(owner, characteristic.uuid, status)
                val duplicateAfter = injectedDuplicateCallbackAfterFrame
                val shouldInjectDuplicate =
                    duplicateAfter == Int.MAX_VALUE && session.snapshot()?.reservation?.phase == PumpSession.Phase.ACKED
                if (shouldInjectDuplicate) {
                    injectedDuplicateCallbackAfterFrame = null
                    recorder.fact(
                        "InjectedDuplicateWriteCallback",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("after_callback", observedWriteCallbacks)
                            .put("status", status),
                    )
                    transport.onCharacteristicWrite(owner, characteristic.uuid, status)
                }
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
                        stage = "CONTROL_CCCD",
                        service = CONTROL_SERVICE_UUID,
                        descriptor = YpsoWritePolicy.CCCD_UUID,
                    )
                    return
                }
                readiness.requiredSetupVerified(readinessOwner(owner))
                recorder.fact(
                    "RequiredCccdVerified",
                    JSONObject()
                        .put("write_id", writeId)
                        .put("service", descriptor.characteristic?.service?.uuid?.toString() ?: JSONObject.NULL)
                        .put("characteristic", descriptor.characteristic?.uuid?.toString() ?: JSONObject.NULL)
                        .put("descriptor", descriptor.uuid.toString()),
                )
                if (runKind == RunKind.READINESS_PROBE && omittedReadinessStage == "read") {
                    handshakePhase = HandshakePhase.READY
                    startSelector(owner)
                    return
                }
                handshakePhase = HandshakePhase.PRIMING_READ
                primeRead(owner)
            }

            override fun onCharacteristicRead(
                owner: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (consumeCapabilityRead(owner, characteristic, value.copyOf(), status)) return
                consumeRead(owner, characteristic, value.copyOf(), status)
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(
                owner: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (consumeCapabilityRead(owner, characteristic, characteristic.value?.copyOf(), status)) return
                consumeRead(owner, characteristic, characteristic.value?.copyOf(), status)
            }

            override fun onCharacteristicChanged(
                owner: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                recordControlNotification(owner, characteristic, value.copyOf())
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(
                owner: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                recordControlNotification(owner, characteristic, characteristic.value?.copyOf() ?: byteArrayOf())
            }
        }

    private fun recordControlNotification(
        owner: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (owner !== gatt || characteristic !== controlNotificationCharacteristic) return
        recorder.fact(
            "ControlNotificationReceived",
            JSONObject()
                .put("write_id", writeId.ifBlank { JSONObject.NULL })
                .put("service", characteristic.service?.uuid?.toString() ?: JSONObject.NULL)
                .put("characteristic", characteristic.uuid.toString())
                .put("firmware", firmware ?: JSONObject.NULL)
                .put("length", value.size)
                .put("sha256", hash(value))
                .put("unsigned_bytes", org.json.JSONArray(value.map { it.toInt() and 0xff })),
        )
    }

    @SuppressLint("MissingPermission")
    private fun startAuthentication(owner: BluetoothGatt) {
        if (runKind == RunKind.READINESS_PROBE && omittedReadinessStage == "auth") {
            readiness.requiredSetupVerified(readinessOwner(owner))
            readiness.readVerified(readinessOwner(owner))
            recorder.fact(
                "InjectedReadinessFacts",
                JSONObject()
                    .put("write_id", writeId)
                    .put("omitted", "auth")
                    .put("injected", org.json.JSONArray(listOf("cccd", "read"))),
            )
            handshakePhase = HandshakePhase.READY
            startSelector(owner)
            return
        }
        val auth =
            find(owner, IDENTITY_SERVICE_UUID, YpsoWritePolicy.AUTH_UUID) ?: run {
                fail(
                    "AUTH characteristic missing",
                    YpsoWriteFailure.Layer.READINESS,
                    YpsoWritePolicy.AUTH_UUID,
                    stage = "AUTH",
                    service = IDENTITY_SERVICE_UUID,
                )
                return
            }
        val password = YpsoAuthentication.password(checkNotNull(document).mac)
        check(
            YpsoWritePolicy.allowsCharacteristic(
                YpsoRemoteWrite.AUTHENTICATION,
                auth.uuid,
                password,
                password,
                true,
            ),
        )
        handshakePhase = HandshakePhase.AUTHENTICATING
        expectedAuthCharacteristic = auth
        if (!writeCharacteristic(owner, auth, password)) {
            expectedAuthCharacteristic = null
            fail(
                "AUTH dispatch refused",
                YpsoWriteFailure.Layer.DISPATCH,
                YpsoWritePolicy.AUTH_UUID,
                stage = "AUTH",
                service = IDENTITY_SERVICE_UUID,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun readFirmware(owner: BluetoothGatt) {
        pendingCapabilityIndex = 0
        readNextCapability(owner)
    }

    @SuppressLint("MissingPermission")
    private fun readNextCapability(owner: BluetoothGatt) {
        val capability = CAPABILITY_READS[pendingCapabilityIndex]
        val characteristic =
            find(owner, capability.service, capability.uuid) ?: run {
                failCapability("${capability.label} characteristic missing", capability, YpsoWriteFailure.Layer.CAPABILITY)
                return
            }
        pendingCapabilityRead = CapabilityRead(owner, capability, characteristic)
        if (!owner.readCharacteristic(characteristic)) {
            pendingCapabilityRead = null
            failCapability("${capability.label} read dispatch refused", capability, YpsoWriteFailure.Layer.DISPATCH)
        }
    }

    private fun consumeCapabilityRead(
        owner: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
        status: Int,
    ): Boolean {
        val pending = pendingCapabilityRead ?: return false
        if (pending.owner !== owner || pending.characteristic !== characteristic) return false
        val capability = pending.capability
        check(pendingCapabilityIndex in CAPABILITY_READS.indices && CAPABILITY_READS[pendingCapabilityIndex] == capability) {
            "Capability read state is invalid"
        }
        check(characteristic.service?.uuid == capability.service && characteristic.uuid == capability.uuid) {
            "Capability callback identity changed"
        }
        pendingCapabilityRead = null
        if (status != BluetoothGatt.GATT_SUCCESS || value == null) {
            failCapability(
                "${capability.label} read status=$status",
                capability,
                YpsoWriteFailure.Layer.GATT_CALLBACK,
                status,
                value,
            )
            return true
        }
        when (capability) {
            Capability.MASTER_FIRMWARE, Capability.SUPERVISOR_FIRMWARE -> {
                val parsedFirmware = YpsoFirmwareVersion.fromWire(value) ?: run {
                    failCapability(
                        "${capability.label} value malformed",
                        capability,
                        YpsoWriteFailure.Layer.CAPABILITY,
                        value = value,
                    )
                    return true
                }
                if (!parsedFirmware.meetsMinimum) {
                    failCapability(
                        "${capability.label} $parsedFirmware is below minimum ${YpsoFirmwareVersion.MINIMUM}",
                        capability,
                        YpsoWriteFailure.Layer.CAPABILITY,
                        value = value,
                    )
                    return true
                }
                if (capability == Capability.MASTER_FIRMWARE) {
                    firmware = parsedFirmware.toString()
                } else {
                    supervisorFirmware = parsedFirmware.toString()
                }
            }
            Capability.CONTROL_PROTOCOL -> {
                if (!value.contentEquals(CONTROL_PROTOCOL_VERSION_WIRE)) {
                    failCapability(
                        "control protocol value unsupported",
                        capability,
                        YpsoWriteFailure.Layer.CAPABILITY,
                        value = value,
                    )
                    return true
                }
                controlVersion = "1.3"
            }
        }
        pendingCapabilityIndex++
        if (pendingCapabilityIndex < CAPABILITY_READS.size) {
            readNextCapability(owner)
            return true
        }
        recorder.fact(
            "CapabilityIdentityRead",
            JSONObject()
                .put("write_id", writeId)
                .put("master_firmware", firmware)
                .put("supervisor_firmware", supervisorFirmware)
                .put("control_protocol", controlVersion)
                .put("minimum_firmware", YpsoFirmwareVersion.MINIMUM.toString()),
        )
        if (runKind == RunKind.READINESS_PROBE && omittedReadinessStage == "cccd") {
            handshakePhase = HandshakePhase.PRIMING_READ
            primeRead(owner)
        } else {
            handshakePhase = HandshakePhase.ENABLING_SETUP
            enableRequiredSetup(owner)
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun enableRequiredSetup(owner: BluetoothGatt) {
        val characteristic =
            find(owner, CONTROL_SERVICE_UUID, YpsoWritePolicy.CONTROL_NOTIFY_UUID) ?: run {
                fail(
                    "control notification characteristic missing",
                    YpsoWriteFailure.Layer.READINESS,
                    YpsoWritePolicy.CONTROL_NOTIFY_UUID,
                    stage = "CONTROL_CCCD",
                    service = CONTROL_SERVICE_UUID,
                )
                return
            }
        val descriptor =
            characteristic.getDescriptor(YpsoWritePolicy.CCCD_UUID) ?: run {
                fail(
                    "control notification CCCD missing",
                    YpsoWriteFailure.Layer.READINESS,
                    YpsoWritePolicy.CONTROL_NOTIFY_UUID,
                    stage = "CONTROL_CCCD",
                    service = CONTROL_SERVICE_UUID,
                    descriptor = YpsoWritePolicy.CCCD_UUID,
                )
                return
            }
        controlNotificationCharacteristic = characteristic
        val enableNotification = byteArrayOf(1, 0)
        check(
            YpsoWritePolicy.allowsDescriptor(
                YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR,
                characteristic.uuid,
                descriptor.uuid,
                enableNotification,
            ),
        )
        expectedDescriptor = descriptor
        if (!owner.setCharacteristicNotification(characteristic, true)) {
            expectedDescriptor = null
            fail(
                "local control notification enable refused",
                YpsoWriteFailure.Layer.DISPATCH,
                YpsoWritePolicy.CONTROL_NOTIFY_UUID,
                stage = "CONTROL_CCCD",
                service = CONTROL_SERVICE_UUID,
                descriptor = YpsoWritePolicy.CCCD_UUID,
            )
            return
        }
        if (!writeDescriptor(owner, descriptor, enableNotification)) {
            expectedDescriptor = null
            fail(
                "required CCCD write dispatch refused",
                YpsoWriteFailure.Layer.DISPATCH,
                YpsoWritePolicy.CONTROL_NOTIFY_UUID,
                stage = "CONTROL_CCCD",
                service = CONTROL_SERVICE_UUID,
                descriptor = YpsoWritePolicy.CCCD_UUID,
            )
        }
    }

    private fun primeRead(owner: BluetoothGatt) {
        primeEventCount = null
        primeEventCountBody = null
        primePumpReboot = null
        val eventCount = findUnique(owner, EVENT_COUNT_UUID)
        if (eventCount == null) {
            fail(
                "event count characteristic missing or ambiguous",
                YpsoWriteFailure.Layer.READINESS,
                EVENT_COUNT_UUID,
                stage = "PRIME_READ",
            )
            return
        }
        readEncrypted(owner, eventCount, allowObservedReboot = runKind == RunKind.OBSERVE_REBOOT) { result ->
            result.fold(
                onSuccess = { body ->
                    val count = BenchHistoryCount.decode(body)
                    recorder.fact(
                        if (count != null) "PrimeReadVerified" else "PrimeReadRejected",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("body_size", body.size)
                            .put("body_sha256", hash(body))
                            .put("glb", count ?: JSONObject.NULL)
                            .put("integrity", "EXACT_GLB"),
                    )
                    if (count == null) {
                        fail("prime read exact GLB invalid")
                        return@fold
                    }
                    primeEventCount = count
                    primeEventCountBody = body.copyOf()
                    primePumpReboot = session.snapshot()?.reboot
                    handshakePhase = HandshakePhase.READY
                    readiness.readVerified(readinessOwner(owner))
                    when (runKind) {
                        RunKind.SELECTOR,
                        RunKind.BOOTSTRAP_NEW_EPOCH,
                        RunKind.AMBIGUITY_CONVERGENCE,
                        RunKind.DUPLICATE_COUNTER_PROBE,
                        RunKind.READINESS_PROBE ->
                            startSelector(owner)
                        RunKind.OBSERVE_SELECTOR -> observeSelector(owner)
                        RunKind.SETTINGS_COUNTER_RECOVERY, RunKind.SETTINGS_COUNTER_JUMP -> prepareSettingsCounterRecovery(owner)
                        RunKind.READ_SELECTOR_STATE -> readSelectorState(owner)
                        RunKind.RECORD_BOOTSTRAP_REFERENCE -> recordBootstrapReference(owner)
                        RunKind.READ_HISTORY_COUNTS -> readHistoryCounts(owner)
                        RunKind.CAPTURE_CURRENT_HISTORY -> captureCurrentHistory(owner)
                        RunKind.PROFILE_ACQUISITION -> startProfileAcquisition(owner)
                        RunKind.OBSERVE_REBOOT -> {
                            close(owner)
                            report("REBOOT:not-observed; authenticated read remained in current epoch")
                        }
                    }
                },
                onFailure = {
                    if (runKind == RunKind.OBSERVE_REBOOT && it is PumpSession.RebootAdoptedException) {
                        val adopted = session.activeRecord()
                        recorder.fact(
                            "RebootAdopted",
                            JSONObject()
                                .put("write_id", writeId)
                                .put("reboot", adopted?.reboot ?: JSONObject.NULL)
                                .put("read", adopted?.read ?: JSONObject.NULL)
                                .put("write", adopted?.write ?: JSONObject.NULL),
                        )
                        close(owner)
                        report(
                            "REBOOT:adopted;reboot=${adopted?.reboot};read=${adopted?.read};" +
                                "write=${adopted?.write ?: "uncertain"};bootstrap=${adopted?.writeBootstrapState};" +
                                " reconnect before the one-time new-epoch bootstrap",
                        )
                    } else {
                        fail("prime read failed: ${it.message}")
                    }
                },
            )
        }
    }

    private fun recordBootstrapReference(owner: BluetoothGatt) {
        val selected = checkNotNull(selector)
        require(selected.name == "event") { "Bootstrap reference must use the event selector family" }
        val binding = resolveSelector(owner, selected) ?: return
        readEncrypted(owner, binding.value) { result ->
            result.fold(
                onSuccess = { body ->
                    val embedded = historyIndex(body)
                    if (!YpsoCrc.isValid(body) || embedded == null) {
                        fail("bootstrap reference event value is not CRC-valid history data")
                        return@fold
                    }
                    val payloadHash = hash(YpsoGlb.encode(embedded))
                    session.recordBenchNewEpochBootstrapReference(
                        checkNotNull(token),
                        selected.indexUuid.toString(),
                        payloadHash,
                    )
                    recorder.fact(
                        "BootstrapReferenceRecorded",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("selector_type", selected.name)
                            .put("embedded_history_index", embedded)
                            .put("selector_payload_sha256", payloadHash)
                            .put("body_size", body.size)
                            .put("body_sha256", hash(body))
                            .put("crc_valid", true)
                            .putSessionSnapshot(),
                    )
                    close(owner)
                    report("BOOTSTRAP_REFERENCE:event=$embedded")
                },
                onFailure = { fail("bootstrap reference read failed: ${it.message}") },
            )
        }
    }

    /**
     * Read two consecutive event rows around pump date/time and count reads, without writes.
     * Target observation shows each value read advances a persistent selector. The rows are
     * therefore protected observations only and never establish a stable logical-head cursor.
     */
    private fun captureCurrentHistory(owner: BluetoothGatt) {
        val eventCount = findUnique(owner, EVENT_COUNT_UUID)
        val eventValue = findUnique(owner, EVENT_VALUE_UUID)
        val systemDate = findUnique(owner, SYSTEM_DATE_UUID)
        val systemTime = findUnique(owner, SYSTEM_TIME_UUID)
        if (eventCount == null || eventValue == null || systemDate == null || systemTime == null) {
            fail(
                "history/time characteristic missing or ambiguous",
                YpsoWriteFailure.Layer.READINESS,
                eventCount?.uuid ?: eventValue?.uuid ?: systemDate?.uuid ?: systemTime?.uuid,
                stage = "CAPTURE_CURRENT_HISTORY",
            )
            return
        }
        readEncrypted(owner, eventValue) { headResult ->
            headResult.fold(
                onSuccess = { headBefore -> capturePumpDate(owner, eventCount, eventValue, systemDate, systemTime, headBefore) },
                onFailure = { fail("event head before capture failed: ${it.message}") },
            )
        }
    }

    private fun capturePumpDate(
        owner: BluetoothGatt,
        eventCount: BluetoothGattCharacteristic,
        eventValue: BluetoothGattCharacteristic,
        systemDate: BluetoothGattCharacteristic,
        systemTime: BluetoothGattCharacteristic,
        headBefore: ByteArray,
    ) {
        readEncrypted(owner, systemDate) { dateResult ->
            dateResult.fold(
                onSuccess = { dateBody ->
                    readEncrypted(owner, systemTime) { timeResult ->
                        timeResult.fold(
                            onSuccess = { timeBody ->
                                captureCountAfter(owner, eventCount, eventValue, headBefore, dateBody, timeBody)
                            },
                            onFailure = { fail("system time capture failed: ${it.message}") },
                        )
                    }
                },
                onFailure = { fail("system date capture failed: ${it.message}") },
            )
        }
    }

    private fun captureCountAfter(
        owner: BluetoothGatt,
        eventCount: BluetoothGattCharacteristic,
        eventValue: BluetoothGattCharacteristic,
        headBefore: ByteArray,
        dateBody: ByteArray,
        timeBody: ByteArray,
    ) {
        readEncrypted(owner, eventCount) { countResult ->
            countResult.fold(
                onSuccess = { countBody ->
                    val countAfter = BenchHistoryCount.decode(countBody)
                    if (countAfter == null) {
                        fail("event count after capture is not an exact non-negative GLB")
                        return@fold
                    }
                    readEncrypted(owner, eventValue) { headResult ->
                        headResult.fold(
                            onSuccess = { headAfter ->
                                finishCurrentHistoryCapture(owner, headBefore, headAfter, countBody, dateBody, timeBody, countAfter)
                            },
                            onFailure = { fail("event head after capture failed: ${it.message}") },
                        )
                    }
                },
                onFailure = { fail("event count after capture failed: ${it.message}") },
            )
        }
    }

    private fun finishCurrentHistoryCapture(
        owner: BluetoothGatt,
        headBeforeBody: ByteArray,
        headAfterBody: ByteArray,
        countAfterBody: ByteArray,
        dateBody: ByteArray,
        timeBody: ByteArray,
        countAfter: Int,
    ) {
        val rebootAfter = session.snapshot()?.reboot
        val countBeforeBody = primeEventCountBody
        val observation = BenchCurrentHistoryObservationDecoder.assess(
            headBeforeBody,
            headAfterBody,
            primeEventCount,
            countAfter,
            primePumpReboot,
            rebootAfter,
        )
        val headBefore = observation.first
        appendProtectedCapture(
            eventCaptureJson(headBeforeBody, headBefore)
                .put("pump_reboot_before", primePumpReboot ?: JSONObject.NULL)
                .put("pump_reboot_after", rebootAfter ?: JSONObject.NULL)
                .put("event_count_before_wire_hex", countBeforeBody?.toHex() ?: JSONObject.NULL)
                .put("event_count_before_wire_sha256", countBeforeBody?.let(::hash) ?: JSONObject.NULL)
                .put("event_count_after", countAfter)
                .put("event_count_after_wire_hex", countAfterBody.toHex())
                .put("event_count_after_wire_sha256", hash(countAfterBody))
                .put("head_after_wire_hex", headAfterBody.toHex())
                .put("head_after_wire_sha256", hash(headAfterBody))
                .put("head_after_crc_valid", observation.second != null)
                .put("head_after_embedded_history_index", observation.second?.index ?: JSONObject.NULL)
                .put("system_date_hex", dateBody.toHex())
                .put("system_time_hex", timeBody.toHex())
                .put("count_stable", observation.countStable)
                .put("reboot_stable", observation.rebootStable)
                .put("started_at_logical_head", observation.startedAtLogicalHead)
                .put("stable_head_cursor", observation.stableHeadCursor)
                .put("disposition", observation.disposition)
                .putSessionSnapshot(),
        )
        recorder.fact(
            "CurrentHistoryRowsObserved",
            eventCaptureJson(headBeforeBody, headBefore)
                .put("pump_reboot_before", primePumpReboot ?: JSONObject.NULL)
                .put("pump_reboot_after", rebootAfter ?: JSONObject.NULL)
                .put("event_count_before_body_size", countBeforeBody?.size ?: JSONObject.NULL)
                .put("event_count_before_body_sha256", countBeforeBody?.let(::hash) ?: JSONObject.NULL)
                .put("event_count_after", countAfter)
                .put("event_count_after_body_size", countAfterBody.size)
                .put("event_count_after_body_sha256", hash(countAfterBody))
                .put("head_after_body_size", headAfterBody.size)
                .put("head_after_body_sha256", hash(headAfterBody))
                .put("system_date_size", dateBody.size)
                .put("system_date_sha256", hash(dateBody))
                .put("system_time_size", timeBody.size)
                .put("system_time_sha256", hash(timeBody))
                .put("count_stable", observation.countStable)
                .put("reboot_stable", observation.rebootStable)
                .put("started_at_logical_head", observation.startedAtLogicalHead)
                .put("stable_head_cursor", observation.stableHeadCursor)
                .put("disposition", observation.disposition)
                .putSessionSnapshot(),
        )
        close(owner)
        report("CAPTURE:rows-observed; advancing selector cannot establish a stable logical-head cursor")
    }

    private fun eventCaptureJson(body: ByteArray, entry: YpsoHistoryEntry?): JSONObject =
        JSONObject()
            .put("write_id", writeId)
            .put("event_count_before", primeEventCount ?: JSONObject.NULL)
            .put("event_body_size", body.size)
            .put("event_body_sha256", hash(body))
            .put("event_crc_valid", entry != null)
            .put("event_strict_layout", entry != null)
            .put("factory_seconds", entry?.factorySeconds ?: JSONObject.NULL)
            .put("event_type", entry?.eventType ?: JSONObject.NULL)
            .put("value1", entry?.value1 ?: JSONObject.NULL)
            .put("value2", entry?.value2 ?: JSONObject.NULL)
            .put("value3", entry?.value3 ?: JSONObject.NULL)
            .put("sequence", entry?.sequence ?: JSONObject.NULL)
            .put("embedded_history_index", entry?.index ?: JSONObject.NULL)

    private fun appendProtectedCapture(capture: JSONObject) {
        FileOutputStream(File(filesDir, "history-captures.jsonl"), true).use { out ->
            out.write((capture.toString() + "\n").toByteArray())
            out.fd.sync()
        }
    }

    private fun readHistoryCounts(owner: BluetoothGatt) {
        val alarmCount = findUnique(owner, YpsoWritePolicy.ALARM_COUNT_UUID)
        val systemCount = findUnique(owner, YpsoWritePolicy.SYSTEM_COUNT_UUID)
        if (alarmCount == null || systemCount == null) {
            fail(
                "history count characteristic missing or ambiguous",
                YpsoWriteFailure.Layer.READINESS,
                alarmCount?.uuid ?: systemCount?.uuid,
                stage = "HISTORY_COUNTS",
            )
            return
        }
        readEncrypted(owner, alarmCount) { alarmResult ->
            alarmResult.fold(
                onSuccess = { alarmBody ->
                    val alarms = BenchHistoryCount.decode(alarmBody)
                    recorder.fact(
                        if (alarms != null) "AlarmCountVerified" else "AlarmCountRejected",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("body_size", alarmBody.size)
                            .put("body_sha256", hash(alarmBody))
                            .put("glb", alarms ?: JSONObject.NULL)
                            .put("integrity", "EXACT_GLB"),
                    )
                    if (alarms == null) {
                        fail("alarm count exact GLB invalid")
                        return@fold
                    }
                    recordHistoryCount(PumpSession.HistoryFamily.ALARM, alarmCount, alarms, alarmBody)
                    readEncrypted(owner, systemCount) { systemResult ->
                        systemResult.fold(
                            onSuccess = { systemBody ->
                                val systems = BenchHistoryCount.decode(systemBody)
                                recorder.fact(
                                    if (systems != null) "SystemCountVerified" else "SystemCountRejected",
                                    JSONObject()
                                        .put("write_id", writeId)
                                        .put("body_size", systemBody.size)
                                        .put("body_sha256", hash(systemBody))
                                        .put("glb", systems ?: JSONObject.NULL)
                                        .put("integrity", "EXACT_GLB"),
                                )
                                if (systems == null) {
                                    fail("system count exact GLB invalid")
                                    return@fold
                                }
                                recordHistoryCount(PumpSession.HistoryFamily.SYSTEM, systemCount, systems, systemBody)
                                close(owner)
                                report("COUNTS:alarm=$alarms,system=$systems")
                            },
                            onFailure = { fail("system count read failed: ${it.message}") },
                        )
                    }
                },
                onFailure = { fail("alarm count read failed: ${it.message}") },
            )
        }
    }

    private fun recordHistoryCount(
        family: PumpSession.HistoryFamily,
        characteristic: BluetoothGattCharacteristic,
        count: Int,
        body: ByteArray,
    ) {
        val snapshot = checkNotNull(session.snapshot())
        session.recordBenchHistoryCounts(
            checkNotNull(token),
            listOf(
                PumpSession.HistoryCountEvidence(
                    family = family,
                    reboot = checkNotNull(snapshot.reboot),
                    read = checkNotNull(snapshot.read),
                    count = count,
                    characteristic = characteristic.uuid.toString(),
                    payloadHash = hash(body),
                ),
            ),
        )
        recorder.fact(
            "HistoryCountBound",
            JSONObject()
                .put("write_id", writeId)
                .put("family", family.name)
                .put("reboot", snapshot.reboot)
                .put("read", snapshot.read)
                .put("count", count)
                .put("characteristic", characteristic.uuid.toString())
                .put("body_sha256", hash(body))
                .putSessionSnapshot(),
        )
    }

    private fun readSelectorState(owner: BluetoothGatt) {
        val selected = checkNotNull(selector)
        require(selected.name in setOf("alarm", "system")) {
            "read-selector-state supports alarm and system families only"
        }
        val binding = resolveSelector(owner, selected) ?: return
        readEncrypted(owner, binding.value) { result ->
            result.fold(
                onSuccess = { body ->
                    val evidence = BenchSelectorEvidenceDecoder.history(body)
                    recorder.fact(
                        if (evidence.crcValid && evidence.embeddedHistoryIndex != null) {
                            "CurrentSelectorStateVerified"
                        } else {
                            "CurrentSelectorStateRejected"
                        },
                        JSONObject()
                            .put("write_id", writeId)
                            .put("selector_type", selected.name)
                            .put("body_size", body.size)
                            .put("body_sha256", hash(body))
                            .put("glb", evidence.glb ?: JSONObject.NULL)
                            .put("crc_valid", evidence.crcValid)
                            .put("embedded_history_index", evidence.embeddedHistoryIndex ?: JSONObject.NULL)
                            .putSessionSnapshot(),
                    )
                    if (!evidence.crcValid || evidence.embeddedHistoryIndex == null) {
                        fail("current ${selected.name} selector value is not CRC-valid history data")
                        return@fold
                    }
                    val family = historyFamily(selected.name)
                    if (family == null) {
                        fail("read-selector-state supports alarm and system families only")
                        return@fold
                    }
                    val snapshot = checkNotNull(session.snapshot())
                    session.recordBenchHistorySelectorState(
                        checkNotNull(token),
                        PumpSession.HistorySelectorState(
                            family = family,
                            reboot = checkNotNull(snapshot.reboot),
                            read = checkNotNull(snapshot.read),
                            index = evidence.embeddedHistoryIndex,
                            characteristic = binding.value.uuid.toString(),
                            payloadHash = hash(body),
                        ),
                    )
                    recorder.fact(
                        "SelectorStateBound",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("selector_type", selected.name)
                            .put("index", evidence.embeddedHistoryIndex)
                            .put("characteristic", binding.value.uuid.toString())
                            .put("body_sha256", hash(body))
                            .putSessionSnapshot(),
                    )
                    close(owner)
                    report("SELECTOR_STATE:${selected.name}=${evidence.embeddedHistoryIndex}")
                },
                onFailure = { fail("current ${selected.name} selector read failed: ${it.message}") },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSelector(owner: BluetoothGatt) {
        val selected = checkNotNull(selector)
        val binding = resolveSelector(owner, selected) ?: return
        expectedSelectorCharacteristic = binding.index
        recorder.fact(
            "SelectorServiceBound",
            JSONObject()
                .put("write_id", writeId)
                .put("selector_type", selected.name)
                .put("service", binding.service.toString())
                .put("index_characteristic", binding.index.uuid.toString())
                .put("value_characteristic", binding.value.uuid.toString()),
        )
        val operationOwner = YpsoBenchWriteCoordinator.Owner(owner, connectionId, checkNotNull(token))
        val started =
            coordinator.writeSelector(
                writeId,
                operationOwner,
                selected.category,
                selected.indexUuid,
                YpsoGlb.encode(selected.value),
                firmware = firmware,
                deadlineMs = intent.getLongExtra("deadline_ms", 8_000L),
                mode =
                    when (runKind) {
                        RunKind.BOOTSTRAP_NEW_EPOCH -> YpsoBenchWriteCoordinator.BenchWriteMode.NEW_EPOCH_BOOTSTRAP
                        RunKind.AMBIGUITY_CONVERGENCE -> YpsoBenchWriteCoordinator.BenchWriteMode.AMBIGUITY_CONVERGENCE
                        RunKind.SETTINGS_COUNTER_RECOVERY -> YpsoBenchWriteCoordinator.BenchWriteMode.SETTINGS_COUNTER_RECOVERY
                        RunKind.SETTINGS_COUNTER_JUMP -> YpsoBenchWriteCoordinator.BenchWriteMode.SETTINGS_COUNTER_JUMP
                        RunKind.DUPLICATE_COUNTER_PROBE -> YpsoBenchWriteCoordinator.BenchWriteMode.DUPLICATE_COUNTER
                        else ->
                            if (forwardGap == 1) {
                                YpsoBenchWriteCoordinator.BenchWriteMode.FORWARD_GAP
                            } else {
                                YpsoBenchWriteCoordinator.BenchWriteMode.STRICT_NEXT
                            }
                    },
                dispatch = { frame ->
                    dispatchedFrames++
                    val accepted = writeCharacteristic(owner, binding.index, frame)
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
                    recorder.coordinatorOutcome(outcome)
                    when (outcome) {
                        is YpsoWriteOutcome.AcceptedUnverified -> readBack(owner, selected, binding)
                        is YpsoWriteOutcome.NotSent -> {
                            close(owner)
                            report(
                                "OUTCOME:NotSent;layer=${outcome.failure.layer};" +
                                    "detail=${outcome.failure.detail};counter=${outcome.counter ?: "none"}",
                            )
                        }
                        is YpsoWriteOutcome.ProvenRejected -> {
                            close(owner)
                            report("OUTCOME:ProvenRejected; reconciliation required")
                        }
                        is YpsoWriteOutcome.PossiblyApplied -> {
                            if (
                                outcome.failure.layer == YpsoWriteFailure.Layer.GATT_CALLBACK &&
                                outcome.failure.frame == EXPECTED_SELECTOR_FRAME_COUNT &&
                                dispatchedFrames == EXPECTED_SELECTOR_FRAME_COUNT
                            ) {
                                readBackAfterAmbiguousFinalCallback(owner, selected, binding, outcome)
                            } else {
                                close(owner)
                                report("OUTCOME:PossiblyApplied; reconciliation required")
                            }
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

    private fun startProfileAcquisition(owner: BluetoothGatt) {
        // This is a normal read acquisition, not a historical counter experiment. The previous
        // run has closed its transport; retain its counter and outcome, then acquire afresh.
        session.recoverInterruptedWrite(checkNotNull(token))
        val settingId = findUnique(owner, YpsoWritePolicy.SETTING_ID_UUID)
        val settingValue = findUnique(owner, SETTING_VALUE_UUID)
        if (settingId == null || settingValue == null || settingId.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            fail(
                "profile selector identity/value characteristic missing, ambiguous, or unreadable",
                YpsoWriteFailure.Layer.READINESS,
                settingId?.uuid ?: settingValue?.uuid,
                stage = "PROFILE_ACQUISITION",
            )
            return
        }
        profileReadback = null
        profileSelectedSettingId = null
        profileStartedElapsed = android.os.SystemClock.elapsedRealtime()
        profileRows = linkedMapOf()
        profilePaused = false
        readEncrypted(owner, settingId) { result ->
            result.fold(
                onSuccess = { body ->
                    val initial = YpsoGlb.decodeExact(body)
                    if (initial == null || initial < 0) {
                        fail("initial setting selector identity is not exact non-negative GLB")
                        return@fold
                    }
                    profileSelectedSettingId = initial
                    recorder.fact(
                        "ProfileInitialSelectorIdentity",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("setting_id", initial)
                            .put("body_sha256", hash(body))
                            .putSessionSnapshot(),
                    )
                    if (initial == 1) {
                        profileSelect(owner, 14, discardValue = true) { profileReadActiveBefore(owner) }
                    } else {
                        profileReadActiveBefore(owner)
                    }
                },
                onFailure = { fail("initial setting selector identity read failed: ${it.message}") },
            )
        }
    }

    private fun profileReadActiveBefore(owner: BluetoothGatt) {
        profileSelect(owner, 1) { body ->
            val program = YpsoBasalSchedule.Program.decode(body)
            if (program == null) {
                fail("active profile before acquisition is unsupported")
                return@profileSelect
            }
            val snapshot = checkNotNull(session.snapshot())
            profileReadback =
                YpsoProfileReadback(
                    checkNotNull(token).generation,
                    checkNotNull(snapshot.reboot),
                    connectionId,
                    profileStartedElapsed,
                    program,
                )
            recorder.fact(
                "ProfileActiveBefore",
                JSONObject()
                    .put("write_id", writeId)
                    .put("active_program", program.name)
                    .put("body_sha256", hash(body))
                    .putSessionSnapshot(),
            )
            profileReadRow(owner, 14)
        }
    }

    private fun profileReadRow(owner: BluetoothGatt, settingId: Int) {
        if (settingId > 61) {
            profileSelect(owner, 1) { activeAfter -> profileReadClock(owner, activeAfter) }
            return
        }
        profileSelect(owner, settingId) { body ->
            val value = YpsoGlb.decodeExact(body)
            if (value == null || value < 0 || profileReadback?.add(settingId, body) != true) {
                fail("profile setting $settingId is invalid or duplicated")
                return@profileSelect
            }
            profileRows[settingId] = value
            recorder.fact(
                "ProfileSettingRead",
                JSONObject()
                    .put("write_id", writeId)
                    .put("setting_id", settingId)
                    .put("centi_units_per_hour", value)
                    .put("body_sha256", hash(body))
                    .putSessionSnapshot(),
            )
            val pauseAfter = intent.getIntExtra("pause_after_setting", Int.MIN_VALUE)
            val pauseMs = intent.getLongExtra("pause_ms", 0L)
            if (!profilePaused && settingId == pauseAfter) {
                require(pauseMs in 1_000L..120_000L) { "pause_ms must be within 1000..120000" }
                profilePaused = true
                recorder.fact(
                    "ProfileAcquisitionPaused",
                    JSONObject().put("write_id", writeId).put("after_setting", settingId).put("pause_ms", pauseMs),
                )
                progress("PAUSED:profile acquisition after setting $settingId for ${pauseMs}ms")
                handler.postDelayed({ if (gatt === owner) profileReadRow(owner, settingId + 1) }, pauseMs)
            } else {
                handler.post { if (gatt === owner) profileReadRow(owner, settingId + 1) }
            }
        }
    }

    private fun profileReadClock(owner: BluetoothGatt, activeAfter: ByteArray) {
        val systemDate = findUnique(owner, SYSTEM_DATE_UUID)
        val systemTime = findUnique(owner, SYSTEM_TIME_UUID)
        val eventCount = findUnique(owner, EVENT_COUNT_UUID)
        if (systemDate == null || systemTime == null || eventCount == null) {
            fail("profile clock/history bracket characteristic missing or ambiguous")
            return
        }
        readEncrypted(owner, systemDate) { dateResult ->
            dateResult.fold(
                onSuccess = { dateBody ->
                    readEncrypted(owner, systemTime) { timeResult ->
                        timeResult.fold(
                            onSuccess = { timeBody ->
                                readEncrypted(owner, eventCount) { countResult ->
                                    countResult.fold(
                                        onSuccess = { countBody ->
                                            finishProfileAcquisition(owner, activeAfter, dateBody, timeBody, countBody)
                                        },
                                        onFailure = { fail("event count after profile acquisition failed: ${it.message}") },
                                    )
                                }
                            },
                            onFailure = { fail("system time after profile acquisition failed: ${it.message}") },
                        )
                    }
                },
                onFailure = { fail("system date after profile acquisition failed: ${it.message}") },
            )
        }
    }

    private fun finishProfileAcquisition(
        owner: BluetoothGatt,
        activeAfter: ByteArray,
        dateBody: ByteArray,
        timeBody: ByteArray,
        countBody: ByteArray,
    ) {
        val countAfter = BenchHistoryCount.decode(countBody)
        if (countAfter == null || countAfter != primeEventCount) {
            profileReadback?.invalidate()
            fail("event count changed during profile acquisition")
            return
        }
        val local = YpsoProfileReadback.decodeClock(dateBody, timeBody)
        if (local == null) {
            profileReadback?.invalidate()
            fail("pump clock after profile acquisition is malformed")
            return
        }
        val snapshot = checkNotNull(session.snapshot())
        val zone = ZoneId.systemDefault()
        val verified =
            profileReadback?.finish(
                checkNotNull(token).generation,
                checkNotNull(snapshot.reboot),
                connectionId,
                android.os.SystemClock.elapsedRealtime(),
                activeAfter,
                local,
                Instant.now(),
                zone,
                maxAcquisitionMs = 5 * 60 * 1000L,
                maxClockSkew = Duration.ofSeconds(30),
                eventCount = countAfter,
            )
        if (verified == null) {
            fail("atomic profile coherence validation failed")
            return
        }
        val capture =
            JSONObject()
                .put("capture_id", writeId)
                .put("capture_kind", "ATOMIC_PROFILE_ACQUISITION")
                .put("session_generation", verified.generation)
                .put("pump_reboot", verified.reboot)
                .put("connection_id", verified.connectionId)
                .put("active_program", verified.activeProgram.name)
                .put("profile_a_centi_units_per_hour", org.json.JSONArray((14..37).map { profileRows[it] }))
                .put("profile_b_centi_units_per_hour", org.json.JSONArray((38..61).map { profileRows[it] }))
                .put("event_count_before", primeEventCount)
                .put("event_count_after", countAfter)
                .put("system_date_hex", dateBody.toHex())
                .put("system_time_hex", timeBody.toHex())
                .put("pump_local_time", local.toString())
                .put("zone", zone.id)
                .put("acquired_elapsed_ms", verified.acquiredElapsedMs)
                .putSessionSnapshot()
        appendProfileCapture(capture)
        recorder.fact(
            "AtomicProfileAcquisitionVerified",
            JSONObject()
                .put("write_id", writeId)
                .put("capture_sha256", hash(capture.toString().toByteArray()))
                .put("active_program", verified.activeProgram.name)
                .put("row_count", profileRows.size)
                .put("event_count", countAfter)
                .putSessionSnapshot(),
        )
        close(owner)
        report("PROFILE:Verified;active=${verified.activeProgram.name};rows=${profileRows.size};event_count=$countAfter")
    }

    @SuppressLint("MissingPermission")
    private fun profileSelect(
        owner: BluetoothGatt,
        settingId: Int,
        discardValue: Boolean = false,
        done: (ByteArray) -> Unit,
    ) {
        if (profileSelectedSettingId == settingId) {
            fail("setting $settingId lacks changed-selector identity evidence")
            return
        }
        val selected = Selector.parse("setting", settingId)
        selector = selected
        val binding = resolveSelector(owner, selected) ?: return
        expectedSelectorCharacteristic = binding.index
        dispatchedFrames = 0
        observedWriteCallbacks = 0
        val operationId = "$writeId-setting-$settingId-${UUID.randomUUID()}"
        val operationOwner = YpsoBenchWriteCoordinator.Owner(owner, connectionId, checkNotNull(token))
        val started =
            coordinator.writeSelector(
                operationId,
                operationOwner,
                selected.category,
                selected.indexUuid,
                YpsoGlb.encode(settingId),
                firmware,
                intent.getLongExtra("deadline_ms", 8_000L),
                dispatch = { frame ->
                    dispatchedFrames++
                    writeCharacteristic(owner, binding.index, frame)
                },
                onOutcome = { outcome ->
                    recorder.coordinatorOutcome(outcome)
                    when (outcome) {
                        is YpsoWriteOutcome.Verified -> Unit
                        is YpsoWriteOutcome.AcceptedUnverified ->
                            profileReconcileSelector(owner, operationOwner, operationId, settingId, binding, discardValue, done)
                        is YpsoWriteOutcome.PossiblyApplied -> {
                            if (outcome.failure.layer == YpsoWriteFailure.Layer.GATT_CALLBACK &&
                                outcome.failure.frame == EXPECTED_SELECTOR_FRAME_COUNT &&
                                dispatchedFrames == EXPECTED_SELECTOR_FRAME_COUNT
                            ) {
                                profileReconcileSelector(owner, operationOwner, operationId, settingId, binding, discardValue, done)
                            } else {
                                fail("profile selector $settingId became uncertain before semantic read-back")
                            }
                        }
                        else -> fail("profile selector $settingId failed before semantic read-back: $outcome")
                    }
                },
            )
        if (!started && gatt === owner) fail("profile selector $settingId could not start")
    }

    private fun profileReconcileSelector(
        owner: BluetoothGatt,
        operationOwner: YpsoBenchWriteCoordinator.Owner,
        operationId: String,
        settingId: Int,
        binding: SelectorBinding,
        discardValue: Boolean,
        done: (ByteArray) -> Unit,
    ) {
        readEncrypted(owner, binding.index) { identityResult ->
            identityResult.fold(
                onSuccess = { identityBody ->
                    val observed = YpsoGlb.decodeExact(identityBody)
                    if (observed != settingId) {
                        fail("profile selector identity mismatch: requested $settingId, read $observed")
                        return@fold
                    }
                    val snapshot = checkNotNull(session.snapshot())
                    val evidenceHash = hash(
                        "${checkNotNull(token).generation}|${snapshot.reboot}|$connectionId|$settingId|${hash(identityBody)}".toByteArray(),
                    )
                    val detail = "same-link exact-GLB selector identity read-back matched setting $settingId"
                    if (!coordinator.reconcile(
                            operationId,
                            operationOwner,
                            YpsoBenchWriteCoordinator.Reconciliation(
                                YpsoSemanticEvidence.ACCEPTED,
                                PumpSession.WriteResolution.ACCEPTED,
                                evidenceHash,
                                detail,
                            ),
                        )
                    ) {
                        fail("profile selector $settingId reconciliation lost live ownership")
                        return@fold
                    }
                    profileSelectedSettingId = settingId
                    recorder.fact(
                        "ProfileSelectorIdentityVerified",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("operation_id", operationId)
                            .put("setting_id", settingId)
                            .put("body_sha256", hash(identityBody))
                            .put("evidence_sha256", evidenceHash)
                            .putSessionSnapshot(),
                    )
                    readEncrypted(owner, binding.value) { valueResult ->
                        valueResult.fold(
                            onSuccess = { body ->
                                if (YpsoGlb.decodeExact(body) == null) {
                                    fail("profile setting $settingId value is not exact GLB")
                                } else if (discardValue) {
                                    handler.post { if (gatt === owner) done(body) }
                                } else {
                                    done(body)
                                }
                            },
                            onFailure = { fail("profile setting $settingId value read failed: ${it.message}") },
                        )
                    }
                },
                onFailure = { fail("profile selector $settingId identity read failed: ${it.message}") },
            )
        }
    }

    private fun appendProfileCapture(capture: JSONObject) {
        FileOutputStream(File(filesDir, "profile-captures.jsonl"), true).use { out ->
            out.write((capture.toString() + "\n").toByteArray())
            out.fd.sync()
        }
    }

    private fun prepareSettingsCounterRecovery(owner: BluetoothGatt) {
        val selected = checkNotNull(selector)
        require(selected.category == YpsoRemoteWrite.HISTORY_SELECTOR && selected.indexUuid == YpsoWritePolicy.EVENT_INDEX_UUID)
        require(selected.value in 0 until checkNotNull(primeEventCount))
        check(if (runKind == RunKind.SETTINGS_COUNTER_JUMP) session.benchSettingsCounterJumpReady() else session.benchSettingsCounterRecoveryReady())
        val binding = resolveSelector(owner, selected) ?: return
        readEncrypted(owner, binding.value, false) { result ->
            result.fold(
                onSuccess = { body ->
                    val observed = BenchSelectorEvidenceDecoder.history(body, selected.value)
                    val before = observed.embeddedHistoryIndex
                    if (!observed.crcValid || before == null || kotlin.math.abs(before - selected.value) <= 1) {
                        fail("Recovery requires a valid current event index distinct from requested and next iterator row")
                        return@fold
                    }
                    recorder.fact("SettingsCounterRecoveryPreRead", JSONObject()
                        .put("write_id", writeId).put("current_index", before)
                        .put("requested_index", selected.value).put("body_sha256", hash(body)))
                    handler.post { if (owner === gatt) startSelector(owner) }
                },
                onFailure = { fail("Recovery pre-read failed: ${it.message}") },
            )
        }
    }

    private fun observeSelector(owner: BluetoothGatt) {
        val selected = checkNotNull(selector)
        val binding = resolveSelector(owner, selected) ?: return
        val reservation = session.snapshot()?.reservation ?: error("No unresolved write to observe")
        require(reservation.phase in setOf(PumpSession.Phase.POSSIBLY_SENT, PumpSession.Phase.ACKED)) {
            "Write is not awaiting reconciliation"
        }
        require(reservation.operationId == writeId) { "write_id does not match the unresolved write" }
        require(reservation.characteristic == selected.indexUuid.toString()) { "selector characteristic does not match unresolved write" }
        require(reservation.purpose == selected.category.name) { "selector purpose does not match unresolved write" }
        require(reservation.payloadHash == hash(YpsoGlb.encode(selected.value))) { "selector value does not match unresolved write" }
        readEncrypted(owner, binding.value) { result ->
            result.fold(
                onSuccess = { body ->
                    val evidence = selectorEvidence(selected, body)
                    recorder.fact(
                        "UnresolvedSelectorObserved",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("counter", reservation.counter)
                            .put("phase", reservation.phase.name)
                            .put("selector_type", selected.name)
                            .put("selector", selected.value)
                            .put("body_size", body.size)
                            .put("body_sha256", hash(body))
                            .put("glb", evidence.glb ?: JSONObject.NULL)
                            .put("crc_valid", evidence.crcValid)
                            .put("embedded_history_index", evidence.embeddedHistoryIndex ?: JSONObject.NULL)
                            .put("semantic_match", evidence.semanticMatch ?: JSONObject.NULL)
                            .putSessionSnapshot(),
                    )
                    close(owner)
                    report("OBSERVED:unresolved selector read-back captured; reconciliation still required")
                },
                onFailure = {
                    recorder.fact(
                        "UnresolvedSelectorObservationFailed",
                        JSONObject().put("write_id", writeId).put("detail", it.message),
                    )
                    close(owner)
                    report("OBSERVED:read-back failed; reconciliation still required")
                },
            )
        }
    }

    private fun readBack(
        owner: BluetoothGatt,
        selected: Selector,
        binding: SelectorBinding,
    ) = readSelectorIdentity(owner, selected, binding) { identityMatches ->
        if (!identityMatches) {
            close(owner)
            report("OUTCOME:AcceptedUnverified; selector identity mismatch; explicit reconciliation required")
            return@readSelectorIdentity
        }
        readEncrypted(owner, binding.value) { result ->
            result.fold(
                onSuccess = { body ->
                    val evidence = selectorEvidence(selected, body)
                    if (selected.name == "event") appendSelectedEventCapture(selected, body)
                    recorder.fact(
                        "SelectorReadBack",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("selector_type", selected.name)
                            .put("selector", selected.value)
                            .put("body_size", body.size)
                            .put("body_sha256", hash(body))
                            .put("glb", evidence.glb ?: JSONObject.NULL)
                            .put("crc_valid", evidence.crcValid)
                            .put("embedded_history_index", evidence.embeddedHistoryIndex ?: JSONObject.NULL)
                            .put("semantic_match", evidence.semanticMatch ?: JSONObject.NULL)
                            .putSessionSnapshot(),
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
    }

    /**
     * A non-zero final callback does not prove rejection: target selector writes with raw status 139
     * have later been semantically verified. Preserve the uncertain write outcome, but use the still-
     * owned connection for the same selector-value read performed by the reference implementation.
     */
    private fun readBackAfterAmbiguousFinalCallback(
        owner: BluetoothGatt,
        selected: Selector,
        binding: SelectorBinding,
        outcome: YpsoWriteOutcome.PossiblyApplied,
    ) = readSelectorIdentity(owner, selected, binding) { identityMatches ->
        if (!identityMatches) {
            close(owner)
            report("OUTCOME:PossiblyApplied; selector identity mismatch; reconciliation required")
            return@readSelectorIdentity
        }
        readEncrypted(owner, binding.value) { result ->
            result.fold(
                onSuccess = { body ->
                    val evidence = selectorEvidence(selected, body)
                    recorder.fact(
                        "AmbiguousFinalCallbackReadBack",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("selector_type", selected.name)
                            .put("selector", selected.value)
                            .put("callback_status", outcome.failure.code ?: JSONObject.NULL)
                            .put("callback_frame", outcome.failure.frame ?: JSONObject.NULL)
                            .put("body_size", body.size)
                            .put("body_sha256", hash(body))
                            .put("glb", evidence.glb ?: JSONObject.NULL)
                            .put("crc_valid", evidence.crcValid)
                            .put("embedded_history_index", evidence.embeddedHistoryIndex ?: JSONObject.NULL)
                            .put("semantic_match", evidence.semanticMatch ?: JSONObject.NULL)
                            .putSessionSnapshot(),
                    )
                    close(owner)
                    report("OUTCOME:PossiblyApplied; same-connection read-back captured; reconciliation required")
                },
                onFailure = {
                    recorder.fact(
                        "AmbiguousFinalCallbackReadBackFailed",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("callback_status", outcome.failure.code ?: JSONObject.NULL)
                            .put("callback_frame", outcome.failure.frame ?: JSONObject.NULL)
                            .put("detail", it.message),
                    )
                    close(owner)
                    report("OUTCOME:PossiblyApplied; same-connection read-back failed; reconciliation required")
                },
            )
        }
    }

    /** A setting value has no embedded setting ID, so identity must be read separately. */
    private fun readSelectorIdentity(
        owner: BluetoothGatt,
        selected: Selector,
        binding: SelectorBinding,
        done: (Boolean) -> Unit,
    ) {
        if (selected.name != "setting") {
            done(true)
            return
        }
        if (binding.index.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            recorder.fact(
                "SettingSelectorIdentityReadBackUnavailable",
                JSONObject().put("write_id", writeId).put("selector", selected.value).put("properties", binding.index.properties),
            )
            done(false)
            return
        }
        readEncrypted(owner, binding.index) { result ->
            result.fold(
                onSuccess = { body ->
                    val observed = YpsoGlb.decodeExact(body)
                    val matches = observed == selected.value
                    recorder.fact(
                        "SettingSelectorIdentityReadBack",
                        JSONObject()
                            .put("write_id", writeId)
                            .put("selector", selected.value)
                            .put("body_size", body.size)
                            .put("body_sha256", hash(body))
                            .put("glb", observed ?: JSONObject.NULL)
                            .put("semantic_match", matches)
                            .putSessionSnapshot(),
                    )
                    done(matches)
                },
                onFailure = {
                    recorder.fact(
                        "SettingSelectorIdentityReadBackFailed",
                        JSONObject().put("write_id", writeId).put("selector", selected.value).put("detail", it.message),
                    )
                    done(false)
                },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun readEncrypted(
        owner: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        allowObservedReboot: Boolean = false,
        done: (Result<ByteArray>) -> Unit,
    ) {
        check(pendingRead == null) { "Overlapping EXTREAD transaction" }
        pendingRead = ReadTransaction(owner, characteristic, allowObservedReboot, done)
        if (!owner.readCharacteristic(characteristic)) {
            finishRead(Result.failure(IllegalStateException("read dispatch refused for ${characteristic.uuid}")))
        }
    }

    private fun appendSelectedEventCapture(selected: Selector, body: ByteArray) {
        val entry = YpsoHistoryEntry.decodeWire(body)
        appendProtectedCapture(
            JSONObject()
                .put("capture_id", writeId)
                .put("capture_kind", "SELECTOR_READ_BACK")
                .put("wall_time_ms", System.currentTimeMillis())
                .put("elapsed_ms", android.os.SystemClock.elapsedRealtime())
                .put("firmware", firmware ?: JSONObject.NULL)
                .put("supervisor_firmware", supervisorFirmware ?: JSONObject.NULL)
                .put("control_protocol", controlVersion ?: JSONObject.NULL)
                .put("event_count_before", primeEventCount ?: JSONObject.NULL)
                .put("selected_index", selected.value)
                .put("event_wire_hex", body.toHex())
                .put("event_wire_sha256", hash(body))
                .put("event_crc_valid", YpsoCrc.isValid(body))
                .put("event_strict_layout", entry != null)
                .put("factory_seconds", entry?.factorySeconds ?: JSONObject.NULL)
                .put("event_type", entry?.eventType ?: JSONObject.NULL)
                .put("value1", entry?.value1 ?: JSONObject.NULL)
                .put("value2", entry?.value2 ?: JSONObject.NULL)
                .put("value3", entry?.value3 ?: JSONObject.NULL)
                .put("sequence", entry?.sequence ?: JSONObject.NULL)
                .put("index", entry?.index ?: JSONObject.NULL)
                .putSessionSnapshot(),
        )
    }

    @SuppressLint("MissingPermission")
    private fun consumeRead(
        owner: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
        status: Int,
    ) {
        val read = pendingRead ?: return
        if (read.owner !== owner || characteristic !== read.expectedCharacteristic) return
        if (status != BluetoothGatt.GATT_SUCCESS || value == null) {
            recorder.fact(
                "EncryptedReadFailed",
                JSONObject()
                    .put("write_id", writeId)
                    .put("layer", YpsoWriteFailure.Layer.GATT_CALLBACK.name)
                    .put("service", characteristic.service?.uuid?.toString() ?: JSONObject.NULL)
                    .put("characteristic", characteristic.uuid.toString())
                    .put("firmware", firmware ?: JSONObject.NULL)
                    .put("code", status),
            )
            finishRead(Result.failure(IllegalStateException("read ${characteristic.uuid} status=$status")))
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
            val next = find(owner, EXTENDED_READ_SERVICE_UUID, EXTENDED_READ_UUID)
            if (next == null || !owner.readCharacteristic(next)) {
                finishRead(Result.failure(IllegalStateException("EXTREAD dispatch refused")))
            } else {
                read.expectedCharacteristic = next
            }
            return
        }
        val result =
            runCatching {
                val encrypted = YpsoFraming.parseMultiFrameRead(read.frames)
                val operation = session.begin(checkNotNull(token))
                try {
                    session.decrypt(checkNotNull(token), operation, encrypted, crypto, read.allowObservedReboot)
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
        capability: Capability? = null,
        observedValue: ByteArray? = null,
        stage: String? = null,
        service: UUID? = null,
        descriptor: UUID? = null,
    ) {
        recorder.fact(
            "BenchFailure",
            JSONObject()
                .put("write_id", writeId.ifBlank { JSONObject.NULL })
                .put("layer", layer?.name ?: JSONObject.NULL)
                .put("characteristic", characteristic?.toString() ?: JSONObject.NULL)
                .put("service", service?.toString() ?: JSONObject.NULL)
                .put("descriptor", descriptor?.toString() ?: JSONObject.NULL)
                .put("stage", stage ?: JSONObject.NULL)
                .put("capability", capability?.name ?: JSONObject.NULL)
                .put("firmware", firmware ?: JSONObject.NULL)
                .put("supervisor_firmware", supervisorFirmware ?: JSONObject.NULL)
                .put("control_protocol", controlVersion ?: JSONObject.NULL)
                .put("code", code ?: JSONObject.NULL)
                .put("observed_length", observedValue?.size ?: JSONObject.NULL)
                .put("observed_sha256", observedValue?.let(::hash) ?: JSONObject.NULL)
                .put("detail", detail),
        )
        gatt?.let {
            coordinator.ownerDisconnected(it, detail)
            close(it)
        }
        report("ERROR:$detail")
    }

    private fun failCapability(
        detail: String,
        capability: Capability,
        layer: YpsoWriteFailure.Layer,
        code: Int? = null,
        value: ByteArray? = null,
    ) =
        fail(
            detail,
            layer,
            capability.uuid,
            code,
            capability,
            value,
            stage = "CAPABILITY_IDENTITY",
            service = capability.service,
        )

    @SuppressLint("MissingPermission")
    private fun close(owner: BluetoothGatt) {
        if (gatt !== owner) return
        gatt = null
        resetActivityState(owner)
    }

    @SuppressLint("MissingPermission")
    private fun resetActivityState(owner: BluetoothGatt? = null) {
        pendingRead = null
        pendingCapabilityRead = null
        pendingCapabilityIndex = 0
        firmware = null
        supervisorFirmware = null
        controlVersion = null
        expectedAuthCharacteristic = null
        expectedSelectorCharacteristic = null
        controlNotificationCharacteristic = null
        expectedDescriptor = null
        handshakePhase = HandshakePhase.IDLE
        owner?.let {
            readiness.disconnected(it)
            coordinator.releaseOwner(it)
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
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
        resetActivityState()
    }

    private fun releaseRunLease() {
        if (!runLeaseHeld) return
        runLeaseHeld = false
        runLease.set(false)
    }

    private fun readinessOwner(owner: BluetoothGatt) = YpsoCommandReadiness.Owner(owner, connectionId, checkNotNull(token).generation)

    private fun resolveSelector(
        owner: BluetoothGatt,
        selector: Selector,
    ): SelectorBinding? {
        val bindings =
            owner.services.mapNotNull { service ->
                val index = service.getCharacteristic(selector.indexUuid)
                val value = service.getCharacteristic(selector.valueUuid)
                if (index != null && value != null) SelectorBinding(service.uuid, index, value) else null
            }
        if (bindings.size == 1) return bindings.single()
        recorder.fact(
            "SelectorServiceBindingRejected",
            JSONObject()
                .put("write_id", writeId)
                .put("selector_type", selector.name)
                .put("index_characteristic", selector.indexUuid.toString())
                .put("value_characteristic", selector.valueUuid.toString())
                .put("matching_services", org.json.JSONArray(bindings.map { it.service.toString() })),
        )
        fail(
            "selector service binding missing or ambiguous",
            YpsoWriteFailure.Layer.READINESS,
            selector.indexUuid,
            stage = "SELECTOR_SERVICE_BINDING",
        )
        return null
    }

    private fun findUnique(
        owner: BluetoothGatt,
        uuid: UUID,
    ): BluetoothGattCharacteristic? = owner.services.mapNotNull { it.getCharacteristic(uuid) }.singleOrNull()

    private fun find(
        owner: BluetoothGatt,
        service: UUID,
        characteristic: UUID,
    ): BluetoothGattCharacteristic? = owner.getService(service)?.getCharacteristic(characteristic)

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

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

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
        return BenchSelectorEvidenceDecoder.history(body).embeddedHistoryIndex
    }

    private fun selectorEvidence(selected: Selector, body: ByteArray): BenchSelectorEvidence =
        if (selected.name == "setting") {
            BenchSelectorEvidenceDecoder.setting(body)
        } else {
            BenchSelectorEvidenceDecoder.history(body, selected.value)
        }

    private fun historyFamily(selectorName: String): PumpSession.HistoryFamily? =
        when (selectorName) {
            "alarm" -> PumpSession.HistoryFamily.ALARM
            "system" -> PumpSession.HistoryFamily.SYSTEM
            else -> null
        }

    private fun JSONObject.putSessionSnapshot(): JSONObject {
        val record = session.snapshot()
        val reservation = record?.reservation
        return put("session_generation", record?.generation ?: JSONObject.NULL)
            .put("session_reboot", record?.reboot ?: JSONObject.NULL)
            .put("session_read", record?.read ?: JSONObject.NULL)
            .put("session_write", record?.write ?: JSONObject.NULL)
            .put("pending_operation", reservation?.operationId ?: JSONObject.NULL)
            .put("pending_phase", reservation?.phase?.name ?: JSONObject.NULL)
            .put(
                "retired_legacy_operation",
                record?.retiredLegacyBenchAlarmCursorRecovery?.operationId ?: JSONObject.NULL,
            )
    }

    private fun android.content.Intent.intExtraOrNull(name: String): Int? =
        takeIf { hasExtra(name) }?.getIntExtra(name, -1)?.also { require(it > 0) { "$name must be positive" } }

    private fun report(value: String) {
        progress(value)
        if (!value.startsWith("CONNECTING:")) finish()
    }

    private fun progress(value: String) {
        File(filesDir, "result.txt").outputStream().use { out ->
            out.write(value.toByteArray())
            out.fd.sync()
        }
        android.util.Log.i("YpsoWriteBench", value)
    }

    private data class ReadTransaction(
        val owner: BluetoothGatt,
        var expectedCharacteristic: BluetoothGattCharacteristic,
        val allowObservedReboot: Boolean,
        val done: (Result<ByteArray>) -> Unit,
        val frames: MutableList<ByteArray> = mutableListOf(),
        var total: Int = 0,
    )

    private data class CapabilityRead(
        val owner: BluetoothGatt,
        val capability: Capability,
        val characteristic: BluetoothGattCharacteristic,
    )

    private data class SelectorBinding(
        val service: UUID,
        val index: BluetoothGattCharacteristic,
        val value: BluetoothGattCharacteristic,
    )

    private enum class HandshakePhase {
        IDLE,
        CONNECTING,
        DISCOVERING,
        AUTHENTICATING,
        ENABLING_SETUP,
        READING_CAPABILITIES,
        PRIMING_READ,
        READY,
    }

    private enum class RunKind {
        SELECTOR,
        BOOTSTRAP_NEW_EPOCH,
        AMBIGUITY_CONVERGENCE,
        SETTINGS_COUNTER_RECOVERY,
        SETTINGS_COUNTER_JUMP,
        DUPLICATE_COUNTER_PROBE,
        OBSERVE_SELECTOR,
        READ_SELECTOR_STATE,
        OBSERVE_REBOOT,
        RECORD_BOOTSTRAP_REFERENCE,
        READ_HISTORY_COUNTS,
        CAPTURE_CURRENT_HISTORY,
        PROFILE_ACQUISITION,
        READINESS_PROBE,
    }

    private enum class Capability(
        val label: String,
        val service: UUID,
        val uuid: UUID,
    ) {
        MASTER_FIRMWARE(
            "master firmware",
            IDENTITY_SERVICE_UUID,
            UUID.fromString("669a0c20-0008-969e-e211-fcbeb0147bc5"),
        ),
        SUPERVISOR_FIRMWARE(
            "supervisor firmware",
            IDENTITY_SERVICE_UUID,
            UUID.fromString("669a0c20-0008-969e-e211-fcbeb1147bc5"),
        ),
        CONTROL_PROTOCOL(
            "control protocol",
            CONTROL_SERVICE_UUID,
            UUID.fromString("669a0c20-0008-969e-e211-fcbee08b7bc5"),
        ),
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
        val IDENTITY_SERVICE_UUID: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000adde0000")
        val CONTROL_SERVICE_UUID: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0000")
        val EXTENDED_READ_SERVICE_UUID: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0002")
        val EVENT_COUNT_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecb3b7bc5")
        val EVENT_VALUE_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecd3b7bc5")
        val SYSTEM_DATE_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbedc3b7bc5")
        val SYSTEM_TIME_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbedd3b7bc5")
        val ALARM_VALUE_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeca3b7bc5")
        val SYSTEM_VALUE_UUID: UUID = UUID.fromString("ae3022af-2ec8-bf88-e64c-da68c9a3891a")
        val SETTING_VALUE_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb4147bc5")
        val EXTENDED_READ_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff000000ff")
        val CONTROL_PROTOCOL_VERSION_WIRE = "1.3\u0000".toByteArray(Charsets.US_ASCII)
        val CAPABILITY_READS = Capability.entries
        const val EXPECTED_SELECTOR_FRAME_COUNT =
            (YpsoGlb.SIZE + SessionCrypto.COUNTER_DATA_SIZE + SessionCrypto.TAG_SIZE + SessionCrypto.NONCE_SIZE + 18) / 19
        const val MAX_EVIDENCE_BYTES = 64L * 1024 * 1024
        val runLease = AtomicBoolean(false)
    }
}
