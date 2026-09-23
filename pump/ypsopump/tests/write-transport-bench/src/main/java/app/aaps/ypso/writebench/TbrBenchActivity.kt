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
import android.os.SystemClock
import android.util.Log
import app.aaps.pump.ypsopump.ble.YpsoAuthentication
import app.aaps.pump.ypsopump.ble.YpsoHistorySelectorCoordinator
import app.aaps.pump.ypsopump.ble.YpsoRemoteWrite
import app.aaps.pump.ypsopump.ble.YpsoSerializedWriteTransport
import app.aaps.pump.ypsopump.ble.YpsoTbrWriteCoordinator
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.crypto.SessionJournal
import app.aaps.pump.ypsopump.history.YpsoHistoryClassifier
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocumentParser
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/**
 * Bench-only START_STOP_TBR characterization. Sends the raw 16-byte command exactly as requested,
 * without production validation, and records system status, bolus status and new history rows
 * around it. Pump-changing: use only with a pump that is not attached to a person.
 *
 * Actions (intent extra `action`):
 * - `observe`: status, bolus status and the newest `history_rows` rows; no therapy write.
 * - `write-tbr`: extras `percent`, `duration_minutes`; optional `observe_delays_ms` (comma list).
 */
class TbrBenchActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val session by lazy { PumpSession(SessionJournal(this)) }
    private val crypto = SessionCrypto()
    private val transport = YpsoSerializedWriteTransport(
        { action, delay -> handler.postDelayed(action, delay) },
        { action -> handler.removeCallbacks(action) },
    )
    private val coordinator by lazy { YpsoTbrWriteCoordinator(session, crypto, transport) }
    private val historyCoordinator by lazy { YpsoHistorySelectorCoordinator(session, crypto, transport) }
    private var gatt: BluetoothGatt? = null
    private var token: PumpSession.Token? = null
    private var sharedKey: ByteArray? = null
    private var connectionId = ""
    private var action = ""
    private var percent = 0
    private var duration = 0
    private var pendingRead: PendingRead? = null
    private var currentWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var expectedSetupDescriptor: BluetoothGattDescriptor? = null
    private var finished = false
    private val lines = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            require(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
            action = intent.getStringExtra("action") ?: error("action required")
            require(action in setOf("observe", "write-tbr")) { "unsupported action $action" }
            if (action == "write-tbr") {
                percent = intExtra("percent", -1)
                duration = intExtra("duration_minutes", -1)
                // Wide sanity envelope only: the bench exists to measure what the pump rejects.
                require(percent in 0..1000) { "percent outside bench envelope" }
                require(duration in 0..1500) { "duration outside bench envelope" }
            }
            note("BEGIN:action=$action;percent=$percent;duration=$duration;wall=${System.currentTimeMillis()}")
            connect()
        }.onFailure { finishWith("ERROR:${it.javaClass.simpleName}:${it.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val doc = YpsoSessionDocumentParser.parse(File(filesDir, "ypso-keys.json").readBytes())
        sharedKey = doc.sharedKey
        token = session.open(doc.mac, doc.sharedKey)
        connectionId = UUID.randomUUID().toString()
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        gatt = adapter.getRemoteDevice(doc.mac).connectGatt(this, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        handler.postDelayed({ finishWith("ERROR:bench timeout") }, BENCH_TIMEOUT_MS)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(owner: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (owner !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) owner.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) finishWith("DISCONNECTED:status=$status")
            }
        }

        override fun onServicesDiscovered(owner: BluetoothGatt, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) return@post finishWith("ERROR:services=$status")
                val auth = find(owner, YpsoWritePolicy.AUTH_UUID) ?: return@post finishWith("ERROR:auth missing")
                write(owner, auth, YpsoAuthentication.password(owner.device.address))
            }
        }

        override fun onCharacteristicWrite(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (characteristic.uuid == YpsoWritePolicy.AUTH_UUID) {
                    if (status != BluetoothGatt.GATT_SUCCESS) return@post finishWith("ERROR:auth=$status")
                    guard { enableControlNotifications(owner) }
                } else if (characteristic === currentWriteCharacteristic) {
                    if (characteristic.uuid == YpsoWritePolicy.TBR_START_STOP_UUID) note("TBR_CALLBACK:status=$status")
                    transport.onCharacteristicWrite(owner, characteristic.uuid, status)
                }
            }
        }

        override fun onDescriptorWrite(owner: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (owner !== gatt || descriptor !== expectedSetupDescriptor) return@post
                expectedSetupDescriptor = null
                if (status != BluetoothGatt.GATT_SUCCESS) return@post finishWith("ERROR:control_cccd=$status")
                guard { start(owner) }
            }
        }

        override fun onCharacteristicChanged(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { note("NOTIFY:${characteristic.uuid.toString().substring(24)}:${value.hex()}") }
        }

        override fun onCharacteristicRead(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handler.post { guard { consumeRead(owner, characteristic, value, status) } }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(owner: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val value = characteristic.value?.copyOf()
            handler.post { guard { consumeRead(owner, characteristic, value, status) } }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableControlNotifications(owner: BluetoothGatt) {
        val characteristic = find(owner, YpsoWritePolicy.CONTROL_NOTIFY_UUID) ?: error("control notification characteristic missing")
        val descriptor = characteristic.getDescriptor(YpsoWritePolicy.CCCD_UUID) ?: error("control notification CCCD missing")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        require(YpsoWritePolicy.allowsDescriptor(YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR, characteristic.uuid, descriptor.uuid, value))
        require(owner.setCharacteristicNotification(characteristic, true)) { "local control notification enable refused" }
        expectedSetupDescriptor = descriptor
        require(writeDescriptor(owner, descriptor, value)) { "control notification CCCD dispatch refused" }
    }

    private fun start(owner: BluetoothGatt) {
        // History baseline first, so waiting for a pump-menu bolus is the last step before dispatch.
        readEventCount(owner) { count ->
            val rows = intExtra("history_rows", if (action == "observe") 6 else 1).coerceIn(1, 32)
            readRows(owner, count, rows, stopAt = null) { head ->
                observeStatus(owner, "PRE") { _ ->
                    if (action == "observe") return@observeStatus finishWith("DONE:observe")
                    if (intent.getBooleanExtra("wait_for_bolus", false)) awaitBolus(owner) { dispatchTbr(owner, count, head.first()) }
                    else dispatchTbr(owner, count, head.first())
                }
            }
        }
    }

    /** Polls bolus status until the pump reports an immediate or extended bolus running. */
    private fun awaitBolus(owner: BluetoothGatt, done: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 90_000L
        note("WAITING_FOR_BOLUS")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ finishWith("ERROR:bench timeout") }, BENCH_TIMEOUT_MS)
        fun poll() {
            observeStatus(owner, "WAIT", quiet = true) { bolus ->
                when {
                    bolus.bolusStatusCode != 0 || bolus.extendedStatusCode != 0 -> done()
                    SystemClock.elapsedRealtime() > deadline -> finishWith("ERROR:no bolus started within 90 s")
                    else -> handler.post { guard { poll() } }
                }
            }
        }
        poll()
    }

    private fun dispatchTbr(owner: BluetoothGatt, baselineCount: Int, baselineHead: YpsoHistoryEntry) {
        val characteristic = find(owner, YpsoWritePolicy.TBR_START_STOP_UUID) ?: error("TBR characteristic missing")
        require(characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) { "TBR characteristic is not writable" }
        currentWriteCharacteristic = characteristic
        val writeId = "tbr-bench-${UUID.randomUUID()}"
        val payload = YpsoTbrWriteCoordinator.payload(percent, duration)
        val operationOwner = YpsoTbrWriteCoordinator.Owner(owner, connectionId, checkNotNull(token))
        note("TBR_DISPATCH:percent=$percent;duration=$duration;payload=${payload.hex()};wall=${System.currentTimeMillis()}")
        var observed = false
        val started = coordinator.write(
            writeId, operationOwner, percent, duration, null, 8_000,
            beforeDispatch = { note("TBR_RESERVED:counter=${it.counter}") },
            dispatch = { frame -> write(owner, characteristic, frame) },
        ) { outcome ->
            note(describe(outcome))
            when (outcome) {
                is YpsoWriteOutcome.NotSent, is YpsoWriteOutcome.ProvenRejected -> finishWith("DONE:not-applied")
                is YpsoWriteOutcome.Verified -> Unit
                is YpsoWriteOutcome.AcceptedUnverified, is YpsoWriteOutcome.PossiblyApplied -> {
                    if (observed) return@write
                    observed = true
                    handler.post { guard { postWrite(owner, writeId, operationOwner, baselineCount, baselineHead) } }
                }
            }
        }
        if (!started) note("TBR_NOT_STARTED")
    }

    private fun postWrite(
        owner: BluetoothGatt,
        writeId: String,
        operationOwner: YpsoTbrWriteCoordinator.Owner,
        baselineCount: Int,
        baselineHead: YpsoHistoryEntry,
    ) {
        val delays = (intent.getStringExtra("observe_delays_ms") ?: "0,2000,6000")
            .split(',').map { it.trim().toLong() }
        val sent = SystemClock.elapsedRealtime()
        var last: SystemStatus? = null
        var lastBody: ByteArray? = null
        fun step(index: Int) {
            if (index >= delays.size) {
                val status = checkNotNull(last)
                val effect = if (duration == 0) status.percent == 100L && status.remaining == 0L
                else status.percent == percent.toLong() && status.remaining in (duration - 2L)..duration.toLong()
                val detail = "bench post-write status percent=${status.percent} remaining=${status.remaining} mode=${status.mode}"
                val reconciled = if (effect) coordinator.reconcileAccepted(writeId, operationOwner, hash(checkNotNull(lastBody)), detail)
                else coordinator.recordUnresolved(writeId, operationOwner, hash(checkNotNull(lastBody)), detail)
                note("RECONCILE:effect_matches_request=$effect;reconciled=$reconciled")
                if (!reconciled) {
                    // Same retirement a reconnect performs: keep the counter as high-water, outcome unknown.
                    coordinator.ownerDisconnected(owner, "bench retires unresolved TBR write before history scan")
                    session.recoverInterruptedWrite(checkNotNull(token))
                    note("RETIRED_UNRESOLVED_WRITE")
                }
                readEventCount(owner) { countAfter ->
                    note("HISTORY_COUNT:before=$baselineCount;after=$countAfter")
                    readRows(owner, countAfter, 16, stopAt = baselineHead.sequence) { finishWith("DONE:write-tbr") }
                }
                return
            }
            val wait = (sent + delays[index] - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            handler.postDelayed({
                guard {
                    readStatus(owner) { status, body ->
                        last = status
                        lastBody = body
                        note("POST+${SystemClock.elapsedRealtime() - sent}ms:$status")
                        if (intent.getBooleanExtra("wait_for_bolus", false)) observeStatus(owner, "POST_BOLUS_CHECK") { step(index + 1) }
                        else step(index + 1)
                    }
                }
            }, wait)
        }
        step(0)
    }

    private fun observeStatus(owner: BluetoothGatt, label: String, quiet: Boolean = false, done: (BolusCommand) -> Unit) {
        if (quiet) {
            // Bolus status only, so polling reacts to a pump-menu bolus within a few hundred ms.
            readEncrypted(owner, BOLUS_STATUS_UUID) { bolusBody ->
                val payload = YpsoCrc.validatedPayload(bolusBody) ?: error("bolus status CRC invalid")
                val bolus = BolusCommand(0.0).apply { decode(payload) }
                if (bolus.bolusStatusCode != 0 || bolus.extendedStatusCode != 0) {
                    note("${label}_BOLUS:fast_status=${bolus.bolusStatusCode};fast_total=${bolus.totalProgrammedUnits};fast_injected=${bolus.deliveredUnits};slow_status=${bolus.extendedStatusCode};slow_total=${bolus.extendedTotalUnits}")
                }
                done(bolus)
            }
            return
        }
        readStatus(owner) { status, _ ->
            note("$label:$status")
            readEncrypted(owner, BOLUS_STATUS_UUID) { bolusBody ->
                val payload = YpsoCrc.validatedPayload(bolusBody) ?: error("bolus status CRC invalid")
                val bolus = BolusCommand(0.0).apply { decode(payload) }
                note(
                    "${label}_BOLUS:valid=${bolus.success};fast_status=${bolus.bolusStatusCode};fast_total=${bolus.totalProgrammedUnits};" +
                        "fast_injected=${bolus.deliveredUnits};slow_status=${bolus.extendedStatusCode};slow_total=${bolus.extendedTotalUnits}",
                )
                done(bolus)
            }
        }
    }

    private fun readStatus(owner: BluetoothGatt, done: (SystemStatus, ByteArray) -> Unit) =
        readEncrypted(owner, STATUS_UUID) { body ->
            val payload = YpsoCrc.validatedPayload(body) ?: error("system status CRC invalid")
            require(payload.size == 18) { "system status size ${payload.size}" }
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            fun u32(offset: Int) = buffer.getInt(offset).toLong() and 0xFFFFFFFFL
            done(
                SystemStatus(
                    mode = payload[0].toInt() and 0xFF,
                    reservoir = u32(1),
                    bars = payload[5].toInt() and 0xFF,
                    basal = u32(6),
                    percent = u32(10),
                    remaining = u32(14),
                ),
                body,
            )
        }

    /** Reads from index 0 up to [limit] rows, or until [stopAt] sequence is reached (inclusive). */
    private fun readRows(owner: BluetoothGatt, count: Int, limit: Int, stopAt: Long?, done: (List<YpsoHistoryEntry>) -> Unit) {
        val rows = mutableListOf<YpsoHistoryEntry>()
        fun next(index: Int) {
            if (index >= minOf(count, limit)) {
                if (stopAt != null) note("HISTORY_BASELINE_NOT_REACHED:scanned=${rows.size}")
                return done(rows)
            }
            selectEvent(owner, index) { row ->
                rows += row
                val kind = YpsoHistoryClassifier.classify(row).kind
                note("ROW[$index]:seq=${row.sequence};type=${row.eventType};v=${row.value1}/${row.value2}/${row.value3};t=${row.factorySeconds};kind=$kind")
                if (stopAt != null && row.sequence == stopAt) done(rows) else next(index + 1)
            }
        }
        next(0)
    }

    private fun readEventCount(owner: BluetoothGatt, done: (Int) -> Unit) = readEncrypted(owner, EVENT_COUNT_UUID) { body ->
        val count = YpsoGlb.decodeExact(body) ?: error("event count is not exact GLB")
        done(count)
    }

    private fun selectEvent(owner: BluetoothGatt, index: Int, done: (YpsoHistoryEntry) -> Unit) {
        val indexCharacteristic = find(owner, YpsoWritePolicy.EVENT_INDEX_UUID) ?: error("event index missing")
        fun readSelected() = readEncrypted(owner, EVENT_VALUE_UUID) { body ->
            val row = YpsoHistoryEntry.decodeWire(body) ?: error("history row invalid")
            require(row.index == index) { "history embedded index mismatch" }
            done(row)
        }
        readEncrypted(owner, YpsoWritePolicy.EVENT_INDEX_UUID) { beforeBody ->
            val before = YpsoGlb.decodeExact(beforeBody) ?: error("pre-selector identity is not exact GLB")
            if (before == index) return@readEncrypted readSelected()
            currentWriteCharacteristic = indexCharacteristic
            val selectId = "tbr-history-$connectionId-$index-${UUID.randomUUID()}"
            val historyOwner = YpsoHistorySelectorCoordinator.Owner(owner, connectionId, checkNotNull(token))
            val started = historyCoordinator.select(
                selectId, historyOwner, index, null, 8_000,
                dispatch = { frame -> write(owner, indexCharacteristic, frame) },
            ) { outcome ->
                if (outcome is YpsoWriteOutcome.Verified) return@select
                if (outcome !is YpsoWriteOutcome.AcceptedUnverified) {
                    handler.post { finishWith("ERROR:history selector ${describe(outcome)}") }
                    return@select
                }
                handler.post {
                    guard {
                        readEncrypted(owner, YpsoWritePolicy.EVENT_INDEX_UUID) { identityBody ->
                            require(YpsoGlb.decodeExact(identityBody) == index) { "event selector identity mismatch" }
                            require(
                                historyCoordinator.reconcileAccepted(
                                    selectId, historyOwner, hash(identityBody),
                                    "same-link changed exact-GLB selector identity matched event index $index",
                                ),
                            ) { "event selector reconciliation failed" }
                            readSelected()
                        }
                    }
                }
            }
            if (!started) finishWith("ERROR:history selector not started")
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
        val total = YpsoFraming.validateFrame(value, read.frames.size + 1, read.total)
            ?: error("invalid read frame ${characteristic.uuid.toString().substring(24)} #${read.frames.size + 1}: ${value.hex()}")
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
        read.done(body)
    }

    private fun describe(outcome: YpsoWriteOutcome): String = when (outcome) {
        is YpsoWriteOutcome.NotSent -> "OUTCOME:NotSent;layer=${outcome.failure.layer};detail=${outcome.failure.detail}"
        is YpsoWriteOutcome.ProvenRejected -> "OUTCOME:ProvenRejected;layer=${outcome.failure.layer};code=${outcome.failure.code};detail=${outcome.failure.detail}"
        is YpsoWriteOutcome.PossiblyApplied -> "OUTCOME:PossiblyApplied;layer=${outcome.failure.layer};code=${outcome.failure.code};frame=${outcome.failure.frame};detail=${outcome.failure.detail}"
        is YpsoWriteOutcome.AcceptedUnverified -> "OUTCOME:AcceptedUnverified;counter=${outcome.counter}"
        is YpsoWriteOutcome.Verified -> "OUTCOME:Verified;${outcome.evidence}"
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

    private fun note(value: String) {
        Log.i(TAG, value)
        lines += value
    }

    @SuppressLint("MissingPermission")
    private fun finishWith(value: String) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        note(value)
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        sharedKey?.fill(0)
        gatt = null
        val text = lines.joinToString("\n") + "\n"
        FileOutputStream(File(filesDir, "tbr-result.txt")).use { it.write(text.toByteArray()); it.fd.sync() }
        FileOutputStream(File(filesDir, "tbr-evidence.log"), true).use { it.write((text + "---\n").toByteArray()); it.fd.sync() }
        setResult(RESULT_OK, android.content.Intent().putExtra("result", value))
        finish()
    }

    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun intExtra(name: String, default: Int): Int =
        when (@Suppress("DEPRECATION") val value = intent.extras?.get(name)) {
            null -> default
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: error("invalid integer extra: $name")
            else -> error("invalid integer extra type: $name")
        }

    private data class SystemStatus(val mode: Int, val reservoir: Long, val bars: Int, val basal: Long, val percent: Long, val remaining: Long) {
        override fun toString() = "mode=$mode;basal_cU=$basal;percent=$percent;remaining=$remaining;reservoir_cU=$reservoir;bars=$bars"
    }

    private data class PendingRead(
        val owner: BluetoothGatt,
        var expected: BluetoothGattCharacteristic,
        val done: (ByteArray) -> Unit,
        val frames: MutableList<ByteArray> = mutableListOf(),
        var total: Int = 0,
    )

    companion object {
        private const val TAG = "YpsoTbrBench"
        private const val BENCH_TIMEOUT_MS = 120_000L
        private val BOLUS_STATUS_UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee28b7bc5")
        private val STATUS_UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee48b7bc5")
        private val EVENT_VALUE_UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecd3b7bc5")
        private val EVENT_COUNT_UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecb3b7bc5")
        private val EXTREAD_UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff000000ff")
    }
}
