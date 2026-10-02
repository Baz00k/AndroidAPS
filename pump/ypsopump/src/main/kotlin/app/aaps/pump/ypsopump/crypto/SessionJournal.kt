package app.aaps.pump.ypsopump.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Journal revisions are authenticated by unique, non-exportable Keystore keys. A new sealed
 * revision is first published in a transition containing both the prior and next sealed envelopes.
 * The prior revision remains authoritative while its key exists; deleting that exact key atomically
 * makes the next revision authoritative. Process termination therefore leaves one decryptable,
 * unambiguous replay floor. The file is excluded from Android backup.
 */
class SessionJournal internal constructor(
    private val storage: Storage,
    private val onSlowCommit: (Long, Int, Int) -> Unit = { _, _, _ -> },
) : PumpSession.Store {

    constructor(context: Context) : this(AndroidStorage(context))
    constructor(context: Context, onSlowCommit: (Long, Int, Int) -> Unit) : this(AndroidStorage(context), onSlowCommit)
    private var bodyBytes = 0

    internal interface Storage {
        fun read(): String?
        fun anchors(): List<String>
        fun create(alias: String)
        fun delete(alias: String)
        fun seal(alias: String, body: String): String
        fun open(alias: String, sealed: String): String
        fun writeAndSync(value: String)
    }


    override fun load(): PumpSession.State {
        val anchors = storage.anchors()
        val contents = storage.read()
        if (contents == null && anchors.isEmpty()) return PumpSession.State()
        val envelope = authoritativeEnvelope(JSONObject(checkNotNull(contents)), anchors)
        val alias = envelope.getString("anchor")
        // Extra orphan keys are harmless. A missing authoritative key rejects rollback and restored revisions.
        if (alias !in anchors) throw AnchorMismatch(anchors.size, false)
        val json = JSONObject(storage.open(alias, envelope.getString("sealed")))
        val version = json.getInt("version")
        check(version == VERSION) { "Unsupported session journal version $version" }
        val records = json.getJSONArray("records")
        val parsed = (0 until records.length()).map { index ->
            val r = records.getJSONObject(index)
            PumpSession.Record(
                pump = r.getString("pump"),
                keyId = r.getString("key"),
                generation = r.getString("generation"),
                reboot = r.optIntOrNull("reboot"),
                read = r.optLongOrNull("read"),
                write = r.optLongOrNull("write"),
                reservation = r.optJSONObject("reservation")?.let { reservation(it) },
                serial = r.stringOrNull("serial").orEmpty(),
                keyHex = r.stringOrNull("keyHex"),
                createdAt = r.optLongOrNull("createdAt"),
                importedAt = r.optLongOrNull("importedAt"),
                source = r.optJSONObject("source")?.toStringMap().orEmpty(),
                verifiedAt = r.optLongOrNull("verifiedAt"),
                verifiedSerial = r.stringOrNull("verifiedSerial"),
                writeEvidence = r.getJSONArray("writeEvidence").let { values ->
                    (0 until values.length()).map { evidenceIndex -> writeEvidence(values.getJSONObject(evidenceIndex)) }
                },
                writeBootstrapState = PumpSession.WriteBootstrapState.valueOf(r.getString("writeBootstrapState")),
                counterRecoveryExponent = r.getInt("counterRecoveryExponent"),
                lowerBoundRecoveryReboot = r.optIntOrNull("lowerBoundRecoveryReboot"),
            )
        }
        return PumpSession.State(
            records = parsed,
            activeGeneration = json.stringOrNull("activeGeneration"),
            availability = availability(json.getJSONObject("availability")),
            candidateGeneration = json.stringOrNull("candidateGeneration"),
            candidateReplacesGeneration = json.stringOrNull("candidateReplacesGeneration"),
            candidateAttemptId = json.stringOrNull("candidateAttemptId"),
            lastAttempt = json.optJSONObject("lastAttempt")?.let {
                PumpSession.AttemptResult(it.getString("id"), PumpSession.AttemptStatus.valueOf(it.getString("status")))
            },
            candidateAvailability = json.optJSONObject("candidateAvailability")?.let(::availability),
        ).also(PumpSession::validate)
    }

    private fun reservation(value: JSONObject): PumpSession.Reservation {
        return PumpSession.Reservation(
            id = value.getString("id"),
            counter = value.getLong("counter"),
            phase = PumpSession.Phase.valueOf(value.getString("phase")),
            operationId = value.stringOrNull("operationId"),
            characteristic = value.stringOrNull("characteristic"),
            purpose = value.stringOrNull("purpose"),
            payloadHash = value.stringOrNull("payloadHash"),
            priorWrite = value.getLong("priorWrite"),
            candidate = PumpSession.WriteCandidate.valueOf(value.getString("candidate")),
        )
    }

    private fun writeEvidence(value: JSONObject): PumpSession.WriteEvidence {
        return PumpSession.WriteEvidence(
            operationId = value.getString("operationId"),
            reservationId = value.getString("reservationId"),
            counter = value.getLong("counter"),
            characteristic = value.getString("characteristic"),
            purpose = value.getString("purpose"),
            payloadHash = value.getString("payloadHash"),
            priorWrite = value.getLong("priorWrite"),
            candidate = PumpSession.WriteCandidate.valueOf(value.getString("candidate")),
            resolution = value.stringOrNull("resolution")?.let(PumpSession.WriteResolution::valueOf),
            evidenceHash = value.getString("evidenceHash"),
            detail = value.getString("detail"),
        )
    }

    private fun availability(value: JSONObject): PumpSession.Availability {
        val causes = value.getJSONArray("causes")
        return PumpSession.Availability(
            causes = (0 until causes.length()).map { PumpSession.AvailabilityCause.valueOf(causes.getString(it)) }.toSet(),
            since = value.getLong("since"),
            code = value.optIntOrNull("code"),
            operation = value.stringOrNull("operation"),
            firmware = value.stringOrNull("firmware"),
            failures = value.getInt("failures"),
            retryAt = value.optLongOrNull("retryAt"),
        )
    }

    private fun availabilityJson(value: PumpSession.Availability) = JSONObject()
        .put("causes", JSONArray(value.causes.map { it.name })).put("since", value.since)
        .put("code", value.code ?: JSONObject.NULL).put("operation", value.operation ?: JSONObject.NULL)
        .put("firmware", value.firmware ?: JSONObject.NULL).put("failures", value.failures)
        .put("retryAt", value.retryAt ?: JSONObject.NULL)

    private fun reservationJson(value: PumpSession.Reservation) =
        JSONObject().put("id", value.id).put("counter", value.counter).put("phase", value.phase.name)
            .put("operationId", value.operationId ?: JSONObject.NULL)
            .put("characteristic", value.characteristic ?: JSONObject.NULL)
            .put("purpose", value.purpose ?: JSONObject.NULL)
            .put("payloadHash", value.payloadHash ?: JSONObject.NULL)
            .put("priorWrite", value.priorWrite)
            .put("candidate", value.candidate.name)

    override fun commit(state: PumpSession.State) {
        val started = System.nanoTime()
        try {
            writeState(state, replaceUnavailable = false)
        } finally {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            if (elapsedMs >= 500) runCatching { onSlowCommit(elapsedMs, bodyBytes, state.records.sumOf { it.writeEvidence.size }) }
        }
    }

    override fun replaceUnavailable(state: PumpSession.State) {
        runCatching { load() }.onSuccess { error("Session journal is available") }
        writeState(state, replaceUnavailable = true)
    }

    private fun writeState(state: PumpSession.State, replaceUnavailable: Boolean) {
        PumpSession.validate(state)
        val records = JSONArray()
        state.records.forEach { r ->
            records.put(JSONObject().put("pump", r.pump).put("key", r.keyId).put("generation", r.generation)
                .put("reboot", r.reboot ?: JSONObject.NULL).put("read", r.read ?: JSONObject.NULL).put("write", r.write ?: JSONObject.NULL)
                .put("reservation", r.reservation?.let(::reservationJson) ?: JSONObject.NULL)
                .put("serial", r.serial).put("keyHex", r.keyHex ?: JSONObject.NULL)
                .put("createdAt", r.createdAt ?: JSONObject.NULL).put("importedAt", r.importedAt ?: JSONObject.NULL)
                .put("source", JSONObject(r.source)).put("verifiedAt", r.verifiedAt ?: JSONObject.NULL)
                .put("verifiedSerial", r.verifiedSerial ?: JSONObject.NULL)
                .put("writeBootstrapState", r.writeBootstrapState.name)
                .put("counterRecoveryExponent", r.counterRecoveryExponent)
                .put("lowerBoundRecoveryReboot", r.lowerBoundRecoveryReboot ?: JSONObject.NULL)
                .put("writeEvidence", JSONArray(r.writeEvidence.map { evidence ->
                    JSONObject()
                        .put("operationId", evidence.operationId)
                        .put("reservationId", evidence.reservationId)
                        .put("counter", evidence.counter)
                        .put("characteristic", evidence.characteristic)
                        .put("purpose", evidence.purpose)
                        .put("payloadHash", evidence.payloadHash)
                        .put("priorWrite", evidence.priorWrite)
                        .put("candidate", evidence.candidate.name)
                        .put("resolution", evidence.resolution?.name ?: JSONObject.NULL)
                        .put("evidenceHash", evidence.evidenceHash)
                        .put("detail", evidence.detail)
                })))
        }
        val body = JSONObject().put("version", VERSION).put("records", records)
            .put("activeGeneration", state.activeGeneration ?: JSONObject.NULL).put("availability", availabilityJson(state.availability))
            .put("candidateGeneration", state.candidateGeneration ?: JSONObject.NULL)
            .put("candidateReplacesGeneration", state.candidateReplacesGeneration ?: JSONObject.NULL)
            .put("candidateAttemptId", state.candidateAttemptId ?: JSONObject.NULL)
            .put("lastAttempt", state.lastAttempt?.let { JSONObject().put("id", it.id).put("status", it.status.name) } ?: JSONObject.NULL)
            .put("candidateAvailability", state.candidateAvailability?.let(::availabilityJson) ?: JSONObject.NULL).toString()
        bodyBytes = body.toByteArray(Charsets.UTF_8).size
        val old = storage.anchors()
        val alias = PREFIX + UUID.randomUUID()
        try {
            storage.create(alias)
            val nextEnvelope = JSONObject().put("anchor", alias).put("sealed", storage.seal(alias, body))
            if (replaceUnavailable) {
                // Recovery starts from a journal that is already unavailable. Retire every stale
                // anchor before publishing so a restored old file cannot authenticate during a
                // crash window. Interruption remains unavailable and the reviewed operation can be
                // retried; it can never expose an older replay floor.
                old.forEach(storage::delete)
                storage.writeAndSync(nextEnvelope.toString())
                return
            }
            val currentContents = storage.read()
            if (currentContents == null && old.isEmpty()) {
                storage.writeAndSync(nextEnvelope.toString())
                return
            }
            val priorEnvelope = authoritativeEnvelope(JSONObject(checkNotNull(currentContents)), old)
            val priorAlias = priorEnvelope.getString("anchor")
            val transition = JSONObject()
                .put("transitionVersion", TRANSITION_VERSION)
                .put("prior", priorEnvelope)
                .put("next", nextEnvelope)
            // Until priorAlias is deleted, load() deterministically selects prior. Once it is
            // deleted, the same durable file deterministically selects next.
            storage.writeAndSync(transition.toString())
            // Remove every older orphan before switching authority. Otherwise restoring a stale
            // envelope whose orphan key survived could roll the replay floor backwards.
            old.filterNot { it == priorAlias }.forEach(storage::delete)
            storage.delete(priorAlias)
            // The transition now resolves to next. Finalization and orphan cleanup are optional.
            runCatching { storage.writeAndSync(nextEnvelope.toString()) }
        } catch (e: Exception) {
            // Remove the candidate only if the currently durable envelope still resolves to prior.
            // If priorAlias was deleted, the transition already makes this candidate authoritative.
            runCatching {
                val anchors = storage.anchors()
                val authoritativeAlias = storage.read()?.let {
                    authoritativeEnvelope(JSONObject(it), anchors).getString("anchor")
                }
                if (authoritativeAlias != alias && alias in anchors) storage.delete(alias)
            }
            throw e
        }
    }

    private fun authoritativeEnvelope(root: JSONObject, anchors: List<String>): JSONObject {
        if (root.optInt("transitionVersion", 0) != TRANSITION_VERSION) return root
        val prior = root.getJSONObject("prior")
        val next = root.getJSONObject("next")
        val priorAlias = prior.getString("anchor")
        val nextAlias = next.getString("anchor")
        return when {
            priorAlias in anchors -> prior
            nextAlias in anchors -> next
            else -> throw AnchorMismatch(anchors.size, false)
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
        override fun writeAndSync(value: String) {
            checkpoint("before-truncate")
            val temporary = File(file.parentFile, "${file.name}.new")
            FileOutputStream(temporary).use { out ->
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
            check(temporary.renameTo(file)) { "Unable to atomically publish session journal" }
            FileChannel.open(file.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun JSONObject.optLongOrNull(name: String): Long? = if (isNull(name)) null else getLong(name)
    private fun JSONObject.optIntOrNull(name: String): Int? = if (isNull(name)) null else getInt(name)
    private fun JSONObject.stringOrNull(name: String): String? = if (!has(name) || isNull(name)) null else getString(name).takeIf(String::isNotEmpty)
    private fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith(::getString)

    companion object {
        private const val PREFIX = "ypso.session.revision."
        private const val TRANSITION_VERSION = 1
        private const val IV_SIZE = 12
        private const val VERSION = 18
    }

    internal class AnchorMismatch(val count: Int, val containsCurrent: Boolean) : IllegalStateException("Incomplete or restored session journal")
}
