package app.aaps.pump.ypsopump.provisioning

import android.content.Context
import android.content.SharedPreferences
import app.aaps.pump.ypsopump.YpsoPumpConst
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionJournal
import app.aaps.pump.ypsopump.data.YpsoPumpState
import java.io.InputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** The sole transactional boundary for installing and observing YpsoPump credentials. */
@Singleton
class YpsoProvisioningService internal constructor(
    internal val owner: PumpSession,
    private val pumpState: YpsoPumpState,
    private val legacyStore: LegacyStore
) {

    @Inject constructor(context: Context, pumpState: YpsoPumpState) : this(
        PumpSession(SessionJournal(context)),
        pumpState,
        SharedPreferencesLegacyStore(context.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE))
    )

    internal var quiesceConnection: () -> Unit = {}
    internal var availabilityChanged: (PumpSession.Availability) -> Unit = {}
    private var verificationAttemptRequested = false

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
    fun installed(): InstalledSession? = owner.activeRecord()?.toInstalled(owner.availability())

    @Synchronized
    fun isConfigured(): Boolean = owner.activeRecord()?.let { it.keyHex != null && it.serial.isNotBlank() && it.pump.isNotBlank() } == true

    @Synchronized
    fun availability(): PumpSession.Availability = pumpState.availability

    @Synchronized
    fun keyBytes(): ByteArray? = owner.activeRecord()?.keyHex?.let(::decodeKey)

    @Synchronized
    fun installManual(draft: ManualDraft, now: Instant = Instant.now()): PumpSession.Installation {
        val serial = validateField(ManualField.SERIAL) { PumpIdentity.normalizeSerial(draft.serial) }
        val mac = validateField(ManualField.MAC) { PumpIdentity.normalizeMac(draft.mac) }
        validateField(ManualField.MAC) { PumpIdentity.validatePair(serial, mac) }
        val current = owner.activeRecord()
        val legacy = legacyStore.load()
        val explicitKey = draft.replacementKey?.takeIf(String::isNotBlank)?.let {
            validateField(ManualField.KEY) { normalizeKey(it) }
        }
        val key = explicitKey ?: current?.keyHex?.let(::decodeKey)
            ?: legacy.key
                ?.takeIf { legacy.mac?.let { value -> runCatching { PumpIdentity.normalizeMac(value) }.getOrNull() } == mac }
                ?.let { validateField(ManualField.KEY) { normalizeKey(it) } }
            ?: throw ManualValidationException(ManualField.KEY, "A 32-byte session key is required")
        val preservesCurrentKey = explicitKey == null || current?.keyHex?.equals(key.toHex(), ignoreCase = true) == true
        return install(
            serial,
            mac,
            key,
            createdAt = current?.createdAt.takeIf { preservesCurrentKey },
            importedAt = now.toEpochMilli(),
            source = mapOf("profile" to "manual")
        ).also { clearLegacyCredentials() }
    }

    @Synchronized
    fun reviewDocument(stream: InputStream, now: Instant = Instant.now()): YpsoSessionDocument {
        val document = YpsoSessionDocumentParser.parse(boundedRead(stream), now)
        return try {
            val serial = PumpIdentity.normalizeSerial(document.serial)
            PumpIdentity.validatePair(serial, document.mac)
            document
        } catch (e: Exception) {
            document.sharedKey.fill(0)
            throw e
        }
    }

    @Synchronized
    fun installDocument(document: YpsoSessionDocument, now: Instant = Instant.now()): PumpSession.Installation {
        val serial = PumpIdentity.normalizeSerial(document.serial)
        PumpIdentity.validatePair(serial, document.mac)
        return try {
            install(
                serial,
                document.mac,
                document.sharedKey.copyOf(),
                document.createdAt.toEpochMilli(),
                now.toEpochMilli(),
                document.source
            ).also { clearLegacyCredentials() }
        } finally {
            document.sharedKey.fill(0)
        }
    }

    @Synchronized
    fun recordUnavailable(
        causes: Set<PumpSession.AvailabilityCause>,
        code: Int? = null,
        operation: String? = null,
        firmware: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        require(causes.isNotEmpty())
        val prior = owner.availability()
        val durablePrerequisites = prior.causes.intersect(setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN))
        val stickyRekey = prior.causes.intersect(setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED))
        val nextCauses = causes + durablePrerequisites + stickyRekey
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
    }

    /** Verification requires a serial independently observed from the bonded name or GATT identity. */
    @Synchronized
    fun markVerified(serialObserved: String?, now: Long = System.currentTimeMillis()) {
        val configured = owner.activeRecord() ?: throw SecurityException("No configured pump")
        val observed = serialObserved?.takeIf(String::isNotBlank)?.let {
            runCatching { PumpIdentity.normalizeSerial(it) }.getOrNull()
        }
        if (observed == null) {
            recordUnavailable(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE), operation = "identity-unobservable", now = now)
            throw SecurityException("Pump serial could not be independently observed")
        }
        if (observed != configured.serial) {
            recordUnavailable(setOf(PumpSession.AvailabilityCause.IDENTITY_MISMATCH), operation = "identity-read", now = now)
            throw SecurityException("Configured serial does not match the connected pump")
        }
        owner.markVerified(configured.serial, now)
        pumpState.claimedSerialNumber = configured.serial
        pumpState.serialNumber = configured.serial
        publishAvailability()
    }

    @Synchronized
    fun retryAllowed(now: Long = System.currentTimeMillis()): Boolean {
        if (verificationAttemptRequested) {
            verificationAttemptRequested = false
            return true
        }
        val availability = pumpState.availability
        if (PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED in availability.causes) return false
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
        owner.preflight(provisioning)
        quiesceConnection()
        owner.install(provisioning).also {
            pumpState.invalidateStatus()
            pumpState.claimedSerialNumber = serial
            pumpState.serialNumber = ""
            publishAvailability()
        }
    } finally {
        key.fill(0)
    }

    /** Complete legacy triples migrate once. MAC/key-only state waits for explicit real serial entry. */
    private fun migrateCompleteLegacyCredentials() {
        if (owner.activeRecord() != null) {
            if (owner.activeRecord()?.keyHex != null) clearLegacyCredentials()
            return
        }
        val legacy = legacyStore.load()
        val serial = legacy.serial?.takeIf(String::isNotBlank) ?: return
        val mac = legacy.mac?.takeIf(String::isNotBlank) ?: return
        val key = legacy.key?.takeIf(String::isNotBlank) ?: return
        runCatching {
            val normalizedSerial = PumpIdentity.normalizeSerial(serial)
            val normalizedMac = PumpIdentity.normalizeMac(mac)
            PumpIdentity.validatePair(normalizedSerial, normalizedMac)
            install(
                normalizedSerial,
                normalizedMac,
                normalizeKey(key),
                createdAt = null,
                importedAt = System.currentTimeMillis(),
                source = mapOf("profile" to "legacy-preferences")
            )
            clearLegacyCredentials()
        }
    }

    private fun publishAvailability() {
        val value = owner.availability()
        pumpState.updateAvailability(value)
        availabilityChanged(value)
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
        return decodeKey(normalized).also { require(it.any { byte -> byte.toInt() != 0 }) { "Session key must not be all zero" } }
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
                .apply()
        }
    }

    companion object {
        private val RETRY_DELAYS_MS = longArrayOf(5_000, 15_000, 30_000, 60_000, 5 * 60_000)
        private const val MAX_RECORDED_FAILURES = 5
        private const val TRANSPORT_NOTIFICATION_THRESHOLD = 3
        private const val LEGACY_PREFERENCES = "ypso_ble_state"
    }
}
