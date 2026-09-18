package app.aaps.ypso.writebench

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.aaps.pump.ypsopump.ble.YpsoAuthentication
import app.aaps.pump.ypsopump.ble.YpsoBolusWriteCoordinator
import app.aaps.pump.ypsopump.ble.YpsoHistorySelectorCoordinator
import app.aaps.pump.ypsopump.ble.YpsoProfileSelectorCoordinator
import app.aaps.pump.ypsopump.ble.YpsoSerializedWriteTransport
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptFileStore
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptJournal
import app.aaps.pump.ypsopump.bolus.YpsoBolusBaseline
import app.aaps.pump.ypsopump.bolus.YpsoBolusBlock
import app.aaps.pump.ypsopump.bolus.YpsoBolusOutcome
import app.aaps.pump.ypsopump.bolus.YpsoBolusRequestValidator
import app.aaps.pump.ypsopump.bolus.YpsoBolusShape
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import app.aaps.pump.ypsopump.bolus.YpsoValidatedBolusRequest
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.comm.commands.StatusCommand
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.crypto.SessionJournal
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoEventIdentity
import app.aaps.pump.ypsopump.history.YpsoHistoryCursor
import app.aaps.pump.ypsopump.history.YpsoHistoryFingerprint
import app.aaps.pump.ypsopump.history.YpsoHistoryKind
import app.aaps.pump.ypsopump.history.YpsoHistoryReconciler
import app.aaps.pump.ypsopump.history.YpsoHistoryReconciliation
import app.aaps.pump.ypsopump.history.YpsoHistorySnapshot
import app.aaps.pump.ypsopump.history.YpsoPumpLocalTime
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocumentParser
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject

/** Explicit disconnected-pump therapy qualification entry point. Not part of the distributed app. */
class BolusBenchActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val session by lazy { PumpSession(SessionJournal(this)) }
    private val crypto = SessionCrypto()
    private val transport = YpsoSerializedWriteTransport(
        { action, delay -> handler.postDelayed(action, delay) },
        { action -> handler.removeCallbacks(action) },
    )
    private val coordinator by lazy { YpsoBolusWriteCoordinator(session, crypto, transport) }
    private val historyCoordinator by lazy { YpsoHistorySelectorCoordinator(session, crypto, transport) }
    private val profileCoordinator by lazy { YpsoProfileSelectorCoordinator(session, crypto, transport) }
    private val attempts by lazy { YpsoBolusAttemptJournal(YpsoBolusAttemptFileStore(File(noBackupFilesDir, "bolus-attempt.json"))) }
    private var gatt: BluetoothGatt? = null
    private var token: PumpSession.Token? = null
    private var sharedKey: ByteArray? = null
    private var connectionId = ""
    private var action = ""
    private var validatedRequest: YpsoValidatedBolusRequest? = null
    private var autoCancelAfterIdentity = false
    private var autoCancelDispatched = false
    private var lastAcceptedStatus: BolusCommand? = null
    private var baselineStatus: BolusCommand? = null
    private var baselineHistory: YpsoHistoryEntry? = null
    private var pendingRead: PendingRead? = null
    private var currentWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var expectedSetupDescriptor: BluetoothGattDescriptor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            require(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
            action = intent.getStringExtra("action") ?: error("action required")
            require(action in setOf("start-bolus", "cancel-bolus", "reconcile-bolus", "inspect-bolus", "validate-bolus", "read-status"))
            if (action == "inspect-bolus") {
                report("ATTEMPT:${attempts.current()?.toString() ?: "none"}")
                return
            }
            val units = numericExtra("units", Double.NaN)
            autoCancelAfterIdentity = intent.getBooleanExtra("start_then_cancel", false)
            if (action == "start-bolus" || action == "validate-bolus") {
                val validated = YpsoBolusRequestValidator.validateDelivery(
                    totalUnits = units,
                    durationMinutes = intExtra("duration_minutes", 0),
                    immediateUnits = numericExtra("immediate_units", 0.0),
                    treatment = YpsoBolusTreatment.NORMAL,
                    aapsMaxBolus = numericExtra("aaps_max_bolus", 30.0),
                )
                validatedRequest = validated
                if (action == "validate-bolus") {
                    report(
                        "VALIDATED:shape=${validated.shape};total=${validated.units};" +
                            "duration=${validated.durationMinutes};immediate=${validated.immediateUnits};" +
                            "extended=${validated.extendedCentiUnits / 100.0}",
                    )
                    return
                }
            }
            report("CONNECTING:action=$action")
            connect()
        }.onFailure { report("ERROR:${it.javaClass.simpleName}:${it.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val documentFile = File(filesDir, "ypso-keys.json")
        val doc = YpsoSessionDocumentParser.parse(documentFile.readBytes())
        sharedKey = doc.sharedKey
        token = session.open(doc.mac, doc.sharedKey)
        connectionId = UUID.randomUUID().toString()
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        gatt = adapter.getRemoteDevice(doc.mac).connectGatt(this, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(owner: BluetoothGatt, status: Int, newState: Int) {
            if (owner !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) owner.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) finishWith("DISCONNECTED:status=$status")
        }

        override fun onServicesDiscovered(owner: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return finishWith("ERROR:services=$status")
            val auth = find(owner, YpsoWritePolicy.AUTH_UUID) ?: return finishWith("ERROR:auth missing")
            write(owner, auth, YpsoAuthentication.password(owner.device.address))
        }

        override fun onCharacteristicWrite(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == YpsoWritePolicy.AUTH_UUID) {
                if (status != BluetoothGatt.GATT_SUCCESS) return finishWith("ERROR:auth=$status")
                guard { enableControlNotifications(owner) }
            } else if (characteristic === currentWriteCharacteristic) {
                transport.onCharacteristicWrite(owner, characteristic.uuid, status)
            }
        }

        override fun onDescriptorWrite(owner: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (owner !== gatt || descriptor !== expectedSetupDescriptor) return
            expectedSetupDescriptor = null
            if (status != BluetoothGatt.GATT_SUCCESS) return finishWith("ERROR:control_cccd=$status")
            guard { preflight(owner) }
        }

        override fun onCharacteristicRead(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            consumeRead(owner, characteristic, value, status)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            consumeRead(owner, characteristic, characteristic.value, status)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableControlNotifications(owner: BluetoothGatt) {
        val characteristic = find(owner, YpsoWritePolicy.CONTROL_NOTIFY_UUID) ?: error("control notification characteristic missing")
        val descriptor = characteristic.getDescriptor(YpsoWritePolicy.CCCD_UUID) ?: error("control notification CCCD missing")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        require(
            YpsoWritePolicy.allowsDescriptor(
                app.aaps.pump.ypsopump.ble.YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR,
                characteristic.uuid,
                descriptor.uuid,
                value,
            ),
        )
        require(owner.setCharacteristicNotification(characteristic, true)) { "local control notification enable refused" }
        expectedSetupDescriptor = descriptor
        require(writeDescriptor(owner, descriptor, value)) { "control notification CCCD dispatch refused" }
    }

    private fun preflight(owner: BluetoothGatt) {
        readEncrypted(owner, STATUS_UUID) { statusBody ->
            val statusPayload = YpsoCrc.validatedPayload(statusBody) ?: error("system status CRC invalid")
            if (action == "read-status") return@readEncrypted probeStatus(owner, statusPayload)
            val systemStatus = StatusCommand().apply { decode(statusPayload); require(success) }
            require(!systemStatus.isSuspended) { "pump is not running" }
            require(systemStatus.reservoirUnits > 0.0) { "reservoir has no confirmed insulin" }
            readEncrypted(owner, BOLUS_STATUS_UUID) { bolusBody ->
                val payload = YpsoCrc.validatedPayload(bolusBody) ?: error("bolus status CRC invalid")
                baselineStatus = BolusCommand(0.0).apply { decode(payload); require(success) }
                if (action == "cancel-bolus") return@readEncrypted dispatchCancel(owner)
                if (action == "reconcile-bolus") return@readEncrypted reconcileTerminal(owner, checkNotNull(baselineStatus))
                require(baselineStatus?.bolusStatusCode == BolusCommand.STATUS_IDLE) { "immediate bolus already active" }
                require(baselineStatus?.extendedStatusCode == BolusCommand.STATUS_IDLE) { "extended or mixed bolus active" }
                verifyCurrentProfileEvidence(owner) {
                    selectEvent(owner, 0) { baselineHistory = it; dispatchStart(owner) }
                }
            }
        }
    }

    /** Read-only diagnostic probe: raw system and bolus status without any write. */
    private fun probeStatus(owner: BluetoothGatt, statusPayload: ByteArray) {
        readEncrypted(owner, BOLUS_STATUS_UUID) { bolusBody ->
            val payload = YpsoCrc.validatedPayload(bolusBody) ?: error("bolus status CRC invalid")
            val system = ByteBuffer.wrap(statusPayload).order(ByteOrder.LITTLE_ENDIAN)
            fun u32(offset: Int) = system.getInt(offset).toLong() and 0xFFFFFFFFL
            val bolus = BolusCommand(0.0).apply { decode(payload) }
            finishWith(
                "STATUS:mode=${statusPayload[0].toInt() and 0xFF};" +
                    "reservoir_raw=${u32(1)};bars=${statusPayload[5].toInt() and 0xFF};" +
                    "basal_raw=${u32(6)};percent_raw=${u32(10)};remaining_raw=${u32(14)};" +
                    "bolus_valid=${bolus.success};" +
                    "fast_status=${bolus.bolusStatusCode};fast_seq=${bolus.fastSequence};" +
                    "fast_injected=${bolus.deliveredUnits};fast_total=${bolus.totalProgrammedUnits};" +
                    "slow_status=${bolus.extendedStatusCode};slow_seq=${bolus.extendedSequence};" +
                    "slow_injected=${bolus.extendedDeliveredUnits};slow_total=${bolus.extendedTotalUnits};" +
                    "combo_injected=${bolus.comboImmediateDeliveredUnits};combo_total=${bolus.comboImmediateTotalUnits};" +
                    "slow_elapsed=${bolus.extendedMinutesElapsed};slow_duration=${bolus.extendedMinutesTotal}",
            )
        }
    }

    private fun verifyCurrentProfileEvidence(owner: BluetoothGatt, done: () -> Unit) {
        val captureFile = File(filesDir, "profile-captures.jsonl")
        require(captureFile.exists()) { "qualified profile capture is missing" }
        val capture = captureFile.useLines { lines ->
            lines.filter(String::isNotBlank).map(::JSONObject).lastOrNull()
        } ?: error("qualified profile capture is empty")
        val record = checkNotNull(session.snapshot())
        require(capture.getString("capture_kind") == "ATOMIC_PROFILE_ACQUISITION") { "profile capture kind is unsupported" }
        require(capture.getString("session_generation") == checkNotNull(token).generation) { "profile capture belongs to another session" }
        require(capture.getInt("pump_reboot") == record.reboot) { "profile capture belongs to another pump epoch" }
        val capturedElapsed = capture.getLong("acquired_elapsed_ms")
        val age = android.os.SystemClock.elapsedRealtime() - capturedElapsed
        require(age in 0 until PROFILE_EVIDENCE_MAX_AGE_MS) { "profile capture is not current" }
        val expectedRows = (capture.getJSONArray("profile_a_centi_units_per_hour").let { array ->
            (0 until array.length()).map(array::getInt)
        } + capture.getJSONArray("profile_b_centi_units_per_hour").let { array ->
            (0 until array.length()).map(array::getInt)
        })
        require(expectedRows.size == 48) { "profile capture does not contain all schedule rows" }
        fun activeName(body: ByteArray): String = when (YpsoGlb.decodeExact(body)) {
            3 -> "A"
            10 -> "B"
            else -> error("active program is unsupported")
        }
        fun readRows(settingId: Int) {
            if (settingId > 61) {
                selectSetting(owner, 1) { activeAfter ->
                    require(activeName(activeAfter) == capture.getString("active_program")) {
                        "active pump program changed during schedule verification"
                    }
                    done()
                }
                return
            }
            selectSetting(owner, settingId) { body ->
                val value = YpsoGlb.decodeExact(body) ?: error("schedule row $settingId is not exact GLB")
                require(value == expectedRows[settingId - 14]) { "schedule row $settingId differs from qualified profile" }
                readRows(settingId + 1)
            }
        }
        selectSetting(owner, 1) { activeBefore ->
            require(activeName(activeBefore) == capture.getString("active_program")) {
                "active pump program changed since profile qualification"
            }
            readRows(14)
        }
    }

    private fun selectSetting(owner: BluetoothGatt, settingId: Int, done: (ByteArray) -> Unit) {
        val characteristic = find(owner, YpsoWritePolicy.SETTING_ID_UUID) ?: error("setting selector missing")
        require(characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) { "setting selector is unreadable" }
        currentWriteCharacteristic = characteristic
        val ownerIdentity = YpsoProfileSelectorCoordinator.Owner(owner, connectionId, checkNotNull(token))
        readEncrypted(owner, YpsoWritePolicy.SETTING_ID_UUID) { beforeBody ->
            val before = YpsoGlb.decodeExact(beforeBody) ?: error("pre-setting selector identity is not exact GLB")
            if (before == settingId) {
                readEncrypted(owner, SETTING_VALUE_UUID, done)
                return@readEncrypted
            }
            val writeId = "bolus-profile-$connectionId-$settingId-${UUID.randomUUID()}"
            val started = profileCoordinator.write(
                writeId,
                ownerIdentity,
                settingId,
                null,
                8_000,
                dispatch = { frame -> write(owner, characteristic, frame) },
            ) { outcome ->
                if (outcome is YpsoWriteOutcome.Verified) return@write
                if (outcome !is YpsoWriteOutcome.AcceptedUnverified) {
                    finishWith("OUTCOME:ProfileSelector${outcome.javaClass.simpleName};setting=$settingId")
                    return@write
                }
                readEncrypted(owner, YpsoWritePolicy.SETTING_ID_UUID) { identityBody ->
                    require(YpsoGlb.decodeExact(identityBody) == settingId) { "setting selector identity mismatch" }
                    require(
                        profileCoordinator.reconcileAccepted(
                            writeId,
                            ownerIdentity,
                            hash(identityBody),
                            "same-link changed exact-GLB selector identity matched setting $settingId",
                        ),
                    ) { "setting selector reconciliation failed" }
                    readEncrypted(owner, SETTING_VALUE_UUID, done)
                }
            }
            if (!started) finishWith("OUTCOME:ProfileSelectorNotStarted;setting=$settingId")
        }
    }

    private fun dispatchStart(owner: BluetoothGatt) {
        val status = checkNotNull(baselineStatus)
        val history = checkNotNull(baselineHistory)
        val record = checkNotNull(session.snapshot())
        val doc = YpsoSessionDocumentParser.parse(File(filesDir, "ypso-keys.json").readBytes())
        val validated = checkNotNull(validatedRequest)
        val payloadHash = hash(YpsoCrc.appendCrc(validated.payload()))
        val requestId = intent.getStringExtra("write_id") ?: UUID.randomUUID().toString()
        attempts.prepare(
            YpsoBolusAttempt(
                requestId = requestId,
                pumpSerial = doc.serial,
                sessionGeneration = checkNotNull(token).generation,
                treatment = YpsoBolusTreatment.NORMAL,
                requestedCentiUnits = validated.centiUnits,
                payloadHash = payloadHash,
                baseline = YpsoBolusBaseline(
                    status.fastSequence,
                    status.extendedSequence,
                    history.sequence,
                    history.fingerprint().high,
                    history.fingerprint().low,
                    checkNotNull(record.reboot),
                    System.currentTimeMillis(),
                ),
                createdAt = System.currentTimeMillis(),
                shape = validated.shape,
                durationMinutes = validated.durationMinutes,
                immediateCentiUnits = validated.immediateCentiUnits,
            ),
        )
        dispatch(owner, requestId, cancel = false)
        doc.sharedKey.fill(0)
    }

    private fun dispatchCancel(owner: BluetoothGatt) {
        val attempt = attempts.current() ?: error("no durable bolus attempt")
        require(attempt.inhibitsAutomatedDelivery && attempt.cancelRequestId == null) { "attempt is not cancellable" }
        val document = YpsoSessionDocumentParser.parse(File(filesDir, "ypso-keys.json").readBytes())
        try {
            require(attempt.pumpSerial == document.serial) { "attempt belongs to another pump" }
        } finally {
            document.sharedKey.fill(0)
        }
        val record = checkNotNull(session.snapshot())
        require(attempt.sessionGeneration == checkNotNull(token).generation && attempt.baseline.pumpReboot == record.reboot) {
            "attempt belongs to another session epoch"
        }
        val block = requireNotNull(attempt.provenCancelBlock) { "bolus status identity was never proven" }
        val identity = requireNotNull(attempt.provenSequence(block)) { "bolus status identity was never proven" }
        val programmed = requireNotNull(attempt.programmedCentiUnits(block))
        val status = checkNotNull(baselineStatus)
        val preDispatchDelivered = when (block) {
            YpsoBolusBlock.FAST -> {
                require(status.bolusStatusCode == BolusCommand.STATUS_DELIVERING) { "intended immediate bolus is not currently delivering" }
                require(status.fastSequence == identity) { "active immediate bolus identity changed" }
                require(cents(status.totalProgrammedUnits) == programmed) { "active programmed amount differs from intended bolus" }
                cents(status.deliveredUnits)
            }
            YpsoBolusBlock.SLOW -> {
                require(status.extendedStatusCode in setOf(BolusCommand.STATUS_DELIVERING, BolusCommand.STATUS_MIXED_DELIVERING)) {
                    "intended extended bolus is not currently delivering"
                }
                require(status.extendedSequence == identity) { "active extended bolus identity changed" }
                require(cents(status.extendedTotalUnits) == programmed) { "active programmed extended amount differs from intended bolus" }
                cents(status.extendedDeliveredUnits)
            }
        }
        dispatch(owner, intent.getStringExtra("write_id") ?: UUID.randomUUID().toString(), cancel = true, preDispatchDelivered = preDispatchDelivered)
    }

    private fun reconcileTerminal(owner: BluetoothGatt, status: BolusCommand) {
        val attempt = attempts.current() ?: error("no durable bolus attempt")
        require(attempt.inhibitsAutomatedDelivery) { "attempt is already terminal or certainly not sent" }
        val document = YpsoSessionDocumentParser.parse(File(filesDir, "ypso-keys.json").readBytes())
        try {
            require(attempt.pumpSerial == document.serial) { "attempt belongs to another pump" }
        } finally {
            document.sharedKey.fill(0)
        }
        val record = checkNotNull(session.snapshot())
        require(attempt.sessionGeneration == checkNotNull(token).generation && attempt.baseline.pumpReboot == record.reboot) {
            "attempt belongs to another session epoch"
        }
        readEventCount(owner) { countBefore ->
            require(countBefore > 0) { "event history is empty" }
            val rows = mutableListOf<YpsoHistoryEntry>()
            val maxRows = intent.getIntExtra("max_history_rows", 128).coerceIn(1, 512)
            fun finishScan() {
                readEventCount(owner) { countAfter ->
                    selectEvent(owner, 0) { headAfter ->
                        val headBefore = rows.firstOrNull() ?: error("history head was not captured")
                        val snapshot = YpsoHistorySnapshot(
                            countBefore,
                            countAfter,
                            checkNotNull(record.reboot).toLong(),
                            checkNotNull(record.reboot).toLong(),
                            headBefore,
                            headAfter,
                            rows.toList(),
                            fullCoverage = rows.size == countBefore,
                        )
                        val baseline = attempt.baseline
                        val cursor = YpsoHistoryCursor(
                            YpsoEventIdentity(attempt.pumpSerial, 0, baseline.historyPumpId),
                            YpsoHistoryFingerprint(baseline.historyFingerprintHigh, baseline.historyFingerprintLow),
                            baseline.pumpReboot.toLong(),
                        )
                        val reconciliation = YpsoHistoryReconciler.reconcile(cursor, snapshot)
                        val stable = reconciliation as? YpsoHistoryReconciliation.Stable
                            ?: return@selectEvent finishWith("OUTCOME:Unresolved;history=$reconciliation")
                        val completedKinds = when (attempt.shape) {
                            YpsoBolusShape.IMMEDIATE -> setOf(YpsoHistoryKind.IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED)
                            YpsoBolusShape.EXTENDED -> setOf(YpsoHistoryKind.DELAYED_BOLUS_COMPLETED)
                            YpsoBolusShape.COMBINED -> setOf(YpsoHistoryKind.COMBINED_BOLUS_COMPLETED)
                        }
                        val abortedKinds = when (attempt.shape) {
                            YpsoBolusShape.IMMEDIATE -> setOf(YpsoHistoryKind.IMMEDIATE_BOLUS_ABORTED)
                            YpsoBolusShape.EXTENDED -> setOf(YpsoHistoryKind.DELAYED_BOLUS_ABORTED)
                            YpsoBolusShape.COMBINED -> setOf(YpsoHistoryKind.COMBINED_BOLUS_ABORTED)
                        }
                        val identities = setOfNotNull(attempt.pumpFastSequence, attempt.pumpSlowSequence)
                        val compatible = stable.newEventsOldestFirst.filter {
                            it.semantics.kind in completedKinds + abortedKinds && it.identity.sequence in identities
                        }
                        if (compatible.size != 1) {
                            val historySummary = stable.newEventsOldestFirst.joinToString(",") { event ->
                                "${event.identity.aapsPumpId}:${event.entry.sequence}:${event.entry.eventType}:" +
                                    "${event.entry.value1}/${event.entry.value2}/${event.entry.value3}:" +
                                    "${event.semantics.kind}:${event.semantics.amountUnits}"
                            }
                            val noEffect = status.bolusStatusCode == BolusCommand.STATUS_IDLE &&
                                status.extendedStatusCode == BolusCommand.STATUS_IDLE &&
                                status.fastSequence == attempt.baseline.fastSequence &&
                                status.extendedSequence == attempt.baseline.slowSequence &&
                                cents(status.totalProgrammedUnits) == 0 &&
                                cents(status.deliveredUnits) == 0 &&
                                cents(status.extendedTotalUnits) == 0 &&
                                cents(status.extendedDeliveredUnits) == 0 &&
                                stable.newEventsOldestFirst.isEmpty() &&
                                countBefore == countAfter
                            if (noEffect) {
                                val rejected = attempts.reconcileNoEffect(
                                    attempt.requestId,
                                    "stable status remained idle at baseline sequences with zero programmed/delivered and no history movement",
                                )
                                return@selectEvent finishWith(
                                    "OUTCOME:${rejected.outcome};fast_status=${status.bolusStatusCode};" +
                                        "fast=${status.fastSequence};slow_status=${status.extendedStatusCode};" +
                                        "slow=${status.extendedSequence};history_new=0;" +
                                        "history_count_before=$countBefore;history_count_after=$countAfter",
                                )
                            }
                            attempts.unresolved(attempt.requestId, "terminal history contained ${compatible.size} compatible bolus rows")
                            return@selectEvent finishWith(
                                "OUTCOME:Unresolved;compatible=${compatible.size};shape=${attempt.shape};" +
                                    "fast_status=${status.bolusStatusCode};fast=${status.fastSequence};" +
                                    "slow_status=${status.extendedStatusCode};slow=${status.extendedSequence};" +
                                    "history_new=${stable.newEventsOldestFirst.size};" +
                                    "history_count_before=$countBefore;history_count_after=$countAfter;" +
                                    "history_rows=$historySummary",
                            )
                        }
                        val event = compatible.single()
                        val block = if (attempt.pumpSlowSequence != null && event.identity.sequence == attempt.pumpSlowSequence) {
                            YpsoBolusBlock.SLOW
                        } else {
                            YpsoBolusBlock.FAST
                        }
                        require(event.identity.sequence == attempt.provenSequence(block)) {
                            "terminal identity does not match the proven bolus block"
                        }
                        val programmed = requireNotNull(attempt.programmedCentiUnits(block))
                        val rowAmount = Math.round(checkNotNull(event.semantics.amountUnits) * 100.0).toInt()
                        val aborted = event.semantics.kind in abortedKinds
                        // Target-paired semantics: completed rows of every bolus shape carry the
                        // delivered amount, including a cancelled square or combination (observed 8
                        // centi-units of 50 and 40 of 100). Cancellation observations during the run
                        // are progress/lower-bound evidence only; abort rows remain unqualified.
                        val rowAmountIsDelivered = when (event.semantics.kind) {
                            YpsoHistoryKind.IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED,
                            YpsoHistoryKind.DELAYED_BOLUS_COMPLETED,
                            YpsoHistoryKind.COMBINED_BOLUS_COMPLETED -> true
                            else -> false
                        }
                        val delivered = if (rowAmountIsDelivered) {
                            if (rowAmount in 0..programmed) rowAmount else null
                        } else {
                            attempt.cancelObservedCentiUnits?.coerceAtMost(programmed)
                        }
                        if (delivered == null) {
                            attempts.unresolved(attempt.requestId, "terminal history amount is not qualified for the proven bolus block")
                            return@selectEvent finishWith(
                                "OUTCOME:Unresolved;unqualified_amount;shape=${attempt.shape};block=$block;" +
                                    "history_kind=${event.semantics.kind};row=${rowAmount / 100.0};" +
                                    "programmed=${programmed / 100.0};cancel_observed=${attempt.cancelObservedCentiUnits?.div(100.0)};" +
                                    "sequence=${event.identity.sequence};history_pump_id=${event.identity.aapsPumpId}",
                            )
                        }
                        val resolved = YpsoPumpLocalTime.resolve(event.entry.factorySeconds, ZoneId.systemDefault())
                            as? YpsoPumpLocalTime.Resolution.Resolved
                            ?: return@selectEvent finishWith("OUTCOME:Unresolved;terminal timestamp ambiguous")
                        val terminal = attempts.confirmTerminal(
                            attempt.requestId,
                            delivered,
                            resolved.instant.toEpochMilli(),
                            block,
                            event.identity.sequence,
                            event.identity.aapsPumpId,
                            aborted,
                        )
                        finishWith(
                            "OUTCOME:${terminal.outcome};shape=${attempt.shape};requested=${terminal.requestedUnits};" +
                                "block=$block;proven_sequence=${event.identity.sequence};" +
                                "history_kind=${event.semantics.kind};history_amount=${rowAmount / 100.0};" +
                                "row_values=${event.entry.value1}/${event.entry.value2}/${event.entry.value3};" +
                                "cancel_observed=${attempt.cancelObservedCentiUnits?.div(100.0)};" +
                                "delivered=${terminal.confirmedUnits};history_pump_id=${event.identity.aapsPumpId}",
                        )
                    }
                }
            }
            fun readRow(index: Int) {
                if (index >= minOf(countBefore, maxRows)) return finishScan()
                selectEvent(owner, index) { row ->
                    rows += row
                    val baselineFound = row.sequence == attempt.baseline.historyPumpId &&
                        row.fingerprint().high == attempt.baseline.historyFingerprintHigh &&
                        row.fingerprint().low == attempt.baseline.historyFingerprintLow
                    if (baselineFound) finishScan() else readRow(index + 1)
                }
            }
            readRow(0)
        }
    }

    private fun readEventCount(owner: BluetoothGatt, done: (Int) -> Unit) = readEncrypted(owner, EVENT_COUNT_UUID) { body ->
        val count = YpsoGlb.decodeExact(body) ?: error("event count is not exact GLB")
        require(count >= 0) { "event count is negative" }
        done(count)
    }

    private fun dispatch(owner: BluetoothGatt, writeId: String, cancel: Boolean, preDispatchDelivered: Int? = null) {
        val characteristic = find(owner, YpsoWritePolicy.BOLUS_START_STOP_UUID) ?: error("bolus command characteristic missing")
        currentWriteCharacteristic = characteristic
        val operationOwner = YpsoBolusWriteCoordinator.Owner(owner, connectionId, checkNotNull(token))
        val onOutcome: (YpsoWriteOutcome) -> Unit = { outcome ->
            val attempt = attempts.current()
            when (outcome) {
                is YpsoWriteOutcome.AcceptedUnverified -> {
                    if (!cancel) attempts.transportAccepted(writeId)
                    observeBolusStatus(owner, writeId, cancel, operationOwner)
                }
                is YpsoWriteOutcome.NotSent -> {
                    if (cancel && attempt?.outcome == YpsoBolusOutcome.CANCEL_PENDING) {
                        attempts.cancelNotSent(attempt.requestId, outcome.failure.detail)
                    } else if (!cancel && attempt?.requestId == writeId) {
                        attempts.provenNotApplied(writeId, rejected = false, detail = outcome.failure.detail)
                    }
                    finishWith("OUTCOME:NotSent;${outcome.failure.detail}")
                }
                is YpsoWriteOutcome.ProvenRejected -> {
                    if (cancel && attempt?.outcome == YpsoBolusOutcome.CANCEL_PENDING) {
                        attempts.cancelNotSent(attempt.requestId, "cancel was proven rejected: ${outcome.failure.detail}")
                    } else if (!cancel && attempt?.requestId == writeId) {
                        attempts.provenNotApplied(writeId, rejected = true, detail = outcome.failure.detail)
                    }
                    finishWith(
                        "OUTCOME:ProvenRejected;layer=${outcome.failure.layer};code=${outcome.failure.code};" +
                            "frame=${outcome.failure.frame};detail=${outcome.failure.detail}",
                    )
                }
                is YpsoWriteOutcome.PossiblyApplied -> finishWith(
                    "OUTCOME:PossiblyApplied;layer=${outcome.failure.layer};code=${outcome.failure.code};" +
                        "frame=${outcome.failure.frame};detail=${outcome.failure.detail};reconciliation required",
                )
                is YpsoWriteOutcome.Verified -> {
                    if (autoCancelAfterIdentity && !autoCancelDispatched && attempt?.outcome == YpsoBolusOutcome.DELIVERING) {
                        autoCancelDispatched = true
                        autoCancelSameLink(owner, checkNotNull(lastAcceptedStatus))
                    } else {
                        finishWith("OUTCOME:Verified;${outcome.evidence}")
                    }
                }
            }
        }
        val started = if (cancel) {
            val attempt = checkNotNull(attempts.current())
            val block = requireNotNull(attempt.provenCancelBlock) { "attempt has no proven cancellation target" }
            coordinator.cancel(
                writeId, operationOwner, block, null, 8_000,
                beforeDispatch = {
                    attempts.requestCancel(attempt.requestId, writeId, it.counter, block)
                    preDispatchDelivered?.let { delivered -> attempts.observeCancelDelivery(attempt.requestId, delivered) }
                },
                dispatch = { frame -> write(owner, characteristic, frame) },
                onOutcome = onOutcome,
            )
        } else {
            coordinator.start(
                writeId, operationOwner, checkNotNull(validatedRequest), null, 8_000,
                beforeDispatch = { attempts.beforeDispatch(writeId, it.counter, System.currentTimeMillis()) },
                dispatch = { frame -> write(owner, characteristic, frame) },
                onOutcome = onOutcome,
            )
        }
        if (!started) finishWith("OUTCOME:NotStarted")
    }

    /**
     * Reads the bolus status after a dispatched command. A start is allowed a bounded set of
     * read-only retries so a combination bolus can surface its extended block identity; a
     * cancellation is observed once, immediately.
     */
    private fun observeBolusStatus(
        owner: BluetoothGatt,
        writeId: String,
        cancel: Boolean,
        operationOwner: YpsoBolusWriteCoordinator.Owner,
    ) {
        var remaining = if (cancel) 1 else STATUS_EVIDENCE_ATTEMPTS
        fun readOnce() {
            readEncrypted(owner, BOLUS_STATUS_UUID) { body ->
                val payload = YpsoCrc.validatedPayload(body) ?: error("post-write bolus status CRC invalid")
                val status = BolusCommand(0.0).apply { decode(payload); require(success) }
                val evidenceHash = hash(body)
                if (cancel) {
                    val attempt = checkNotNull(attempts.current())
                    val block = checkNotNull(attempt.cancelBlock)
                    val identity = attempt.provenSequence(block)
                    val observed = when (block) {
                        YpsoBolusBlock.FAST -> cents(status.deliveredUnits)
                        YpsoBolusBlock.SLOW -> cents(status.extendedDeliveredUnits)
                    }
                    attempts.observeCancelDelivery(attempt.requestId, observed)
                    val settled = when (block) {
                        YpsoBolusBlock.FAST -> status.bolusStatusCode == BolusCommand.STATUS_IDLE &&
                            (status.fastSequence == identity || status.fastSequence == 0L)
                        YpsoBolusBlock.SLOW -> status.extendedStatusCode == BolusCommand.STATUS_IDLE &&
                            (status.extendedSequence == identity || status.extendedSequence == 0L)
                    }
                    if (settled) {
                        coordinator.reconcileAccepted(writeId, operationOwner, evidenceHash, "same-link cancellation left the proven block idle")
                    } else {
                        coordinator.recordUnresolved(writeId, operationOwner, evidenceHash, "bolus status did not prove the cancellation effect")
                    }
                    finishWith(cancelReport(status, block, observed, settled))
                    return@readEncrypted
                }
                val attempt = checkNotNull(attempts.current())
                val fastMatched = matchesFastBlock(attempt, status)
                val slowMatched = matchesSlowBlock(attempt, status)
                if (fastMatched) attempts.observeFastDelivering(writeId, status.fastSequence, cents(status.totalProgrammedUnits))
                if (slowMatched) attempts.observeSlowDelivering(writeId, status.extendedSequence, cents(status.extendedTotalUnits))
                val accepted = fastMatched || slowMatched
                val stillActive = status.bolusStatusCode == BolusCommand.STATUS_DELIVERING ||
                    status.extendedStatusCode in setOf(BolusCommand.STATUS_DELIVERING, BolusCommand.STATUS_MIXED_DELIVERING)
                remaining -= 1
                if (!accepted && stillActive && remaining > 0) {
                    handler.postDelayed({ readOnce() }, STATUS_EVIDENCE_INTERVAL_MS)
                    return@readEncrypted
                }
                if (accepted) {
                    lastAcceptedStatus = status
                    coordinator.reconcileAccepted(writeId, operationOwner, evidenceHash, "same-link bolus status matched command identity")
                    if (autoCancelDispatched) return@readEncrypted
                } else {
                    coordinator.recordUnresolved(writeId, operationOwner, evidenceHash, "bolus status did not prove command identity/outcome")
                }
                finishWith(startReport(status, accepted))
            }
        }
        readOnce()
    }

    /**
     * Explicit bench-only fast-path cancellation: the start identity was just proven on this
     * connection and the durable attempt is DELIVERING. Persist cancellation ownership, then
     * dispatch the cancel on the same link so a ~1 U/s standard bolus can still be cancelled.
     */
    private fun autoCancelSameLink(owner: BluetoothGatt, status: BolusCommand) {
        val attempt = checkNotNull(attempts.current())
        val block = requireNotNull(attempt.provenCancelBlock) { "attempt has no proven cancellation target" }
        val identity = requireNotNull(attempt.provenSequence(block))
        val programmed = requireNotNull(attempt.programmedCentiUnits(block))
        val preDispatchDelivered = when (block) {
            YpsoBolusBlock.FAST -> {
                require(
                    status.bolusStatusCode == BolusCommand.STATUS_DELIVERING &&
                        status.fastSequence == identity &&
                        cents(status.totalProgrammedUnits) == programmed,
                ) { "same-link fast identity changed before cancellation" }
                cents(status.deliveredUnits)
            }
            YpsoBolusBlock.SLOW -> {
                require(
                    status.extendedStatusCode in setOf(BolusCommand.STATUS_DELIVERING, BolusCommand.STATUS_MIXED_DELIVERING) &&
                        status.extendedSequence == identity &&
                        cents(status.extendedTotalUnits) == programmed,
                ) { "same-link slow identity changed before cancellation" }
                cents(status.extendedDeliveredUnits)
            }
        }
        dispatch(owner, "auto-cancel-${attempt.requestId}", cancel = true, preDispatchDelivered = preDispatchDelivered)
    }

    private fun startReport(status: BolusCommand, accepted: Boolean): String =
        "OUTCOME:${if (accepted) "AcceptedUnverified" else "Unresolved"};" +
            "fast_status=${status.bolusStatusCode};fast=${status.fastSequence};" +
            "fast_injected=${status.deliveredUnits};fast_total=${status.totalProgrammedUnits};" +
            "slow_status=${status.extendedStatusCode};slow=${status.extendedSequence};" +
            "slow_injected=${status.extendedDeliveredUnits};slow_total=${status.extendedTotalUnits};" +
            "combo_injected=${status.comboImmediateDeliveredUnits};combo_total=${status.comboImmediateTotalUnits};" +
            "elapsed=${status.extendedMinutesElapsed};duration=${status.extendedMinutesTotal}"

    private fun cancelReport(status: BolusCommand, block: YpsoBolusBlock, observed: Int, settled: Boolean): String =
        "OUTCOME:${if (settled) "AcceptedUnverified" else "Unresolved"};cancel_block=$block;observed=${observed / 100.0};" +
            "fast_status=${status.bolusStatusCode};fast=${status.fastSequence};" +
            "slow_status=${status.extendedStatusCode};slow=${status.extendedSequence};" +
            "slow_injected=${status.extendedDeliveredUnits};slow_total=${status.extendedTotalUnits}"

    /** The fast block is only bound for a standard immediate bolus; combination identity is the slow block. */
    private fun matchesFastBlock(attempt: YpsoBolusAttempt, status: BolusCommand): Boolean {
        if (attempt.shape != YpsoBolusShape.IMMEDIATE) return false
        val expected = requireNotNull(attempt.programmedCentiUnits(YpsoBolusBlock.FAST))
        return status.bolusStatusCode == BolusCommand.STATUS_DELIVERING &&
            isNewerSequence(status.fastSequence, attempt.baseline.fastSequence) &&
            cents(status.totalProgrammedUnits) == expected
    }

    private fun matchesSlowBlock(attempt: YpsoBolusAttempt, status: BolusCommand): Boolean {
        if (attempt.shape == YpsoBolusShape.IMMEDIATE) return false
        val expected = requireNotNull(attempt.programmedCentiUnits(YpsoBolusBlock.SLOW))
        return status.extendedStatusCode in setOf(BolusCommand.STATUS_DELIVERING, BolusCommand.STATUS_MIXED_DELIVERING) &&
            isNewerSequence(status.extendedSequence, attempt.baseline.slowSequence) &&
            cents(status.extendedTotalUnits) == expected
    }

    private fun cents(units: Double): Int = Math.round(units * 100.0).toInt()

    private fun isNewerSequence(candidate: Long, baseline: Long): Boolean {
        val delta = (candidate - baseline + 0x1_0000_0000L) % 0x1_0000_0000L
        return delta in 1 until 0x8000_0000L
    }

    private fun selectEvent(owner: BluetoothGatt, index: Int, done: (YpsoHistoryEntry) -> Unit) {
        val indexCharacteristic = find(owner, YpsoWritePolicy.EVENT_INDEX_UUID) ?: error("event index missing")
        require(indexCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) { "event selector identity is unreadable" }
        find(owner, EVENT_VALUE_UUID) ?: error("event value missing")
        fun readSelected() = readEncrypted(owner, EVENT_VALUE_UUID) { body ->
            val row = YpsoHistoryEntry.decodeWire(body) ?: error("history head invalid")
            require(row.index == index) { "history embedded index mismatch" }
            done(row)
        }
        readEncrypted(owner, YpsoWritePolicy.EVENT_INDEX_UUID) { beforeBody ->
            val before = YpsoGlb.decodeExact(beforeBody) ?: error("pre-selector identity is not exact GLB")
            if (before == index) {
                // Read-only evidence is enough when the desired row is already selected. Never send
                // a no-op selector because unchanged read-back cannot prove counter consumption.
                readSelected()
                return@readEncrypted
            }
            currentWriteCharacteristic = indexCharacteristic
            val selectId = "bolus-history-$connectionId-$index-${UUID.randomUUID()}"
            val historyOwner = YpsoHistorySelectorCoordinator.Owner(owner, connectionId, checkNotNull(token))
            val started = historyCoordinator.select(
                selectId,
                historyOwner,
                index,
                null,
                8_000,
                dispatch = { frame -> write(owner, indexCharacteristic, frame) },
            ) { outcome ->
                if (outcome is YpsoWriteOutcome.Verified) return@select
                if (outcome !is YpsoWriteOutcome.AcceptedUnverified) {
                    finishWith("OUTCOME:HistorySelector${outcome.javaClass.simpleName}")
                    return@select
                }
                readEncrypted(owner, YpsoWritePolicy.EVENT_INDEX_UUID) { identityBody ->
                    require(YpsoGlb.decodeExact(identityBody) == index) { "event selector identity mismatch" }
                    val evidenceHash = hash(identityBody)
                    require(
                        historyCoordinator.reconcileAccepted(
                            selectId,
                            historyOwner,
                            evidenceHash,
                            "same-link changed exact-GLB selector identity matched event index $index",
                        ),
                    ) { "event selector reconciliation failed" }
                    readSelected()
                }
            }
            if (!started) finishWith("OUTCOME:HistorySelectorNotStarted")
        }
    }

    private fun readEncrypted(owner: BluetoothGatt, uuid: UUID, done: (ByteArray) -> Unit) {
        val characteristic = find(owner, uuid) ?: error("read characteristic missing: $uuid")
        pendingRead = PendingRead(owner, characteristic, done)
        check(owner.readCharacteristic(characteristic)) { "read dispatch refused: $uuid" }
    }

    private fun consumeRead(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray?, status: Int) {
        val read = pendingRead ?: return
        if (read.owner !== owner || read.expected !== characteristic) return
        require(status == BluetoothGatt.GATT_SUCCESS && value != null) { "read failed: ${characteristic.uuid}/$status" }
        val total = YpsoFraming.validateFrame(value, read.frames.size + 1, read.total) ?: error("invalid read frame")
        read.frames += value
        if (read.total == 0) read.total = total
        if (read.frames.size < read.total) {
            val next = find(owner, EXTREAD_UUID) ?: error("extended read missing")
            read.expected = next
            check(owner.readCharacteristic(next))
            return
        }
        pendingRead = null
        val encrypted = YpsoFraming.parseMultiFrameRead(read.frames)
        val transaction = session.begin(checkNotNull(token))
        val body = try { session.decrypt(checkNotNull(token), transaction, encrypted, crypto, true) } finally { session.finish(checkNotNull(token), transaction) }
        guard { read.done(body) }
    }

    private fun guard(block: () -> Unit) {
        runCatching(block).onFailure { finishWith("ERROR:${it.javaClass.simpleName}:${it.message}") }
    }

    private fun find(owner: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        owner.services.asSequence().mapNotNull { it.getCharacteristic(uuid) }.singleOrNull()

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun write(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) owner.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        else {
            characteristic.value = value
            owner.writeCharacteristic(characteristic)
        }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "NewApi")
    private fun writeDescriptor(owner: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            owner.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            owner.writeDescriptor(descriptor)
        }

    @SuppressLint("MissingPermission")
    private fun finishWith(value: String) {
        Log.i("YpsoBolusBench", value)
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        sharedKey?.fill(0)
        gatt = null
        report(value)
    }

    private fun report(value: String) {
        FileOutputStream(File(filesDir, "result.txt")).use { output ->
            output.write((value + "\n").toByteArray())
            output.fd.sync()
        }
        setResult(RESULT_OK, android.content.Intent().putExtra("result", value))
        if (!value.startsWith("CONNECTING:")) finish()
    }

    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private data class PendingRead(
        val owner: BluetoothGatt,
        var expected: BluetoothGattCharacteristic,
        val done: (ByteArray) -> Unit,
        val frames: MutableList<ByteArray> = mutableListOf(),
        var total: Int = 0,
    )

    @Suppress("DEPRECATION")
    private fun numericExtra(name: String, default: Double): Double =
        when (val value = intent.extras?.get(name)) {
            null -> default
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: error("invalid numeric extra: $name")
            else -> error("invalid numeric extra type: $name")
        }

    private fun intExtra(name: String, default: Int): Int =
        when (val value = intent.extras?.get(name)) {
            null -> default
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: error("invalid integer extra: $name")
            else -> error("invalid integer extra type: $name")
        }

    companion object {
        private val BOLUS_STATUS_UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee28b7bc5")
        private val STATUS_UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee48b7bc5")
        private val EVENT_VALUE_UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecd3b7bc5")
        private val EVENT_COUNT_UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecb3b7bc5")
        private val SETTING_VALUE_UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb4147bc5")
        private val EXTREAD_UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff000000ff")
        private const val PROFILE_EVIDENCE_MAX_AGE_MS = 5 * 60 * 1000L
        private const val STATUS_EVIDENCE_ATTEMPTS = 8
        private const val STATUS_EVIDENCE_INTERVAL_MS = 1_500L
    }
}
