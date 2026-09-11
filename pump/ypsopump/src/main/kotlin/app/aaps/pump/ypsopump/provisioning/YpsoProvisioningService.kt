package app.aaps.pump.ypsopump.provisioning

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import app.aaps.pump.ypsopump.YpsoPumpConst
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionJournal
import app.aaps.pump.ypsopump.data.YpsoPumpState
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** The sole transactional boundary for installing and observing YpsoPump credentials. */
@Singleton
class YpsoProvisioningService internal constructor(
    internal val owner: PumpSession,
    private val pumpState: YpsoPumpState,
    private val legacyStore: LegacyStore,
    private val bondedSerialForMac: (String) -> String? = { null }
) {

    @Inject constructor(context: Context, pumpState: YpsoPumpState) : this(
        PumpSession(SessionJournal(context)),
        pumpState,
        SharedPreferencesLegacyStore(context.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)),
        { mac -> bondedPumpSerial(context, mac) }
    )

    internal var quiesceConnection: () -> Unit = {}
    internal var availabilityChanged: (PumpSession.Availability) -> Unit = {}
    private var verificationAttemptRequested = false
    private val mutationEpoch = AtomicLong()
    private val provisioningLock = Any()

    // A failed candidate whose only fallback is the retained legacy bundle must never make the BLE
    // callback thread wait for [provisioningLock]: BLE callbacks run under the manager's opLock, while
    // install/cancel hold [provisioningLock] and take opLock to quiesce. Scheduling the restore breaks
    // that cycle; tests may capture the scheduled task through this seam, but production dispatch must
    // stay asynchronous.
    private val restoreExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ypso-session-restore").apply { isDaemon = true }
    }
    internal var dispatchSessionRestore: ((() -> Unit) -> Unit) = { task -> restoreExecutor.execute(task) }
    private val restoreSequence = AtomicLong()
    @Volatile private var sessionRestorePending = false

    /** True between scheduling a retained-session restore and its execution. */
    internal fun isSessionRestorePending(): Boolean = sessionRestorePending

    /** Test seam: wait until every scheduled retained-session restore has run. */
    internal fun awaitPendingSessionRestore() {
        restoreExecutor.submit {}.get()
    }

    init {
        migrateCompleteLegacyCredentials()
        refreshState()
    }

    data class InstalledSession(
        val serial: String,
        val mac: String,
        val keyFingerprint: String,
        val createdAt: Instant?,
        val importedAt: Instant?,
        val source: Map<String, String>,
        val verifiedAt: Instant?,
        val availability: PumpSession.Availability
    )

    /** Credentials selected for the next connection. A candidate is never therapy-capable. */
    internal data class ConnectionSession(
        val generation: String,
        val attemptId: String?,
        val serial: String,
        val mac: String,
        val key: ByteArray,
        val candidate: Boolean,
        val epoch: Long = 0
    )

    data class VerificationState(val attemptId: String, val status: PumpSession.AttemptStatus)

    data class ManualDraft(val serial: String, val mac: String, val replacementKey: String?)

    enum class ManualField { SERIAL, MAC, KEY }
    class ManualValidationException(val field: ManualField, message: String, cause: Throwable? = null) :
        IllegalArgumentException(message, cause)

    internal data class LegacyCredentials(val serial: String?, val mac: String?, val key: String?)
    internal interface LegacyStore {
        fun load(): LegacyCredentials
        fun clear()
    }

    @Synchronized
    fun installed(): InstalledSession? = owner.committedRecord()?.toInstalled(owner.availability())

    /** Public candidate metadata for UI; never contains raw key material. */
    @Synchronized
    fun pending(): InstalledSession? = owner.candidateRecord()?.toInstalled(owner.availability())

    @Synchronized
    fun verificationState(): VerificationState? = owner.verificationAttempt()?.let { VerificationState(it.id, it.status) }

    @Synchronized
    fun isConfigured(): Boolean = owner.activeRecord()?.let { it.keyHex != null && it.serial.isNotBlank() && it.pump.isNotBlank() } == true

    @Synchronized
    fun availability(): PumpSession.Availability = pumpState.availability

    @Synchronized
    fun keyBytes(): ByteArray? = owner.committedRecord()?.keyHex?.let(::decodeKey)

    @Synchronized
    internal fun connectionSession(): ConnectionSession? = owner.activeRecord()?.let { record ->
        val key = record.keyHex?.let(::decodeKey) ?: return null
        val candidate = owner.candidateRecord()?.generation == record.generation
        ConnectionSession(record.generation, owner.verificationAttempt()?.id.takeIf { candidate }, record.serial, record.pump, key, candidate, mutationEpoch.get())
    }

    /** Odd epochs denote the quiesce-to-commit interval and are never acquirable. */
    internal fun isCurrentConnection(value: ConnectionSession): Boolean =
        value.epoch % 2L == 0L && mutationEpoch.get() == value.epoch && owner.activeRecord()?.generation == value.generation &&
            (!value.candidate || owner.verificationAttempt()?.id == value.attemptId)

    /** UI/API seam: discard an unverified candidate and return to the last verified/active bundle. */
    fun cancelCandidate() {
        synchronized(provisioningLock) {
            // The epoch transition must be atomic with promotion's even-epoch check; both take the
            // service monitor so cancellation cannot start between that check and the commit.
            synchronized(this) { mutationEpoch.incrementAndGet() }
            try {
                quiesceConnection()
                val cancelled = synchronized(this) {
                    val hadCandidate = owner.candidateRecord() != null
                    owner.cancelCandidate(PumpSession.AttemptStatus.CANCELLED)
                    verificationAttemptRequested = false
                    pumpState.invalidateStatus()
                    refreshState()
                    publishAvailability()
                    hadCandidate
                }
                if (cancelled) restoreRetainedLegacySession()
            } finally {
                completeMutationEpoch()
            }
        }
    }

    fun installManual(draft: ManualDraft, now: Instant = Instant.now()): PumpSession.Installation = synchronized(provisioningLock) {
        val serial = validateField(ManualField.SERIAL) { PumpIdentity.normalizeSerial(draft.serial) }
        val mac = validateField(ManualField.MAC) { PumpIdentity.normalizeMac(draft.mac) }
        validateField(ManualField.MAC) { PumpIdentity.validatePair(serial, mac) }
        val current = owner.activeRecord()
        val legacy = legacyStore.load()
        val explicitKey = draft.replacementKey?.takeIf(String::isNotBlank)?.let {
            validateField(ManualField.KEY) { normalizeKey(it) }
        }
        var decodedFallback: ByteArray? = null
        try {
            val fallback = if (explicitKey != null) null else (
                current?.keyHex?.let(::decodeKey)
                    ?: legacy.key
                        ?.takeIf { legacy.mac?.let { value -> runCatching { PumpIdentity.normalizeMac(value) }.getOrNull() } == mac }
                        ?.let { validateField(ManualField.KEY) { normalizeKey(it) } }
                )
            decodedFallback = fallback
            val key = explicitKey ?: fallback
                ?: throw ManualValidationException(ManualField.KEY, "A 32-byte session key is required")
            // A suspected re-key blocks re-saving the identical rejected key: that would grant another
            // verification read without new key material and defeat the anti-retry safeguard.
            if (PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED in owner.availability().causes) {
                val currentHex = current?.keyHex
                if (explicitKey == null || (currentHex != null && explicitKey.toHex().equals(currentHex, ignoreCase = true))) {
                    throw ManualValidationException(ManualField.KEY, "Replacement key required after rejection")
                }
            }
            val preservesCurrentKey = explicitKey == null || current?.keyHex?.equals(key.toHex(), ignoreCase = true) == true
            install(
                serial,
                mac,
                key,
                createdAt = current?.createdAt.takeIf { preservesCurrentKey },
                importedAt = now.toEpochMilli(),
                source = mapOf("profile" to "manual")
            ).also { if (owner.candidateRecord() == null) clearLegacyCredentials() }
        } finally {
            explicitKey?.fill(0)
            decodedFallback?.fill(0)
        }
    }

    /** Stage, allow one immediate poll, and either enqueue it or roll back this exact candidate. */
    fun installManualAndStartVerification(
        draft: ManualDraft,
        now: Instant = Instant.now(),
        enqueue: () -> Boolean
    ): PumpSession.Installation = synchronized(provisioningLock) {
        startVerification({ installManual(draft, now) }, enqueue)
    }

    fun reviewDocument(stream: InputStream, now: Instant = Instant.now()): YpsoSessionDocument {
        val data = boundedRead(stream)
        return try {
            val document = YpsoSessionDocumentParser.parse(data, now)
            try {
                val serial = PumpIdentity.normalizeSerial(document.serial)
                PumpIdentity.validatePair(serial, document.mac)
                document
            } catch (e: Exception) {
                document.sharedKey.fill(0)
                throw e
            }
        } finally {
            data.fill(0)
        }
    }

    fun installDocument(document: YpsoSessionDocument, now: Instant = Instant.now()): PumpSession.Installation = synchronized(provisioningLock) {
        try {
            val serial = PumpIdentity.normalizeSerial(document.serial)
            PumpIdentity.validatePair(serial, document.mac)
            if (PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED in owner.availability().causes) {
                val currentHex = owner.activeRecord()?.keyHex
                if (currentHex != null && document.sharedKey.toHex().equals(currentHex, ignoreCase = true)) {
                    throw SecurityException("Replacement key required after rejection")
                }
            }
            install(
                serial,
                document.mac,
                document.sharedKey.copyOf(),
                document.createdAt.toEpochMilli(),
                now.toEpochMilli(),
                document.source
            ).also { if (owner.candidateRecord() == null) clearLegacyCredentials() }
        } finally {
            document.sharedKey.fill(0)
        }
    }

    /** Document equivalent of [installManualAndStartVerification], including secret destruction. */
    fun installDocumentAndStartVerification(
        document: YpsoSessionDocument,
        now: Instant = Instant.now(),
        enqueue: () -> Boolean
    ): PumpSession.Installation = synchronized(provisioningLock) {
        try {
            startVerification({ installDocument(document, now) }, enqueue)
        } finally {
            document.sharedKey.fill(0)
        }
    }

    private fun startVerification(install: () -> PumpSession.Installation, enqueue: () -> Boolean): PumpSession.Installation {
        val installation = install()
        val candidate = synchronized(this) {
            val value = connectionSession()
            check(value?.candidate == true && value.attemptId != null) { "Provisioning did not stage a verification candidate" }
            verificationAttemptRequested = true
            value
        }
        try {
            if (!enqueue()) throw IllegalStateException("Verification status read was not accepted")
            return installation
        } catch (error: Throwable) {
            synchronized(this) { mutationEpoch.incrementAndGet() }
            try {
                quiesceConnection()
                val cancelled = synchronized(this) {
                    val result = owner.cancelCandidate(candidate.generation, candidate.attemptId, PumpSession.AttemptStatus.CANCELLED)
                    verificationAttemptRequested = false
                    pumpState.invalidateStatus()
                    refreshState()
                    publishAvailability()
                    result
                }
                if (cancelled) restoreRetainedLegacySession()
            } finally {
                completeMutationEpoch()
            }
            throw error
        }
    }

    @Synchronized
    fun recordUnavailable(
        causes: Set<PumpSession.AvailabilityCause>,
        code: Int? = null,
        operation: String? = null,
        firmware: String? = null,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        require(causes.isNotEmpty())
        val prior = owner.availability()
        val durablePrerequisites = prior.causes.intersect(setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN))
        val stickyRekey = prior.causes.intersect(setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED))
        val nextCauses = causes + durablePrerequisites + stickyRekey
        // An empty journal already has durable UNCONFIGURED state. AAPS may ask to connect every second;
        // do not rotate the Keystore anchor or repost the same notification for those no-op polls.
        if (nextCauses == setOf(PumpSession.AvailabilityCause.UNCONFIGURED) && prior.causes == nextCauses) return false
        val preservesRekeyMetadata = stickyRekey.isNotEmpty() && PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED !in causes
        val failures = if (prior.causes == nextCauses) (prior.failures + 1).coerceAtMost(MAX_RECORDED_FAILURES) else 1
        val delay = RETRY_DELAYS_MS[(failures - 1).coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        val availability = PumpSession.Availability(
            nextCauses,
            if (preservesRekeyMetadata) prior.since else now,
            if (preservesRekeyMetadata) prior.code else code,
            if (preservesRekeyMetadata) prior.operation else operation,
            if (preservesRekeyMetadata) prior.firmware else firmware,
            failures,
            if (PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED in nextCauses) null else now + delay
        )
        runCatching { owner.setAvailability(availability) }
            .onSuccess { publishAvailability() }
            .onFailure {
                // A missing/restored/corrupt journal cannot be rewritten safely. Detection itself recurs
                // on every process start, so expose the condition without pretending persistence succeeded.
                val unavailable = availability.copy(causes = nextCauses + PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE)
                pumpState.updateAvailability(unavailable)
                availabilityChanged(unavailable)
            }
        return true
    }

    /** Verification requires a serial independently observed from the bonded name or GATT identity. */
    fun markVerified(generation: String, attemptId: String?, serialObserved: String?, now: Long = System.currentTimeMillis()): Boolean {
        val (configured, promotingCandidate) = synchronized(this) {
            val record = owner.activeRecord() ?: throw SecurityException("No configured pump")
            check(record.generation == generation) { "Stale verification callback" }
            if (owner.candidateRecord()?.generation == generation) check(owner.verificationAttempt()?.id == attemptId) { "Stale verification attempt" }
            record to (owner.candidateRecord()?.generation == generation)
        }
        val observed = serialObserved?.takeIf(String::isNotBlank)?.let {
            runCatching { PumpIdentity.normalizeSerial(it) }.getOrNull()
        }
        if (observed == null) {
            failCandidateOrRecord(generation, attemptId, setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE), "identity-unobservable", now)
            throw SecurityException("Pump serial could not be independently observed")
        }
        if (observed != configured.serial) {
            failCandidateOrRecord(generation, attemptId, setOf(PumpSession.AvailabilityCause.IDENTITY_MISMATCH), "identity-read", now)
            throw SecurityException("Configured serial does not match the connected pump")
        }
        synchronized(this) {
            // A session mutation (install/cancel) increments the epoch before it quiesces the transport.
            // Promotion must not slip through a check-to-commit gap once cancellation has started.
            check(mutationEpoch.get() % 2L == 0L) { "Verification superseded by a session mutation" }
            owner.markVerified(generation, attemptId, configured.serial, now)
            if (owner.candidateRecord() == null) clearLegacyCredentials()
            pumpState.claimedSerialNumber = configured.serial
            pumpState.serialNumber = configured.serial
            publishAvailability()
        }
        return promotingCandidate
    }

    /** Compatibility/test seam; production BLE supplies the connection generation. */
    fun markVerified(serialObserved: String?, now: Long = System.currentTimeMillis()): Boolean {
        val (generation, attemptId) = synchronized(this) {
            val candidate = owner.candidateRecord() != null
            (owner.activeRecord()?.generation ?: throw SecurityException("No configured pump")) to owner.verificationAttempt()?.id.takeIf { candidate }
        }
        return markVerified(generation, attemptId, serialObserved, now)
    }

    fun rejectCandidate(generation: String, attemptId: String?): Boolean {
        val rejected = synchronized(this) {
            if (owner.candidateRecord()?.generation != generation || owner.verificationAttempt()?.id != attemptId) return false
            owner.cancelCandidate(PumpSession.AttemptStatus.FAILED)
            refreshState()
            publishAvailability()
            true
        }
        if (rejected) scheduleRetainedSessionRestore(owner.availability())
        return rejected
    }

    /** One ownership-gated failure transaction. A stale callback has no availability side effects. */
    fun failCandidateOrRecord(
        generation: String?, attemptId: String?, causes: Set<PumpSession.AvailabilityCause>, operation: String?, now: Long = System.currentTimeMillis(),
        firmware: String? = null, code: Int? = null
    ): Boolean {
        val (handled, restoreAvailability) = synchronized(this) {
            if (generation != null && owner.candidateRecord()?.generation == generation) {
                if (owner.verificationAttempt()?.id != attemptId) return false
                val availability = unavailable(owner.availability(), causes, operation, now, firmware, code)
                val failed = owner.failCandidate(generation, attemptId, availability)
                if (failed) {
                    refreshState()
                    publishAvailability()
                }
                failed to availability.takeIf { failed && owner.activeRecord() == null }
            } else {
                // A callback carrying a completed candidate attempt is stale once no matching candidate
                // remains; it must not mutate a promoted successor.
                if (attemptId != null) return false
                if (generation != null && owner.activeRecord()?.generation != generation) return false
                recordUnavailable(causes, code = code, operation = operation, firmware = firmware, now = now)
                true to null
            }
        }
        if (restoreAvailability != null) scheduleRetainedSessionRestore(restoreAvailability)
        return handled
    }

    /** Only the latest scheduled restore may activate credentials; older queued restores are stale. */
    private fun scheduleRetainedSessionRestore(availability: PumpSession.Availability) {
        val sequence = restoreSequence.incrementAndGet()
        sessionRestorePending = true
        dispatchSessionRestore { restoreRetainedLegacySession(availability, sequence) }
    }

    /**
     * Record a retryable failure against the exact verification attempt without retiring it, so the
     * candidate stays selected and uses bounded backoff. A stale attempt has no side effects.
     */
    fun recordCandidateOrUnavailable(
        generation: String?, attemptId: String?, causes: Set<PumpSession.AvailabilityCause>, operation: String?, now: Long = System.currentTimeMillis(),
        firmware: String? = null, code: Int? = null
    ): Boolean = synchronized(this) {
        if (generation != null && owner.candidateRecord()?.generation == generation) {
            if (owner.verificationAttempt()?.id != attemptId) return false
            owner.setAvailability(unavailable(owner.availability(), causes, operation, now, firmware, code))
            refreshState()
            publishAvailability()
            true
        } else {
            if (attemptId != null) return false
            if (generation != null && owner.activeRecord()?.generation != generation) return false
            recordUnavailable(causes, code = code, operation = operation, firmware = firmware, now = now)
            true
        }
    }

    /**
     * A failed or cancelled replacement must not leave the pump unreachable. If no committed session
     * survived, the retained legacy credentials become the active, still-unverified session again,
     * preserving a recorded failure's availability and retry backoff, so the next connection attempt
     * uses the same protected credentials a process restart would have migrated.
     *
     * [capturedAvailability] is the failure state that triggered the restore when the restore was
     * dispatched asynchronously: normal polling may record an unconfigured condition in the window
     * before this task runs, and that must not replace the candidate's recorded failure.
     * [sequence] scopes deferred restores: once a newer restore has been scheduled, an older queued
     * task must not activate credentials or publish stale evidence.
     */
    private fun restoreRetainedLegacySession(capturedAvailability: PumpSession.Availability? = null, sequence: Long? = null) {
        synchronized(provisioningLock) {
            if (sequence != null && sequence != restoreSequence.get()) return
            try {
                if (owner.activeRecord() != null) return
                val legacy = legacyStore.load()
                val mac = legacy.mac?.takeIf(String::isNotBlank) ?: return
                val key = legacy.key?.takeIf(String::isNotBlank) ?: return
                runCatching {
                    val normalizedMac = PumpIdentity.normalizeMac(mac)
                    val serial = legacy.serial?.takeIf(String::isNotBlank) ?: bondedSerialForMac(normalizedMac) ?: return
                    val normalizedSerial = PumpIdentity.normalizeSerial(serial)
                    PumpIdentity.validatePair(normalizedSerial, normalizedMac)
                    val normalizedKey = normalizeKey(key)
                    try {
                        val provisioning = PumpSession.Provisioning(
                            normalizedMac, normalizedSerial, normalizedKey, null, System.currentTimeMillis(), mapOf("profile" to "legacy-preferences")
                        )
                        synchronized(this) {
                            val availability = (capturedAvailability ?: owner.availability()).let { value ->
                                // A cancelled first attempt leaves the journal's default UNCONFIGURED cause, but the
                                // restored bundle is present and merely unverified.
                                if (value.causes == setOf(PumpSession.AvailabilityCause.UNCONFIGURED)) PumpSession.Availability(emptySet(), value.since)
                                else value
                            }
                            owner.activateUnverified(provisioning, availability)
                            refreshState()
                            publishAvailability()
                        }
                    } finally {
                        normalizedKey.fill(0)
                    }
                }
            } finally {
                if (sequence == null || sequence == restoreSequence.get()) sessionRestorePending = false
            }
        }
    }

    @Synchronized
    fun retryAllowed(now: Long = System.currentTimeMillis()): Boolean {
        if (verificationAttemptRequested) {
            verificationAttemptRequested = false
            return true
        }
        val availability = pumpState.availability
        if (PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED in availability.causes) return false
        // Retry timestamps are meaningful only after an actual configured-session failure. Older
        // builds could carry an unconfigured backoff into the first protected migration.
        if (availability.failures == 0) return true
        return availability.retryAt?.let { now >= it } ?: true
    }

    @Synchronized
    fun requestVerificationAttempt() {
        verificationAttemptRequested = true
    }

    @Synchronized
    fun notificationRequired(): Boolean {
        val value = pumpState.availability
        val actionable = value.causes - PumpSession.AvailabilityCause.COUNTER_UNCERTAIN
        if (actionable.isEmpty()) return false
        return actionable.any { it != PumpSession.AvailabilityCause.TRANSPORT } || value.failures >= TRANSPORT_NOTIFICATION_THRESHOLD
    }

    @Synchronized
    fun refreshState() {
        val installed = owner.activeRecord()
        pumpState.claimedSerialNumber = installed?.serial.orEmpty()
        pumpState.serialNumber = installed?.verifiedSerial.orEmpty()
        pumpState.updateAvailability(owner.availability())
    }

    private fun install(
        serial: String,
        mac: String,
        key: ByteArray,
        createdAt: Long?,
        importedAt: Long,
        source: Map<String, String>
    ): PumpSession.Installation = try {
        val provisioning = PumpSession.Provisioning(mac, serial, key, createdAt, importedAt, source)
        synchronized(provisioningLock) {
            synchronized(this) { owner.preflight(provisioning); mutationEpoch.incrementAndGet() }
            try {
                quiesceConnection()
                synchronized(this) {
                    verificationAttemptRequested = false
                    owner.install(provisioning).also {
                        pumpState.invalidateStatus()
                        pumpState.claimedSerialNumber = serial
                        pumpState.serialNumber = ""
                        publishAvailability()
                    }
                }
            } finally {
                completeMutationEpoch()
            }
        }
    } finally {
        key.fill(0)
    }

    /** Complete legacy triples migrate once. MAC/key-only state waits for explicit real serial entry. */
    private fun migrateCompleteLegacyCredentials() {
        if (owner.activeRecord() != null) {
            if (owner.candidateRecord() == null && owner.activeRecord()?.keyHex != null) clearLegacyCredentials()
            return
        }
        val legacy = legacyStore.load()
        val mac = legacy.mac?.takeIf(String::isNotBlank) ?: return
        val key = legacy.key?.takeIf(String::isNotBlank) ?: return
        runCatching {
            val normalizedMac = PumpIdentity.normalizeMac(mac)
            // Older builds stored only MAC/key. A bonded pump name is an independent identity
            // observation, so it can supply the missing real serial without deriving it from the MAC.
            val serial = legacy.serial?.takeIf(String::isNotBlank) ?: bondedSerialForMac(normalizedMac) ?: return
            val normalizedSerial = PumpIdentity.normalizeSerial(serial)
            PumpIdentity.validatePair(normalizedSerial, normalizedMac)
            install(
                normalizedSerial,
                normalizedMac,
                normalizeKey(key),
                createdAt = null,
                importedAt = System.currentTimeMillis(),
                source = mapOf("profile" to "legacy-preferences")
            )
            if (owner.candidateRecord() == null) clearLegacyCredentials()
        }
    }

    private fun publishAvailability() {
        val value = owner.availability()
        pumpState.updateAvailability(value)
        availabilityChanged(value)
    }

    /** Called under [provisioningLock]; never strand normal acquisition after a failed durable mutation. */
    private fun completeMutationEpoch() {
        if (mutationEpoch.get() % 2L != 0L) mutationEpoch.incrementAndGet()
    }

    private fun unavailable(
        prior: PumpSession.Availability,
        causes: Set<PumpSession.AvailabilityCause>,
        operation: String?,
        now: Long,
        firmware: String?,
        code: Int? = null
    ): PumpSession.Availability {
        val retained = prior.causes.intersect(setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN, PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED))
        val next = causes + retained
        val failures = if (prior.causes == next) (prior.failures + 1).coerceAtMost(MAX_RECORDED_FAILURES) else 1
        val sticky = PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED in retained && PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED !in causes
        return PumpSession.Availability(
            next,
            if (sticky) prior.since else now,
            if (sticky) prior.code else code,
            if (sticky) prior.operation else operation,
            if (sticky) prior.firmware else firmware,
            failures,
            if (PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED in next) null else now + RETRY_DELAYS_MS[(failures - 1).coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        )
    }

    private fun clearLegacyCredentials() = legacyStore.clear()

    private inline fun <T> validateField(field: ManualField, block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw ManualValidationException(field, e.message ?: "Invalid value", e)
    }

    private fun normalizeKey(value: String): ByteArray {
        val normalized = value.filterNot(Char::isWhitespace).uppercase()
        require(normalized.matches(Regex("[0-9A-F]{64}"))) { "Session key must contain 64 hexadecimal characters" }
        val decoded = decodeKey(normalized)
        if (decoded.all { it.toInt() == 0 }) {
            decoded.fill(0)
            throw IllegalArgumentException("Session key must not be all zero")
        }
        return decoded
    }

    private fun decodeKey(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun boundedRead(stream: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            require(out.size() + read <= YpsoSessionDocumentParser.MAX_DOCUMENT_BYTES) { "Session file is larger than 64 KiB" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun PumpSession.Record.toInstalled(availability: PumpSession.Availability) = InstalledSession(
        serial,
        pump,
        keyId.take(16),
        createdAt?.let(Instant::ofEpochMilli),
        importedAt?.let(Instant::ofEpochMilli),
        source,
        verifiedAt?.let(Instant::ofEpochMilli),
        availability
    )

    private class SharedPreferencesLegacyStore(private val preferences: SharedPreferences) : LegacyStore {
        override fun load() = LegacyCredentials(
            preferences.getString(YpsoPumpConst.PREF_PUMP_SERIAL, null)?.trim(),
            preferences.getString(YpsoPumpConst.PREF_PUMP_MAC, null)?.trim(),
            preferences.getString(YpsoPumpConst.PREF_SHARED_KEY, null)?.trim()
        )

        override fun clear() {
            // The protected journal is already committed; remove the plaintext legacy copy durably in the
            // same recovery generation rather than leaving an asynchronous apply() window behind.
            preferences.edit()
                .remove(YpsoPumpConst.PREF_SHARED_KEY)
                .remove(YpsoPumpConst.PREF_PRIVATE_KEY)
                .remove(YpsoPumpConst.PREF_PUMP_PUBLIC_KEY)
                .remove(YpsoPumpConst.PREF_PUMP_MAC)
                .remove(YpsoPumpConst.PREF_PUMP_SERIAL)
                .remove(YpsoPumpConst.PREF_KEY_DATE)
                .remove(YpsoPumpConst.PREF_REBOOT_COUNTER)
                .remove(YpsoPumpConst.PREF_READ_COUNTER)
                .remove(YpsoPumpConst.PREF_WRITE_COUNTER)
                .commit()
        }
    }

    companion object {
        private val RETRY_DELAYS_MS = longArrayOf(5_000, 15_000, 30_000, 60_000, 5 * 60_000)
        private const val MAX_RECORDED_FAILURES = 5
        private const val TRANSPORT_NOTIFICATION_THRESHOLD = 3
        private const val LEGACY_PREFERENCES = "ypso_ble_state"

        @SuppressLint("MissingPermission")
        private fun bondedPumpSerial(context: Context, mac: String): String? = runCatching {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return null
            val device = adapter.getRemoteDevice(mac)
            if (device.bondState != BluetoothDevice.BOND_BONDED) return null
            PumpIdentity.serialFromDeviceName(device.name)
        }.getOrNull()
    }
}
