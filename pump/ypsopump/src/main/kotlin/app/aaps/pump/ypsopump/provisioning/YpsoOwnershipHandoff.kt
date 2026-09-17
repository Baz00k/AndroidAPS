package app.aaps.pump.ypsopump.provisioning

import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionJournal
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Portable, secret-free transfer of one reviewed replay-accounting record. The payload is protected
 * with the already-installed pump session key and binds the exact bench journal/evidence artifacts.
 */
internal object YpsoOwnershipHandoff {
    const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024

    data class SourceArtifacts(
        val packageName: String,
        val apkSha256: String,
        val signerSha256: String,
        val journalSha256: String,
        val evidenceSha256: String,
        val historySha256: String,
        val profileSha256: String,
    )

    data class Reviewed(
        val record: PumpSession.Record,
        val createdAt: Long,
        val reviewedEvidenceSha256: String,
        val source: SourceArtifacts,
        val documentSha256: String,
    )

    fun encode(
        record: PumpSession.Record,
        sharedKey: ByteArray,
        createdAt: Long,
        reviewedEvidenceSha256: String,
        source: SourceArtifacts,
    ): ByteArray {
        require(sharedKey.size == 32 && sharedKey.any { it.toInt() != 0 })
        requireHash(reviewedEvidenceSha256)
        requireSource(source)
        require(record.serial.isNotBlank()) { "Ownership handoff requires the canonical pump serial" }
        require(record.reboot != null && record.read != null && record.write != null)
        require(record.writeBootstrapState == PumpSession.WriteBootstrapState.ESTABLISHED)
        require(record.reservation?.phase == PumpSession.Phase.VERIFIED)
        val portable = record.copy(keyHex = null)
        val stateBody = encodeRecord(portable)
        val payload =
            JSONObject()
                .put("schema_version", 1)
                .put("kind", "aaps-ypsopump-ownership-handoff")
                .put("created_at_ms", createdAt)
                .put("reviewed_evidence_sha256", reviewedEvidenceSha256)
                .put(
                    "source",
                    JSONObject()
                        .put("package", source.packageName)
                        .put("apk_sha256", source.apkSha256)
                        .put("signer_sha256", source.signerSha256)
                        .put("journal_sha256", source.journalSha256)
                        .put("evidence_sha256", source.evidenceSha256)
                        .put("history_sha256", source.historySha256)
                        .put("profile_sha256", source.profileSha256),
                )
                .put("state_body_base64", Base64.getEncoder().encodeToString(stateBody))
                .toString()
                .toByteArray(Charsets.UTF_8)
        val payloadHash = hash(payload)
        val authentication = hmac(sharedKey, payload)
        return JSONObject()
            .put("schema_version", 1)
            .put("payload_base64", Base64.getEncoder().encodeToString(payload))
            .put("payload_sha256", payloadHash)
            .put("hmac_sha256", authentication)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun parse(document: ByteArray, sharedKey: ByteArray): Reviewed {
        require(document.isNotEmpty() && document.size <= MAX_DOCUMENT_BYTES)
        require(sharedKey.size == 32 && sharedKey.any { it.toInt() != 0 })
        val root = JSONObject(document.toString(Charsets.UTF_8))
        require(root.keys().asSequence().toSet() == setOf("schema_version", "payload_base64", "payload_sha256", "hmac_sha256"))
        require(root.getInt("schema_version") == 1)
        val payload = Base64.getDecoder().decode(root.getString("payload_base64"))
        require(payload.isNotEmpty() && payload.size <= MAX_DOCUMENT_BYTES)
        requireHash(root.getString("payload_sha256"))
        requireHash(root.getString("hmac_sha256"))
        require(MessageDigest.isEqual(hash(payload).hexBytes(), root.getString("payload_sha256").hexBytes())) {
            "Ownership handoff payload hash does not match"
        }
        require(MessageDigest.isEqual(hmac(sharedKey, payload).hexBytes(), root.getString("hmac_sha256").hexBytes())) {
            "Ownership handoff authentication failed"
        }
        val value = JSONObject(payload.toString(Charsets.UTF_8))
        require(
            value.keys().asSequence().toSet() ==
                setOf("schema_version", "kind", "created_at_ms", "reviewed_evidence_sha256", "source", "state_body_base64"),
        )
        require(value.getInt("schema_version") == 1 && value.getString("kind") == "aaps-ypsopump-ownership-handoff")
        val reviewedHash = value.getString("reviewed_evidence_sha256").also(::requireHash)
        val sourceJson = value.getJSONObject("source")
        require(
            sourceJson.keys().asSequence().toSet() ==
                setOf("package", "apk_sha256", "signer_sha256", "journal_sha256", "evidence_sha256", "history_sha256", "profile_sha256"),
        )
        val source =
            SourceArtifacts(
                packageName = sourceJson.getString("package"),
                apkSha256 = sourceJson.getString("apk_sha256"),
                signerSha256 = sourceJson.getString("signer_sha256"),
                journalSha256 = sourceJson.getString("journal_sha256"),
                evidenceSha256 = sourceJson.getString("evidence_sha256"),
                historySha256 = sourceJson.getString("history_sha256"),
                profileSha256 = sourceJson.getString("profile_sha256"),
            ).also(::requireSource)
        val stateBody = Base64.getDecoder().decode(value.getString("state_body_base64"))
        require(stateBody.isNotEmpty() && stateBody.size <= MAX_DOCUMENT_BYTES)
        val record = decodeRecord(stateBody)
        require(record.reboot != null && record.read != null && record.write != null)
        require(record.serial.isNotBlank()) { "Ownership handoff has no canonical pump serial" }
        require(record.writeBootstrapState == PumpSession.WriteBootstrapState.ESTABLISHED)
        require(record.reservation?.phase == PumpSession.Phase.VERIFIED)
        require(record.keyHex == null) { "Ownership handoff must not contain the pump key" }
        return Reviewed(record, value.getLong("created_at_ms"), reviewedHash, source, hash(document))
    }

    private fun encodeRecord(record: PumpSession.Record): ByteArray {
        val storage = PortableStorage()
        SessionJournal(storage).commit(
            PumpSession.State(
                records = listOf(record),
                activeGeneration = record.generation,
                availability = PumpSession.Availability(emptySet()),
            ),
        )
        return storage.plaintextBody()
    }

    private fun decodeRecord(body: ByteArray): PumpSession.Record {
        val storage = PortableStorage(body)
        val state = SessionJournal(storage).load()
        require(state.candidateGeneration == null && state.records.size == 1)
        return state.records.single().also { require(state.activeGeneration == it.generation) }
    }

    private class PortableStorage(body: ByteArray? = null) : SessionJournal.Storage {
        private var alias: String? = body?.let { "portable" }
        private var contents: String? = body?.let {
            JSONObject()
                .put("anchor", "portable")
                .put("sealed", Base64.getEncoder().encodeToString(it))
                .toString()
        }

        override fun read(): String? = contents
        override fun anchors(): List<String> = listOfNotNull(alias)
        override fun create(alias: String) { require(this.alias == null); this.alias = alias }
        override fun delete(alias: String) { if (this.alias == alias) this.alias = null }
        override fun seal(alias: String, body: String): String {
            require(alias == this.alias)
            return Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        }
        override fun open(alias: String, sealed: String): String {
            require(alias == this.alias)
            return Base64.getDecoder().decode(sealed).toString(Charsets.UTF_8)
        }
        override fun authenticateLegacy(alias: String, body: String): ByteArray = error("Legacy portable journal unsupported")
        override fun writeAndSync(value: String) { contents = value }
        fun plaintextBody(): ByteArray {
            val envelope = JSONObject(checkNotNull(contents))
            return Base64.getDecoder().decode(envelope.getString("sealed"))
        }
    }

    private fun requireSource(source: SourceArtifacts) {
        require(source.packageName == "app.aaps.ypso.writebench") { "Ownership handoff source package is not the reviewed bench" }
        listOf(
            source.apkSha256,
            source.signerSha256,
            source.journalSha256,
            source.evidenceSha256,
            source.historySha256,
            source.profileSha256,
        ).forEach(::requireHash)
    }

    private fun requireHash(value: String) = require(value.matches(Regex("[0-9a-f]{64}"))) { "Expected lowercase SHA-256" }
    private fun hash(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).toHex()
    private fun hmac(key: ByteArray, value: ByteArray): String =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value).toHex()
        }
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
