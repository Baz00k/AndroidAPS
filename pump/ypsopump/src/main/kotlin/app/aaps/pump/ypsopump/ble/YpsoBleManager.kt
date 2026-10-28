package app.aaps.pump.ypsopump.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.ypsopump.bolus.YpsoBolusBlock
import app.aaps.pump.ypsopump.bolus.YpsoValidatedBolusRequest
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_BOLUS_STATUS
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_CONTROL_VERSION
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_EVENT_COUNT
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_EVENT_VALUE
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_EXTREAD
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_SETTING_VALUE
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_STATUS
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_SYSTEM_DATE
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_SYSTEM_TIME
import app.aaps.pump.ypsopump.comm.YpsoBolusNotification
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.comm.commands.StatusCommand
import app.aaps.pump.ypsopump.comm.commands.TbrCommand
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoBasalSchedule
import app.aaps.pump.ypsopump.data.YpsoFirmwareVersion
import app.aaps.pump.ypsopump.data.YpsoProfileReadback
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.history.YpsoHistoryCursor
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoHistorySnapshot
import app.aaps.pump.ypsopump.history.YpsoPumpLocalTime
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.tbr.YpsoTbrObservation
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pump operations over [YpsoPumpLink]: authenticated status, profile/history selector acquisition and
 * therapy writes (bolus, extended bolus and temporary basal). An unknown write floor is reconciled from
 * zero through pump-confirmed counter errors. Pump configuration is never written. Each operation runs
 * on the [YpsoConnectionEpoch] it started on; the link fails it if that connection is replaced.
 */
@Singleton
class YpsoBleManager @Inject internal constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val sessionCrypto: SessionCrypto,
    private val pumpState: YpsoPumpState,
    private val provisioning: YpsoProvisioningService,
    private val link: YpsoPumpLink,
) {
    internal fun noBackupDirectory(): java.io.File = context.noBackupFilesDir

    /** Phone wall and monotonic clocks, replaceable in tests. */
    internal var wallClock: () -> Long = System::currentTimeMillis
    internal var elapsedClock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    val connectionState: ConnectionState get() = link.connectionState
    val isConnected: Boolean get() = link.isConnected

    /** The authenticated connection, its session generation and pump reboot counter; null when there is none. */
    internal fun epoch(): YpsoConnectionEpoch? = link.epoch()

    /**
     * Receives each decoded bolus state change the pump pushes on CONTROL_NOTIFY, with the key of the
     * connection it arrived on (see [YpsoConnectionEpoch.key]) and its [System.nanoTime] receipt time.
     */
    @Volatile
    var onBolusNotification: ((YpsoBolusNotification, String?, Long) -> Unit)? = null

    /**
     * A verified session with an authenticated read floor can acquire selectors. An unknown write
     * floor is normal: the first write reconciles it from zero through the pump-confirmed search.
     */
    val canReadProfile: Boolean
        get() = link.session?.snapshot()?.let { it.reboot != null && it.read != null } == true
    val canReadHistory: Boolean
        get() = canReadProfile && !link.isActive(YpsoLinkOperation.PROFILE) && !link.isActive(YpsoLinkOperation.HISTORY)

    internal fun writeReadinessFailure(epoch: YpsoConnectionEpoch? = link.epoch()): String? {
        val record = link.session?.snapshot()
        return when {
            !isConnected -> "pump connection state is $connectionState"
            epoch == null -> "authenticated connection is unavailable"
            !pumpState.hasCompatibleStatusProtocol() ->
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
            else -> lanes.firstOrNull { it.hasUnresolvedWriteOn(epoch.connection) }?.category?.busy
        }
    }

    internal fun readinessStatus(): String {
        val epoch = link.epoch()
        val record = link.session?.snapshot()
        fun capability(uuid: UUID, property: Int): Boolean = epoch?.connection?.properties(uuid)?.let { it and property != 0 } == true
        return "connection=$connectionState,epoch=${epoch != null}," +
            "protocol=${pumpState.hasCompatibleStatusProtocol()},setting_selector_read=" +
            capability(YpsoWritePolicy.SETTING_ID_UUID, BluetoothGattCharacteristic.PROPERTY_READ) +
            ",setting_selector_write=" + capability(YpsoWritePolicy.SETTING_ID_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE) +
            ",history_selector_read=" + capability(YpsoWritePolicy.EVENT_INDEX_UUID, BluetoothGattCharacteristic.PROPERTY_READ) +
            ",history_selector_write=" + capability(YpsoWritePolicy.EVENT_INDEX_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE) +
            ",bolus_write=" + capability(YpsoWritePolicy.BOLUS_START_STOP_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE) +
            ",reboot=${record?.reboot},read=${record?.read},write=${record?.write}," +
            "bootstrap=${record?.writeBootstrapState},recovery_exponent=${record?.counterRecoveryExponent}," +
            "reservation=${record?.reservation?.phase ?: "none"}," +
            "failure=${writeReadinessFailure(epoch) ?: "none"}"
    }

    val writeCounter: Long get() = link.session?.snapshot()?.write ?: 0L

    companion object {
        private const val EXPECTED_PROFILE_SELECTOR_FRAME_COUNT = 4
        private val SUPPORTED_CONTROL_SERVICE_VERSION = "1.3\u0000".toByteArray(Charsets.US_ASCII)
        /** Wall time may run this far from monotonic time over one pump clock read without a phone clock step. */
        private const val PHONE_CLOCK_STEP_MS = 1_000L
    }

    // ---- configuration and connection ----

    fun configureInstalledSession(): Boolean {
        val installed = provisioning.connectionSession() ?: return false
        link.configure(installed) {
            val cached = profileStore.load(installed.generation)
            pumpState.invalidateProfileEvidence()
            if (cached != null) pumpState.publishProfileEvidence(cached)
        }
        return installed.mac.isNotBlank()
    }

    fun installedPumpMac(): String = provisioning.connectionSession()?.mac.orEmpty()

    /** Connect the exact immutable credential snapshot installed by [configureInstalledSession]. */
    fun connectConfiguredSession() = link.connectConfigured()

    fun disconnect(preserveStatus: Boolean = false) = link.disconnect(preserveStatus)

    private val profileStore by lazy {
        app.aaps.pump.ypsopump.data.YpsoProfileConfigurationStore(context.getSharedPreferences("ypso_profile_configuration", Context.MODE_PRIVATE))
    }
    internal var persistProfile: (YpsoProfileReadback.VerifiedReadback) -> Unit = { profileStore.save(it) }

    // ---- write lanes ----

    private fun continueOnLink(task: () -> Unit) = link.scheduler.execute(task)
    private fun firmware(): String? = pumpState.masterVersion.takeIf(String::isNotBlank)

    private val profileLane = YpsoWriteLane(YpsoWriteLane.Category.PROFILE_SELECTOR, link, sessionCrypto, aapsLogger)
    private val historyLane = YpsoWriteLane(YpsoWriteLane.Category.HISTORY_SELECTOR, link, sessionCrypto, aapsLogger)
    private val bolusLane = YpsoWriteLane(YpsoWriteLane.Category.BOLUS, link, sessionCrypto, aapsLogger)
    private val tbrLane = YpsoWriteLane(YpsoWriteLane.Category.TBR, link, sessionCrypto, aapsLogger)
    private val lanes = listOf(profileLane, historyLane, bolusLane, tbrLane)

    private val linkListener = object : YpsoPumpLink.Listener {
        override fun connectionClosed(connection: YpsoGattConnection, detail: String) {
            lanes.forEach { it.ownerDisconnected(connection, detail) }
        }

        override fun authenticated(epoch: YpsoConnectionEpoch) {
            retireAbandonedSelector(epoch)
            aapsLogger.info(
                LTag.PUMP,
                writeReadinessFailure(epoch)?.let { "YpsoPump authenticated; writes are not ready: $it" }
                    ?: "YpsoPump authenticated; durable write prerequisites are currently satisfied",
            )
        }

        override fun operationWrite(connection: YpsoGattConnection, uuid: UUID, status: Int) {
            lanes.firstOrNull { it.category.characteristic == uuid }?.onCharacteristicWrite(connection, status)
        }

        override fun notification(epochKey: String?, uuid: UUID, value: ByteArray, receivedAtNanos: Long) {
            if (uuid != YpsoWritePolicy.CONTROL_NOTIFY_UUID) return
            val notification = YpsoBolusNotification.decode(value) ?: return
            aapsLogger.debug(
                LTag.PUMP,
                "YpsoPump bolus notification fast=${notification.fastStatusCode}/${notification.fastSequence} " +
                    "slow=${notification.slowStatusCode}/${notification.slowSequence}",
            )
            onBolusNotification?.invoke(notification, epochKey, receivedAtNanos)
        }
    }

    /**
     * A history or setting selector write interrupted by a lost connection stays reserved in the
     * session journal, and every therapy write refuses to start while it is. The read that owned it is
     * gone and a selector move has no therapy effect, so it is retired here, on the new authenticated
     * session, rather than left to block the next command until some later read clears it. Runs with
     * the link lock held; no operation can use the new connection before it returns.
     */
    private fun retireAbandonedSelector(epoch: YpsoConnectionEpoch) {
        val owner = link.session ?: return
        val reservation = owner.snapshot()?.reservation ?: return
        if (reservation.phase == PumpSession.Phase.VERIFIED || !owner.isAbandonableSelector(reservation)) return
        // Only a selector no transport still holds: the old connection's read was released on disconnect.
        if (!historyLane.isIdle() || link.isActive(YpsoLinkOperation.HISTORY)) return
        if (!profileLane.isIdle() || link.isActive(YpsoLinkOperation.PROFILE)) return
        runCatching { owner.retireAbandonedSelector(epoch.token, reservation.id) }
            .onSuccess { if (it) aapsLogger.info(LTag.PUMP, "YpsoPump retired ${reservation.purpose} write ${reservation.id} left by a lost connection") }
            .onFailure { aapsLogger.warn(LTag.PUMP, "YpsoPump could not retire abandoned selector write: ${it.message}") }
    }

    // ---- status ----

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

    /** Read SYSTEM_STATUS over the already-open connection and update [YpsoPumpState]. No disconnect. */
    fun readStatus(onDone: (Boolean) -> Unit = {}): StatusReadAttempt {
        val attempt = StatusReadAttempt()
        val lease = link.acquire(YpsoLinkOperation.STATUS) ?: run {
            attempt.tryComplete()
            onDone(false)
            return attempt
        }
        // CommandReadStatus infers success from lastDataTime, so invalidate the previous sample before
        // every attempt. A failed current read must never inherit a recent successful timestamp or values.
        pumpState.invalidateStatus(preserveDisplay = true)
        val epoch = link.epoch()
        attempt.onCancel = {
            link.release(lease)
            epoch?.let { link.fail(it, "status read cancelled", cause = PumpSession.AvailabilityCause.TRANSPORT) }
            runCatching { onDone(false) }
        }
        if (epoch == null) {
            aapsLogger.warn(LTag.PUMP, "YpsoPump readStatus: not connected")
            link.release(lease)
            if (attempt.tryComplete()) onDone(false)
            return attempt
        }
        readIdentity(epoch) {
            if (!attempt.isActive) return@readIdentity
            readStatusInternal(epoch, lease, attempt, onDone)
        }
        return attempt
    }

    private fun readIdentity(epoch: YpsoConnectionEpoch, done: () -> Unit) {
        // Identity and protocol fields are shared by every connection. Each write is made only while
        // [epoch] is current, so a result accepted before a teardown and delivered after it cannot
        // overwrite a successor's evidence. A replaced epoch ends the read; the caller's own read fails.
        val reset = link.ifCurrent(epoch) {
            pumpState.firmwareVersion = ""
            pumpState.masterVersion = ""
            pumpState.supervisorVersion = ""
            pumpState.baseServiceVersion = ""
            pumpState.settingsServiceVersion = ""
            pumpState.historyServiceVersion = ""
            pumpState.controlServiceVersion = ""
            pumpState.observedIdentitySerial = link.bondedIdentitySerial.orEmpty()
        }
        if (reset == null) return done()
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
            if (index == candidates.size) { finished = true; done(); return }
            val (name, address) = candidates[index]
            val uuid = UUID.fromString(address)
            if (epoch.connection.properties(uuid) == null) {
                aapsLogger.info(LTag.PUMP, "YpsoPump identity $name absent")
                step(index + 1)
                return
            }
            link.read(epoch, uuid) { bytes, status ->
                if (finished) return@read
                aapsLogger.info(LTag.PUMP, "YpsoPump identity $name status=$status")
                val current = link.ifCurrent(epoch) {
                    if (status == BluetoothGatt.GATT_SUCCESS && bytes != null) {
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
                }
                if (current == null) {
                    finished = true
                    done()
                    return@read
                }
                if (status == BluetoothGatt.GATT_SUCCESS) step(index + 1) else {
                    finished = true
                    done()
                }
            }
        }
        step(0)
    }

    private fun readStatusInternal(epoch: YpsoConnectionEpoch, lease: YpsoLinkLease, attempt: StatusReadAttempt, onDone: (Boolean) -> Unit) {
        link.readFrames(
            epoch,
            CHAR_STATUS,
            reportToAttempt = true,
            onFailure = {
                link.release(lease)
                if (attempt.tryComplete()) onDone(false)
            },
        ) { frame ->
            var decodeFailure: Throwable? = null
            var publicationFailure: Throwable? = null
            var completionClaimed = false
            var stillCurrent = false
            val decoded = link.ifCurrent(epoch) {
                stillCurrent = true
                if (!attempt.isActive) return@ifCurrent null
                val decoded = runCatching {
                    val body = link.decrypt(epoch, frame)
                    val payload = YpsoCrc.validatedPayload(body) ?: throw SecurityException("invalid status CRC")
                    val status = StatusCommand().apply { decode(payload) }
                    if (!status.success) throw IllegalArgumentException("status decode failed (${payload.size}B)")
                    if (!pumpState.hasCompatibleStatusProtocol())
                        throw IllegalArgumentException("Unknown or unsupported pump firmware/control protocol")
                    status
                }.onFailure { decodeFailure = it }.getOrNull()
                completionClaimed = attempt.tryComplete()
                decoded?.takeIf { completionClaimed }
            }
            if (!stillCurrent) completionClaimed = attempt.tryComplete()
            val success = decoded?.let { status ->
                runCatching {
                    check(link.isCurrent(epoch)) { "Stale status callback" }
                    val promotedCandidate = provisioning.markVerified(
                        epoch.generation, epoch.attemptId,
                        pumpState.observedIdentitySerial.takeIf(String::isNotBlank)
                    )
                    publishDecodedStatus(status)
                    aapsLogger.info(LTag.PUMP, "YpsoPump encrypted status accepted")
                    if (promotedCandidate) disconnect(preserveStatus = true)
                    true
                }.onFailure { publicationFailure = it }.isSuccess
            } ?: false
            if (!completionClaimed) return@readFrames
            link.release(lease)
            // Identity failures record their own specific cause inside markVerified; only a decode
            // failure is an unattributed encrypted-status failure. Key rejection is terminal; other
            // decode failures keep the candidate with bounded retry.
            decodeFailure?.let {
                if (it is SessionCrypto.AuthenticationFailedException) {
                    provisioning.failCandidateOrRecord(
                        epoch.generation, epoch.attemptId,
                        setOf(PumpSession.AvailabilityCause.KEY_REJECTED),
                        operation = "encrypted-status", firmware = pumpState.masterVersion.takeIf(String::isNotBlank)
                    )
                } else {
                    provisioning.recordCandidateOrUnavailable(
                        epoch.generation, epoch.attemptId,
                        setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE),
                        operation = "encrypted-status", firmware = pumpState.masterVersion.takeIf(String::isNotBlank)
                    )
                }
            }
            val failure = decodeFailure ?: publicationFailure
            failure?.let {
                aapsLogger.error(LTag.PUMP, "YpsoPump status rejected: ${it.message}")
                link.fail(epoch, "status rejected: ${it.message}")
            }
            // Stay connected — the AAPS command queue disconnects when idle.
            runCatching { onDone(success) }
                .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump status callback failed: ${it.message}") }
        }
    }

    private fun publishDecodedStatus(status: StatusCommand) {
        pumpState.publishStatus(
            reservoirUnits = status.reservoirUnits,
            batteryPercent = status.batteryPercent,
            isSuspended = status.isSuspended,
            activeTbrPercent = status.activeTbrPercent,
            timestamp = System.currentTimeMillis(),
            batteryBars = status.batteryBars,
            activeBasalRate = status.basalRate,
            activeTbrRemainingMinutes = status.tbrRemainingMinutes,
        )
    }

    // ---- profile configuration ----

    /**
     * Acquire active-before, all A/B rows, active-after and pump clock on one authenticated GATT.
     * Every selector is independently journaled and semantically reconciled only after the same-link
     * selector characteristic reads back the requested setting ID. The value is then read separately.
     * A failure publishes nothing and never retries an uncertain write.
     */
    fun readProfile(onDone: (Boolean) -> Unit = {}): ProfileReadAttempt = readProfileConfiguration(false, { false }, onDone)

    fun readProfileConfiguration(activeOnly: Boolean, shouldYield: () -> Boolean, onDone: (Boolean) -> Unit): ProfileReadAttempt {
        val attempt = ProfileReadAttempt()
        val lease = link.acquire(YpsoLinkOperation.PROFILE) ?: run {
            attempt.tryComplete()
            onDone(false)
            return attempt
        }
        val epoch = link.epoch()
        val connectionId = UUID.randomUUID().toString()
        attempt.onCancel = {
            epoch?.let { link.ifCurrent(it) { link.disconnect() } }
            runCatching { onDone(false) }
        }
        if (epoch == null || !pumpState.hasCompatibleStatusProtocol()) {
            link.release(lease)
            if (attempt.tryComplete()) onDone(false)
            return attempt
        }
        val settingIdProperties = epoch.connection.properties(YpsoWritePolicy.SETTING_ID_UUID)
        if (settingIdProperties == null ||
            settingIdProperties and BluetoothGattCharacteristic.PROPERTY_READ == 0 ||
            listOf(CHAR_SETTING_VALUE, CHAR_SYSTEM_DATE, CHAR_SYSTEM_TIME, CHAR_EXTREAD).any { epoch.connection.properties(it) == null }
        ) {
            aapsLogger.warn(LTag.PUMP, "YpsoPump profile read blocked: selector identity read-back is unavailable")
            link.release(lease)
            if (attempt.tryComplete()) onDone(false)
            return attempt
        }
        val reboot = epoch.reboot
        if (reboot == null) {
            aapsLogger.warn(LTag.PUMP, "YpsoPump profile read blocked: durable pump session floor is unavailable")
            link.release(lease)
            if (attempt.tryComplete()) onDone(false)
            return attempt
        }
        val owner = YpsoWriteLane.Owner(epoch, lease, connectionId)
        val startedElapsed = android.os.SystemClock.elapsedRealtime()
        var readback: YpsoProfileReadback? = null
        aapsLogger.debug(LTag.PUMP, "YpsoPump profile acquisition started: evidenceRecords=${link.session?.snapshot()?.writeEvidence?.size}")
        var selectedSettingId: Int? = null
        var eventCountBefore: Int? = null

        fun failProfile(detail: String) {
            if (!attempt.tryComplete()) return
            readback?.invalidate()
            aapsLogger.error(LTag.PUMP, "YpsoPump profile read failed: $detail")
            link.release(lease)
            onDone(false)
        }
        /** Publishes [evidence] only while this read still owns its connection. */
        fun publish(evidence: YpsoProfileReadback.VerifiedReadback, stage: String): Boolean? {
            var claimed = false
            var saved = false
            link.ifCurrent(epoch) {
                claimed = attempt.tryComplete()
                if (claimed) {
                    saved = runCatching { persistProfile(evidence) }.isSuccess
                    if (saved) pumpState.publishProfileEvidence(evidence)
                    link.release(lease)
                }
            } ?: run {
                failProfile("profile ownership changed before $stage")
                return null
            }
            if (!claimed) return null
            if (!saved) onDone(false)
            return saved
        }
        fun readEncrypted(uuid: UUID, done: (ByteArray) -> Unit) {
            if (!attempt.isActive) return failProfile("profile read ended before $uuid")
            link.readFrames(epoch, uuid, onFailure = { failProfile("read $uuid failed") }) { frame ->
                if (!attempt.isActive) return@readFrames failProfile("profile read ended")
                val body = runCatching { link.decrypt(epoch, frame) }.getOrElse { return@readFrames failProfile(it.message ?: "decrypt failed") }
                done(body)
            }
        }
        fun readExactGlb(uuid: UUID, label: String, done: (Int, ByteArray) -> Unit) = readEncrypted(uuid) { body ->
            val value = YpsoGlb.decodeExact(body)
                ?: return@readEncrypted failProfile("$label is not exact GLB")
            done(value, body)
        }
        fun finish(date: ByteArray, time: ByteArray, activeAfter: ByteArray, eventCountAfter: Int) {
            if (eventCountAfter != eventCountBefore) return failProfile("event count changed during profile acquisition")
            val local = YpsoProfileReadback.decodeClock(date, time) ?: return failProfile("pump clock is malformed")
            val evidence = readback?.finish(
                epoch.generation,
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
            if (publish(evidence, "publication") != true) return
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
            if (!attempt.isActive || !link.isCurrent(epoch)) return failProfile("profile ownership changed before selector $settingId")
            if (shouldYield()) return failProfile("configuration read yielded to queued command")
            if (selectedSettingId == settingId) return failProfile("selector $settingId lacks changed-identity evidence")
            val writeId = "profile-$connectionId-$settingId-${UUID.randomUUID()}"
            val started = profileLane.write(owner, writeId, YpsoGlb.encode(settingId), firmware()) { outcome ->
                val canReconcile = outcome is YpsoWriteOutcome.AcceptedUnverified ||
                    outcome is YpsoWriteOutcome.PossiblyApplied &&
                    outcome.failure.layer == YpsoWriteFailure.Layer.GATT_CALLBACK &&
                    outcome.failure.frame == EXPECTED_PROFILE_SELECTOR_FRAME_COUNT
                if (!canReconcile) return@write failProfile("selector $settingId was not semantically observable: $outcome")
                readExactGlb(YpsoWritePolicy.SETTING_ID_UUID, "selector identity") { selectedId, selectedIdBody ->
                    if (selectedId != settingId) return@readExactGlb failProfile("selector identity mismatch: requested $settingId, read $selectedId")
                    val detail = "same-link exact-GLB selector identity read-back matched setting $settingId"
                    val evidenceHash = YpsoWriteAccounting.sha256(
                        "${epoch.generation}|$reboot|$connectionId|$settingId|${YpsoWriteAccounting.sha256(selectedIdBody)}".toByteArray(),
                    )
                    val reconciled = runCatching {
                        profileLane.reconcile(owner, writeId, YpsoSemanticEvidence.ACCEPTED, evidenceHash, detail)
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
            val firstRow = 14
            val lastRow = 61
            if (settingId > lastRow) {
                select(1) { activeAfter -> readClock(activeAfter) }
                return
            }
            select(settingId) { body ->
                if (readback?.add(settingId, body) != true) failProfile("setting $settingId could not be added")
                else {
                    val rowsRead = settingId - firstRow + 1
                    if (rowsRead % 12 == 0) {
                        aapsLogger.debug(LTag.PUMP, "YpsoPump profile acquisition progress: rows=$rowsRead/${lastRow - firstRow + 1}, elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedElapsed}, evidenceRecords=${link.session?.snapshot()?.writeEvidence?.size}")
                    }
                    continueOnLink { readRows(settingId + 1) }
                }
            }
        }
        link.enableControlNotifications(epoch) { setup ->
            if (!setup) return@enableControlNotifications failProfile("Could not prepare the pump. Please try again.")
            continueOnLink {
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
                                    if (previous.generation != epoch.generation) return@readExactGlb failProfile("configuration belongs to another pump session")
                                    if (previous.zone != ZoneId.systemDefault()) return@readExactGlb failProfile("configuration belongs to another time zone")
                                    val updated = YpsoProfileReadback.VerifiedReadback(
                                        previous.generation, reboot, connectionId, program, previous.profileA, previous.profileB,
                                        previous.acquiredElapsedMs, previous.zone, eventCountAfter, Instant.now(),
                                        activeProgramObservedElapsedMs = android.os.SystemClock.elapsedRealtime(),
                                    )
                                    if (publish(updated, "active-program publication") == true) onDone(true)
                                }
                                return@select
                            }
                            readback = YpsoProfileReadback(epoch.generation, reboot, connectionId, startedElapsed, program)
                            continueOnLink { readRows(14) }
                        }
                        if (initialSettingId == 1) {
                            // Establish a changed selector identity before setting 1. The witness is not
                            // part of the coherent profile bracket and its value is deliberately discarded.
                            select(14) { continueOnLink { readActiveBefore() } }
                        } else {
                            readActiveBefore()
                        }
                    }
                }
            }
        }
        return attempt
    }

    // ---- history ----

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
        val lease = link.acquire(YpsoLinkOperation.HISTORY) ?: run {
            attempt.tryComplete()
            onResult(null)
            return attempt
        }
        val epoch = link.epoch()
        val connectionId = UUID.randomUUID().toString()
        // Prefixes are published by the row callback, never copied concurrently by the cancelling
        // thread. A late callback from this attempt must not erase a successor's checkpoint.
        attempt.onCancel = {
            epoch?.let(link::cancelCurrentOperation)
            link.release(lease)
            runCatching { onResult(null) }
        }
        if (epoch == null || !pumpState.hasCompatibleStatusProtocol()) {
            link.release(lease)
            if (attempt.tryComplete()) onResult(null)
            return attempt
        }
        val reboot = epoch.reboot
        val selector = epoch.connection.properties(YpsoWritePolicy.EVENT_INDEX_UUID)
        if (reboot == null ||
            selector == null || selector and BluetoothGattCharacteristic.PROPERTY_READ == 0 ||
            epoch.connection.properties(CHAR_EVENT_COUNT) == null || epoch.connection.properties(CHAR_EVENT_VALUE) == null
        ) {
            link.release(lease)
            if (attempt.tryComplete()) onResult(null)
            return attempt
        }
        val owner = YpsoWriteLane.Owner(epoch, lease, connectionId)
        var countBefore = -1
        var headBefore: YpsoHistoryEntry? = null
        val rows = mutableListOf<YpsoHistoryEntry>()
        /**
         * Positions in [rows] copied from an earlier scan's checkpoint. Only these can hold a stale
         * copy of a running event; every other row was read from the pump during this scan.
         */
        val reusedPositions = mutableSetOf<Int>()
        val cached = partialScan?.takeIf { it.generation == epoch.generation && it.reboot == reboot.toLong() }
        var overlapPending = cached != null
        var cursorMismatchReported = false
        /** Selector index proven by same-link read-back within this scan; null until first proven. */
        var verifiedSelectorIndex: Int? = null

        fun finish(value: YpsoHistorySnapshot?) {
            if (!attempt.tryComplete()) return
            // Only a stable completed scan supersedes its checkpoint. Movement at the final anchor
            // check must leave progress available for the next overlap scan.
            if (value != null && value.countBefore == value.countAfter &&
                value.headBefore?.sequence == value.headAfter?.sequence &&
                value.headBefore?.fingerprint() == value.headAfter?.fingerprint()
            ) partialScan = null
            link.release(lease)
            onResult(value)
        }
        fun yieldAtSafeBoundary(): Boolean {
            if (!attempt.isActive) return true
            if (!attempt.shouldYield) return false
            // Keep what was already read. Routine status polling interrupts long scans every few
            // minutes, and restarting at the head each time means the scan can never reach the cursor.
            if (!overlapPending) retainPartialScan(epoch.generation, reboot.toLong(), countBefore, headBefore, rows)
            aapsLogger.debug(
                LTag.PUMP,
                "YpsoPump history scan yielded: freshRows=${rows.size}, retainedRows=${partialScan?.rows?.size ?: 0}, " +
                    "count=$countBefore, headSeq=${headBefore?.sequence}, oldestScannedSeq=${rows.lastOrNull()?.sequence}, " +
                    "cursorSeq=${cursor?.identity?.sequence}, seekingOverlap=$overlapPending, " +
                    "cursorSequenceAt=${rows.indexOfFirst { it.sequence == cursor?.identity?.sequence }}, " +
                    "cursorExactAt=${rows.indexOfFirst { it.sequence == cursor?.identity?.sequence && it.fingerprint() == cursor.fingerprint }}, " +
                    "trackedTbrSeq=${cursor?.activeTbr?.identity?.sequence}, " +
                    "trackedTbrExactAt=${rows.indexOfFirst { it.sequence == cursor?.activeTbr?.identity?.sequence && it.fingerprint() == cursor.activeTbr.fingerprint }}",
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
            if (!attempt.isActive) return failHistory("history read ended before $uuid")
            link.readFrames(epoch, uuid, onFailure = { failHistory("read $uuid failed") }) { frames ->
                if (!attempt.isActive) return@readFrames failHistory("history read ended")
                val body = runCatching { link.decrypt(epoch, frames) }.getOrElse {
                    return@readFrames failHistory(it.message ?: "history decrypt failed")
                }
                done(body)
            }
        }
        fun readCount(done: (Int) -> Unit) = readEncrypted(CHAR_EVENT_COUNT) { body ->
            val value = YpsoGlb.decodeExact(body)
                ?: return@readEncrypted failHistory("event count is not exact GLB")
            if (value < 0) return@readEncrypted failHistory("event count is negative")
            done(value)
        }
        fun dispatchChangedSelection(index: Int, previousIndex: Int, done: (YpsoHistoryEntry) -> Unit) {
            if (!attempt.isActive || !link.isCurrent(epoch)) return failHistory("history ownership changed before selector $index")
            if (previousIndex == index) return failHistory("selector $index lacks changed-value acceptance evidence")
            val writeId = "history-$connectionId-$index-${UUID.randomUUID()}"
            val started = historyLane.write(owner, writeId, YpsoGlb.encode(index), firmware()) { outcome ->
                val canReconcile = outcome is YpsoWriteOutcome.AcceptedUnverified ||
                    outcome is YpsoWriteOutcome.PossiblyApplied &&
                    outcome.failure.layer == YpsoWriteFailure.Layer.GATT_CALLBACK
                if (!canReconcile) return@write failHistory("selector $index was not semantically observable: $outcome")
                readEncrypted(YpsoWritePolicy.EVENT_INDEX_UUID) { selectedBody ->
                    val selected = YpsoGlb.decodeExact(selectedBody)
                        ?: return@readEncrypted failHistory("selector identity is not exact GLB")
                    if (selected != index) {
                        verifiedSelectorIndex = null
                        return@readEncrypted failHistory("selector identity mismatch: requested $index, read $selected")
                    }
                    verifiedSelectorIndex = selected
                    val evidenceHash = YpsoWriteAccounting.sha256(
                        "${epoch.generation}|$reboot|$connectionId|$index|${YpsoWriteAccounting.sha256(selectedBody)}".toByteArray(),
                    )
                    val reconciled = runCatching {
                        historyLane.reconcile(
                            owner,
                            writeId,
                            YpsoSemanticEvidence.ACCEPTED,
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
                if (entry.index != index) {
                    aapsLogger.warn(LTag.PUMP, "YpsoPump history selected index $index returned row ${entry.index}; refreshing selector")
                    if (countBefore < 2) return@readEncrypted failHistory("stale selected row with no alternate history index")
                    // A read-only selector match does not guarantee the event-value buffer was
                    // refreshed. Force two changed-value writes, each with semantic read-back.
                    val alternate = if (index == 0) 1 else 0
                    dispatchChangedSelection(alternate, index) {
                        dispatchChangedSelection(index, alternate, done)
                    }
                    return@readEncrypted
                }
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
                val before = YpsoGlb.decodeExact(beforeBody)
                    ?: return@readEncrypted failHistory("pre-selector identity is not exact GLB")
                verifiedSelectorIndex = before
                if (before != index) {
                    dispatchChangedSelection(index, before, done)
                    return@readEncrypted
                }
                readSelected(index, done)
            }
        }
        /**
         * The pump clock offset from two plain reads (no selector write). A date change between the reads, a phone clock that stepped while they ran, or any read failure
         * leaves the offset unknown rather than failing the scan.
         */
        fun readPumpClock(done: (offset: Long?) -> Unit) {
            if (!attempt.isActive) return done(null)
            // A link without the clock characteristics would fail the scan's remaining reads.
            if (epoch.connection.properties(CHAR_SYSTEM_DATE) == null || epoch.connection.properties(CHAR_SYSTEM_TIME) == null) return done(null)
            fun plain(uuid: UUID, next: (ByteArray?) -> Unit) =
                link.readFrames(epoch, uuid, onFailure = { next(null) }) { frames ->
                    next(runCatching { link.decrypt(epoch, frames) }.getOrNull())
                }
            plain(CHAR_SYSTEM_DATE) { date ->
                val before = wallClock()
                val elapsedBefore = elapsedClock()
                plain(CHAR_SYSTEM_TIME) { time ->
                    val after = wallClock()
                    val elapsedAfter = elapsedClock()
                    val local = if (date != null && time != null) YpsoProfileReadback.decodeClock(date, time) else null
                    // The time is read after the date; near midnight the pair may straddle two days.
                    val nearMidnight = local != null && (local.toLocalTime().toSecondOfDay() < 10 || local.toLocalTime().toSecondOfDay() > 86_390)
                    // Wall time that moved unlike monotonic time means the phone clock was set meanwhile.
                    val stepped = kotlin.math.abs((after - before) - (elapsedAfter - elapsedBefore)) > PHONE_CLOCK_STEP_MS
                    done(local?.let { YpsoPumpLocalTime.pumpOffsetMs(it, (before + after) / 2) }?.takeIf { !nearMidnight && !stepped })
                }
            }
        }
        fun finishScan() {
            if (yieldAtSafeBoundary()) return
            // The pump clock is read before the final count and head check, inside the window that check
            // proves no row was written in: no clock change row follows the reading.
            readPumpClock { offset ->
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
                                pumpClockOffsetMs = offset,
                            ),
                        )
                    }
                }
            }
        }
        fun checkpoint() {
            if (!cursorMismatchReported && cursor != null) {
                rows.firstOrNull { it.sequence == cursor.identity.sequence && it.fingerprint() != cursor.fingerprint }?.let { row ->
                    cursorMismatchReported = true
                    val expected = cursor.fingerprint
                    aapsLogger.warn(
                        LTag.PUMP,
                        "YpsoPump history cursor mismatch: sequence=${row.sequence}, index=${row.index}, " +
                            "cursorReboot=${cursor.pumpReboot}, currentReboot=$reboot, " +
                            "storedTime=${expected.high ushr 24}, storedType=${(expected.high ushr 16) and 0xff}, " +
                            "storedValues=${expected.high and 0xffff}/${(expected.low ushr 48) and 0xffff}/${(expected.low ushr 32) and 0xffff}, " +
                            "pumpTime=${row.factorySeconds}, pumpType=${row.eventType}, pumpValues=${row.value1}/${row.value2}/${row.value3}",
                    )
                }
            }
            if (attempt.isActive && !overlapPending) {
                retainPartialScan(epoch.generation, reboot.toLong(), countBefore, headBefore, rows)
            }
        }
        fun hasRequiredRows(): Boolean = cursor != null &&
            rows.any { it.matchesCursor(cursor, reboot.toLong()) } &&
            (cursor.activeTbr == null || rows.any {
                it.sequence == cursor.activeTbr.identity.sequence && it.fingerprint() == cursor.activeTbr.fingerprint
            })

        // Cached running events can have been rewritten even if the ring's head did not change.
        // Refresh those rows before handing a snapshot to accounting. Rows read in this scan are
        // already current: re-selecting them only repeated a selector move, its read-backs, and the
        // session commits each one costs.
        fun refreshMutableRows(position: Int = 0) {
            if (yieldAtSafeBoundary()) return
            val next = (position until rows.size).firstOrNull { it in reusedPositions && rows[it].eventType in setOf(1, 9, 17, 19, 27) }
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
                            val firstReused = rows.size
                            rows += available.drop(1).map { it.copy(index = it.index + shift) }
                            // The boundary row was just re-read; the rows between came from the checkpoint.
                            reusedPositions += firstReused until rows.lastIndex
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
        link.enableControlNotifications(epoch) { setup ->
            if (!setup) return@enableControlNotifications failHistory("Could not prepare the pump. Please try again.")
            if (yieldAtSafeBoundary()) return@enableControlNotifications
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
        val lease = link.acquire(YpsoLinkOperation.HISTORY) ?: run {
            onResult(LowerBoundRecoveryResult.STOPPED)
            return
        }
        val epoch = link.epoch()
        val connectionId = UUID.randomUUID().toString()
        val record = link.session?.snapshot()
        val reboot = epoch?.reboot
        val selector = epoch?.connection?.properties(YpsoWritePolicy.EVENT_INDEX_UUID)
        if (epoch == null || reboot == null || record == null ||
            record.writeBootstrapState != PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND ||
            selector == null || selector and BluetoothGattCharacteristic.PROPERTY_READ == 0 ||
            epoch.connection.properties(CHAR_EVENT_COUNT) == null
        ) {
            link.release(lease)
            onResult(LowerBoundRecoveryResult.STOPPED)
            return
        }
        val owner = YpsoWriteLane.Owner(epoch, lease, connectionId, lowerBoundRecovery = true)
        var completed = false
        fun finish(result: LowerBoundRecoveryResult) {
            if (completed) return
            completed = true
            link.release(lease)
            onResult(result)
        }
        fun stop(detail: String) {
            aapsLogger.info(LTag.PUMP, "YpsoPump lower-bound selector recovery stopped: $detail")
            finish(LowerBoundRecoveryResult.STOPPED)
        }
        fun readEncrypted(uuid: UUID, done: (ByteArray) -> Unit) {
            link.readFrames(epoch, uuid, onFailure = { stop("encrypted read failed for $uuid") }) { frames ->
                val body = runCatching { link.decrypt(epoch, frames) }.getOrElse {
                    return@readFrames stop("authenticated decrypt failed for $uuid: ${it.message}")
                }
                done(body)
            }
        }
        link.enableControlNotifications(epoch) { setup ->
            if (!setup) return@enableControlNotifications stop("Could not prepare the pump. Please try again.")
            readEncrypted(CHAR_EVENT_COUNT) { countBody ->
                val count = YpsoGlb.decodeExact(countBody)
                    ?: return@readEncrypted stop("event count is not exact GLB (${countBody.size} bytes)")
                if (count < 2) return@readEncrypted stop("event count $count cannot prove a selector transition")
                readEncrypted(YpsoWritePolicy.EVENT_INDEX_UUID) { beforeBody ->
                    val before = YpsoGlb.decodeExact(beforeBody)
                        ?: return@readEncrypted stop("event selector is not exact GLB (${beforeBody.size} bytes)")
                    val target = if (before == 0) 1 else 0
                    if (before !in 0 until count || target >= count) {
                        return@readEncrypted stop("event selector $before is outside event count $count")
                    }
                    if (!link.isCurrent(epoch)) return@readEncrypted stop("connection owner changed before selector recovery")
                    val writeId = "history-lower-bound-$connectionId-$target-${UUID.randomUUID()}"
                    val started = historyLane.write(owner, writeId, YpsoGlb.encode(target), firmware()) { outcome ->
                        // The read-back branch below owns final RECOVERED publication.
                        if (outcome is YpsoWriteOutcome.ProvenRejected && outcome.failure.isPumpCounterError()) {
                            return@write finish(LowerBoundRecoveryResult.COUNTER_TOO_LOW)
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
                            return@write stop(
                                "selector write stopped at $detail",
                            )
                        }
                        readEncrypted(YpsoWritePolicy.EVENT_INDEX_UUID) { selectedBody ->
                            val selected = YpsoGlb.decodeExact(selectedBody)
                                ?: return@readEncrypted stop("selector read-back is not exact GLB (${selectedBody.size} bytes)")
                            if (selected != target) return@readEncrypted stop("selector read-back $selected did not match target $target")
                            val evidenceHash = YpsoWriteAccounting.sha256(
                                "${epoch.generation}|$reboot|$connectionId|$target|${YpsoWriteAccounting.sha256(selectedBody)}".toByteArray(),
                            )
                            val reconciled = runCatching {
                                historyLane.reconcile(
                                    owner,
                                    writeId,
                                    YpsoSemanticEvidence.ACCEPTED,
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

    // ---- bolus ----

    /** Read CHAR_BOLUS_STATUS and parse the immediate-delivery block via [BolusCommand.decode]. */
    fun readBolusStatus(onResult: (BolusCommand?) -> Unit) {
        // These guards used to fail silently, which made a failed cancellation recovery indistinguishable
        // from one that was never attempted. Name the blocking reason so pump logs can tell them apart.
        if (link.isActive(YpsoLinkOperation.BOLUS)) {
            aapsLogger.debug(LTag.PUMP, "YpsoPump bolus-status read skipped: a bolus write owns the link")
            onResult(null); return
        }
        val epoch = link.epoch() ?: run {
            aapsLogger.debug(LTag.PUMP, "YpsoPump bolus-status read skipped: not connected")
            onResult(null); return
        }
        link.readFrames(
            epoch,
            CHAR_BOLUS_STATUS,
            onFailure = {
                aapsLogger.error(LTag.PUMP, "YpsoPump bolus-status read failed")
                onResult(null)
            },
        ) { f ->
            val cmd = runCatching { decodeBolusStatus(epoch, f) }
                .onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump bolus-status decode failed: ${it.message}") }
                .getOrNull()
            onResult(cmd)
        }
    }

    internal fun readBolusStatus(owner: BolusCommandOwner, onResult: (BolusCommand?) -> Unit) {
        link.readFrames(owner.epoch, CHAR_BOLUS_STATUS, priority = true, onFailure = { onResult(null) }) { frames ->
            onResult(runCatching { decodeBolusStatus(owner.epoch, frames) }.getOrNull())
        }
    }

    private fun decodeBolusStatus(epoch: YpsoConnectionEpoch, frames: ByteArray): BolusCommand {
        val body = link.decrypt(epoch, frames)
        val payload = YpsoCrc.validatedPayload(body) ?: throw SecurityException("invalid bolus-status CRC")
        return BolusCommand(0.0).apply { decode(payload); require(success) { "invalid bolus-status layout" } }
    }

    /** Owner of one dispatched bolus write, valid only on the connection that sent it. */
    class BolusCommandOwner internal constructor(internal val owner: YpsoWriteLane.Owner) {
        internal val epoch: YpsoConnectionEpoch get() = owner.epoch
    }

    /**
     * Dispatch one validated bolus command on the authenticated connection [expectedConnectionKey]
     * names. The required [beforeDispatch] hook is the durable domain boundary and runs before the
     * first frame can leave.
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
        expectedConnectionKey: String,
        beforeDispatch: (PumpSession.Reservation) -> Unit,
        onOutcome: (YpsoWriteOutcome, BolusCommandOwner?) -> Unit,
    ) {
        fun notSent(detail: String) = onOutcome(
            YpsoWriteOutcome.NotSent(
                writeId,
                null,
                YpsoWriteFailure(
                    YpsoWriteFailure.Layer.READINESS,
                    YpsoWritePolicy.BOLUS_START_STOP_UUID,
                    firmware(),
                    detail = detail,
                ),
            ),
            null,
        )
        val epoch = link.epoch()
        writeReadinessFailure(epoch)?.let {
            notSent(it)
            return
        }
        val ready = checkNotNull(epoch)
        if (ready.key != expectedConnectionKey) {
            notSent("The pump connection dropped. No insulin was given.")
            return
        }
        val therapyProperties = ready.connection.properties(YpsoWritePolicy.BOLUS_START_STOP_UUID)
        if (therapyProperties == null || therapyProperties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            notSent("This pump does not accept bolus commands.")
            return
        }
        val lease = link.acquire(YpsoLinkOperation.BOLUS) ?: run {
            notSent("The pump is busy. Please try again in a moment.")
            return
        }
        val owner = YpsoWriteLane.Owner(ready, lease)
        val publicOwner = BolusCommandOwner(owner)
        link.enableControlNotifications(ready) { setup ->
            if (!setup || !link.isCurrent(ready)) {
                link.release(lease)
                notSent("Could not prepare the pump. Please try again.")
                return@enableControlNotifications
            }
            val payload = request?.payload() ?: BolusCommand.cancelPayload(extended = checkNotNull(cancelBlock) == YpsoBolusBlock.SLOW)
            bolusLane.write(owner, writeId, YpsoCrc.appendCrc(payload), firmware(), beforeDispatch) { onOutcome(it, publicOwner) }
        }
    }

    internal fun verifyBolusAccepted(owner: BolusCommandOwner, writeId: String, evidenceHash: String, detail: String): Boolean =
        bolusLane.reconcile(owner.owner, writeId, YpsoSemanticEvidence.ACCEPTED, evidenceHash, detail)

    internal fun recordBolusUnresolved(owner: BolusCommandOwner, writeId: String, evidenceHash: String, detail: String): Boolean =
        bolusLane.recordUnresolved(owner.owner, writeId, evidenceHash, detail)

    // ---- temporary basal ----

    /** TBR state of the current fresh status sample, stamped with the time that sample was read. */
    internal fun observedTbr(): YpsoTbrObservation? {
        val sample = pumpState.statusSnapshot ?: return null
        return YpsoTbrObservation(!sample.isSuspended, sample.activeTbrPercent, pumpState.activeTbrRemainingMinutes, sample.acquiredAt)
    }

    /** Owner of one dispatched TBR write, valid only on the connection that sent it. */
    class TbrCommandOwner internal constructor(internal val owner: YpsoWriteLane.Owner) {
        internal val epoch: YpsoConnectionEpoch get() = owner.epoch
    }

    /**
     * Dispatch one START_STOP_TBR command on the current authenticated connection. [beforeDispatch]
     * runs after the counter is reserved and before the first frame can leave the phone.
     */
    internal fun writeTbr(
        writeId: String,
        percent: Int,
        durationMinutes: Int,
        beforeDispatch: (PumpSession.Reservation) -> Unit,
        mayDispatch: () -> Boolean = { true },
        onOutcome: (YpsoWriteOutcome, TbrCommandOwner?) -> Unit,
    ) {
        fun notSent(layer: YpsoWriteFailure.Layer, detail: String) = onOutcome(
            YpsoWriteOutcome.NotSent(
                writeId,
                null,
                YpsoWriteFailure(layer, YpsoWritePolicy.TBR_START_STOP_UUID, firmware(), detail = detail),
            ),
            null,
        )
        val epoch = link.epoch()
        writeReadinessFailure(epoch)?.let { return notSent(YpsoWriteFailure.Layer.READINESS, it) }
        val ready = checkNotNull(epoch)
        val properties = ready.connection.properties(YpsoWritePolicy.TBR_START_STOP_UUID)
        if (properties == null || properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            return notSent(YpsoWriteFailure.Layer.CAPABILITY, "This pump does not accept temporary basal commands.")
        }
        val lease = link.acquire(YpsoLinkOperation.TBR) ?: return notSent(YpsoWriteFailure.Layer.READINESS, "The pump is busy. Please try again in a moment.")
        val owner = YpsoWriteLane.Owner(ready, lease)
        val publicOwner = TbrCommandOwner(owner)
        link.enableControlNotifications(ready) { setup ->
            if (!setup || !link.isCurrent(ready)) {
                link.release(lease)
                return@enableControlNotifications notSent(YpsoWriteFailure.Layer.READINESS, "Could not prepare the pump. Please try again.")
            }
            tbrLane.write(owner, writeId, TbrCommand(percent, durationMinutes).encode(), firmware(), beforeDispatch, mayDispatch) { outcome ->
                // Only a command that still holds the link has a same-link status read to resolve it.
                onOutcome(outcome, publicOwner.takeIf { tbrLane.category.keepsLink(outcome) })
            }
        }
    }

    /** Fresh system status on the TBR owner's connection; also publishes it like a routine read. */
    internal fun readTbrStatus(owner: TbrCommandOwner, onResult: (StatusCommand?, ByteArray?) -> Unit) {
        if (!pumpState.hasCompatibleStatusProtocol()) return onResult(null, null)
        link.readFrames(owner.epoch, CHAR_STATUS, priority = true, onFailure = { onResult(null, null) }) { frames ->
            val decoded = runCatching {
                val body = link.decrypt(owner.epoch, frames)
                val payload = YpsoCrc.validatedPayload(body) ?: error("invalid status CRC")
                StatusCommand().apply { decode(payload); require(success) { "status decode failed" } } to body
            }.onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump TBR status read failed: ${it.message}") }.getOrNull()
            decoded?.first?.let { publishDecodedStatus(it) }
            onResult(decoded?.first, decoded?.second)
        }
    }

    /** Status proved the effect of the TBR command. False leaves the write for [recordTbrUnresolved]. */
    internal fun verifyTbrAccepted(owner: TbrCommandOwner, writeId: String, evidenceHash: String, detail: String): Boolean =
        runCatching { tbrLane.reconcile(owner.owner, writeId, YpsoSemanticEvidence.ACCEPTED, evidenceHash, detail) }.getOrDefault(false)

    /** A measured rejection code with an unchanged status. False leaves the write for [recordTbrUnresolved]. */
    internal fun verifyTbrRejected(owner: TbrCommandOwner, writeId: String, evidenceHash: String, detail: String): Boolean =
        runCatching { tbrLane.reconcile(owner.owner, writeId, YpsoSemanticEvidence.REJECTED, evidenceHash, detail) }.getOrDefault(false)

    /**
     * No evidence resolves the command. Its counter is kept as the high-water mark and the reservation
     * retired, so later therapy can proceed; the TBR journal owns the therapy decision.
     */
    internal fun recordTbrUnresolved(owner: TbrCommandOwner, writeId: String, evidenceHash: String, detail: String) {
        runCatching { tbrLane.recordUnresolved(owner.owner, writeId, evidenceHash, detail) }
    }

    init {
        link.listener = linkListener
    }
}
