package app.aaps.pump.ypsopump.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Journal revisions are authenticated by unique, non-exportable Keystore keys. The previous anchor
 * is destroyed BEFORE replacing the file. A crash anywhere in that interval requires recovery;
 * it cannot restore an older replay floor. The file is excluded from Android backup.
 */
class SessionJournal internal constructor(private val storage: Storage) : PumpSession.Store {

    constructor(context: Context) : this(AndroidStorage(context))

    internal interface Storage {
        fun read(): String?
        fun anchors(): List<String>
        fun create(alias: String)
        fun delete(alias: String)
        fun seal(alias: String, body: String): String
        fun open(alias: String, sealed: String): String
        fun authenticateLegacy(alias: String, body: String): ByteArray
        fun writeAndSync(value: String)
    }


    override fun load(): PumpSession.State {
        val anchors = storage.anchors()
        val contents = storage.read()
        if (contents == null && anchors.isEmpty()) return PumpSession.State()
        val envelope = JSONObject(checkNotNull(contents))
        val alias = envelope.getString("anchor")
        check(anchors == listOf(alias)) { "Incomplete or restored session journal" }
        val body = if (envelope.has("sealed")) storage.open(alias, envelope.getString("sealed")) else {
            val legacyBody = envelope.getString("body")
            val expectedMac = envelope.getString("mac")
            require(expectedMac.matches(Regex("[0-9a-fA-F]{64}"))) { "Malformed legacy session journal MAC" }
            check(
                java.security.MessageDigest.isEqual(
                    storage.authenticateLegacy(alias, legacyBody),
                    expectedMac.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                )
            ) { "Corrupt legacy session journal" }
            legacyBody
        }
        val json = JSONObject(body)
        val version = json.getInt("version")
        check(version in 1..3)
        val records = json.getJSONArray("records")
        val parsed = (0 until records.length()).map { index ->
            val r = records.getJSONObject(index)
            val reservation = r.optJSONObject("reservation")?.let {
                PumpSession.Reservation(it.getString("id"), it.getLong("counter"), PumpSession.Phase.valueOf(it.getString("phase")))
            }
            PumpSession.Record(
                r.getString("pump"), r.getString("key"), r.getString("generation"), if (r.isNull("reboot")) null else r.getInt("reboot"),
                if (r.isNull("read")) null else r.getLong("read"), if (r.isNull("write")) null else r.getLong("write"), reservation,
                serial = r.stringOrNull("serial").orEmpty(),
                keyHex = r.stringOrNull("keyHex"),
                createdAt = r.optLongOrNull("createdAt"),
                importedAt = r.optLongOrNull("importedAt"),
                source = r.optJSONObject("source")?.toStringMap().orEmpty(),
                verifiedAt = r.optLongOrNull("verifiedAt"),
                verifiedSerial = r.stringOrNull("verifiedSerial")
            )
        }
        val availabilityObject = json.optJSONObject("availability")
        val activeGeneration = json.stringOrNull("activeGeneration")
            ?: parsed.singleOrNull()?.generation?.takeIf { version == 1 }
        val availability = availabilityObject?.let { value ->
            val causes = value.getJSONArray("causes")
            PumpSession.Availability(
                causes = (0 until causes.length()).map { PumpSession.AvailabilityCause.valueOf(causes.getString(it)) }.toSet(),
                since = value.getLong("since"),
                code = value.optIntOrNull("code"),
                operation = value.stringOrNull("operation"),
                firmware = value.stringOrNull("firmware"),
                failures = value.getInt("failures"),
                retryAt = value.optLongOrNull("retryAt")
            )
        } ?: if (parsed.isEmpty()) PumpSession.Availability() else PumpSession.Availability(
            causes = setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE),
            since = 0
        )
        return PumpSession.State(
            records = parsed,
            activeGeneration = activeGeneration,
            availability = availability,
            candidateGeneration = json.stringOrNull("candidateGeneration"),
            candidateReplacesGeneration = json.stringOrNull("candidateReplacesGeneration"),
            candidateAttemptId = json.stringOrNull("candidateAttemptId"),
            lastAttempt = json.optJSONObject("lastAttempt")?.let {
                PumpSession.AttemptResult(it.getString("id"), PumpSession.AttemptStatus.valueOf(it.getString("status")))
            },
            candidateAvailability = json.optJSONObject("candidateAvailability")?.let { value ->
                val causes = value.getJSONArray("causes")
                PumpSession.Availability(
                    causes = (0 until causes.length()).map { PumpSession.AvailabilityCause.valueOf(causes.getString(it)) }.toSet(),
                    since = value.getLong("since"), code = value.optIntOrNull("code"), operation = value.stringOrNull("operation"),
                    firmware = value.stringOrNull("firmware"), failures = value.getInt("failures"), retryAt = value.optLongOrNull("retryAt")
                )
            }
        ).also(PumpSession::validate)
    }

    override fun commit(state: PumpSession.State) {
        PumpSession.validate(state)
        val records = JSONArray()
        state.records.forEach { r ->
            records.put(JSONObject().put("pump", r.pump).put("key", r.keyId).put("generation", r.generation)
                .put("reboot", r.reboot ?: JSONObject.NULL).put("read", r.read ?: JSONObject.NULL).put("write", r.write ?: JSONObject.NULL)
                .put("reservation", r.reservation?.let {
                    JSONObject().put("id", it.id).put("counter", it.counter).put("phase", it.phase.name)
                } ?: JSONObject.NULL)
                .put("serial", r.serial).put("keyHex", r.keyHex ?: JSONObject.NULL)
                .put("createdAt", r.createdAt ?: JSONObject.NULL).put("importedAt", r.importedAt ?: JSONObject.NULL)
                .put("source", JSONObject(r.source)).put("verifiedAt", r.verifiedAt ?: JSONObject.NULL)
                .put("verifiedSerial", r.verifiedSerial ?: JSONObject.NULL))
        }
        val availability = JSONObject()
            .put("causes", JSONArray(state.availability.causes.map { it.name }))
            .put("since", state.availability.since).put("code", state.availability.code ?: JSONObject.NULL)
            .put("operation", state.availability.operation ?: JSONObject.NULL).put("firmware", state.availability.firmware ?: JSONObject.NULL)
            .put("failures", state.availability.failures).put("retryAt", state.availability.retryAt ?: JSONObject.NULL)
        fun availabilityJson(value: PumpSession.Availability) = JSONObject()
            .put("causes", JSONArray(value.causes.map { it.name })).put("since", value.since)
            .put("code", value.code ?: JSONObject.NULL).put("operation", value.operation ?: JSONObject.NULL)
            .put("firmware", value.firmware ?: JSONObject.NULL).put("failures", value.failures)
            .put("retryAt", value.retryAt ?: JSONObject.NULL)
        val body = JSONObject().put("version", 3).put("records", records)
            .put("activeGeneration", state.activeGeneration ?: JSONObject.NULL).put("availability", availability)
            .put("candidateGeneration", state.candidateGeneration ?: JSONObject.NULL)
            .put("candidateReplacesGeneration", state.candidateReplacesGeneration ?: JSONObject.NULL)
            .put("candidateAttemptId", state.candidateAttemptId ?: JSONObject.NULL)
            .put("lastAttempt", state.lastAttempt?.let { JSONObject().put("id", it.id).put("status", it.status.name) } ?: JSONObject.NULL)
            .put("candidateAvailability", state.candidateAvailability?.let(::availabilityJson) ?: JSONObject.NULL).toString()
        val old = storage.anchors()
        val alias = PREFIX + UUID.randomUUID()
        try {
            storage.create(alias)
            val envelope = JSONObject().put("anchor", alias).put("sealed", storage.seal(alias, body)).toString()
            // Invalidate every previous revision before the commit can become observable.
            old.forEach(storage::delete)
            storage.writeAndSync(envelope)
        } catch (e: Exception) {
            // If failure happened before the old anchors were invalidated, keep the old revision usable
            // instead of leaving an extra orphan alias that makes a truthful load look restored/corrupt.
            runCatching {
                if (storage.anchors().any { it in old }) storage.delete(alias)
            }
            throw e
        }
    }

    internal class AndroidStorage(context: Context, private val checkpoint: (String) -> Unit = {}) : Storage {
        private val file = File(context.noBackupFilesDir, "ypso-session.json")
        private val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        override fun read(): String? = if (file.exists()) file.readText() else null
        override fun anchors(): List<String> = keys.aliases().toList().filter { it.startsWith(PREFIX) }
        override fun create(alias: String) {
            checkpoint("before-create")
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
            checkpoint("after-create")
        }
        override fun delete(alias: String) {
            checkpoint("before-delete")
            keys.deleteEntry(alias)
            checkpoint("after-delete")
        }
        override fun seal(alias: String, body: String): String = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, keys.getKey(alias, null) as SecretKey)
            Base64.getEncoder().encodeToString(iv + doFinal(body.toByteArray(Charsets.UTF_8)))
        }
        override fun open(alias: String, sealed: String): String = Cipher.getInstance("AES/GCM/NoPadding").run {
            val bytes = Base64.getDecoder().decode(sealed)
            require(bytes.size > IV_SIZE)
            init(Cipher.DECRYPT_MODE, keys.getKey(alias, null) as SecretKey, GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
            doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)).toString(Charsets.UTF_8)
        }
        override fun authenticateLegacy(alias: String, body: String): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(keys.getKey(alias, null) as SecretKey)
            doFinal(body.toByteArray(Charsets.UTF_8))
        }
        override fun writeAndSync(value: String) {
            checkpoint("before-truncate")
            FileOutputStream(file).use { out ->
                checkpoint("after-truncate")
                val bytes = value.toByteArray(Charsets.UTF_8)
                val middle = bytes.size / 2
                out.write(bytes, 0, middle)
                checkpoint("partial-write")
                out.write(bytes, middle, bytes.size - middle)
                checkpoint("before-sync")
                out.fd.sync()
                checkpoint("after-sync")
            }
        }
    }

    private fun JSONObject.optLongOrNull(name: String): Long? = if (isNull(name)) null else getLong(name)
    private fun JSONObject.optIntOrNull(name: String): Int? = if (isNull(name)) null else getInt(name)
    private fun JSONObject.stringOrNull(name: String): String? = if (!has(name) || isNull(name)) null else getString(name).takeIf(String::isNotEmpty)
    private fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith(::getString)

    companion object {
        private const val PREFIX = "ypso.session.revision."
        private const val IV_SIZE = 12
    }
}
