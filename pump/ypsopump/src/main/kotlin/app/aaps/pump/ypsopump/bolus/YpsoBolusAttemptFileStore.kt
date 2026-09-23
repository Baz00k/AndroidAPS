package app.aaps.pump.ypsopump.bolus

import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Atomic no-backup storage adapter. The caller must place [file] under Android's noBackupFilesDir. */
class YpsoBolusAttemptFileStore(private val file: File) : YpsoBolusAttemptStore {
    internal data class RecoveryEvidence(val attempts: List<YpsoBolusAttempt>, val bytes: ByteArray) {
        constructor(attempt: YpsoBolusAttempt, bytes: ByteArray) : this(listOf(attempt), bytes)

        val attempt: YpsoBolusAttempt
            get() = attempts.maxWithOrNull(
                compareBy<YpsoBolusAttempt> { maxOf(it.dispatchCounter ?: -1L, it.cancelCounter ?: -1L) }
                    .thenBy { it.createdAt },
            ) ?: error("bolus recovery evidence is empty")
    }

    /** Decode and hash callers' evidence from one immutable read, avoiding a file-change race. */
    internal fun recoveryEvidence(): RecoveryEvidence? {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        return try {
            RecoveryEvidence(decodeAll(JSONObject(bytes.toString(Charsets.UTF_8))), bytes)
        } catch (error: Exception) {
            bytes.fill(0)
            throw error
        }
    }

    override fun load(): YpsoBolusAttempt? {
        if (!file.exists()) return null
        return loadAll().lastOrNull()
    }

    internal fun loadAll(): List<YpsoBolusAttempt> {
        if (!file.exists()) return emptyList()
        return decodeAll(JSONObject(file.readText()))
    }

    override fun commit(attempt: YpsoBolusAttempt) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.next")
        val prior = loadAll()
        val matching = prior.indexOfFirst { it.requestId == attempt.requestId }
        val attempts = if (matching >= 0) prior.toMutableList().also { it[matching] = attempt } else prior + attempt
        val bytes = JSONObject()
            .put("version", 7)
            .put("attempts", JSONArray().also { array -> attempts.forEach { array.put(encode(it)) } })
            .toString()
            .toByteArray(Charsets.UTF_8)
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "cannot atomically replace bolus journal" }
        fsyncParentDirectory()
    }

    /** Make the replaced directory entry itself durable, not just the temporary file contents. */
    private fun fsyncParentDirectory() {
        val directory = file.parentFile ?: return
        java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    }

    private fun encode(value: YpsoBolusAttempt): JSONObject = JSONObject()
        .put("version", 7)
        .put("requestId", value.requestId)
        .put("pumpSerial", value.pumpSerial)
        .put("sessionGeneration", value.sessionGeneration)
        .putNullable("sessionKeyId", value.sessionKeyId)
        .put("treatment", value.treatment.name)
        .put("shape", value.shape.name)
        .put("durationMinutes", value.durationMinutes)
        .put("immediateCentiUnits", value.immediateCentiUnits)
        .put("requestedCentiUnits", value.requestedCentiUnits)
        .put("payloadHash", value.payloadHash)
        .put("createdAt", value.createdAt)
        .put("outcome", value.outcome.name)
        .putNullable("dispatchCounter", value.dispatchCounter)
        .putNullable("dispatchedAt", value.dispatchedAt)
        .putNullable("pumpFastSequence", value.pumpFastSequence)
        .putNullable("pumpSlowSequence", value.pumpSlowSequence)
        .putNullable("pumpHistoryId", value.pumpHistoryId)
        .putNullable("confirmedCentiUnits", value.confirmedCentiUnits)
        .putNullable("deliveryTimestamp", value.deliveryTimestamp)
        .putNullable("cancelRequestId", value.cancelRequestId)
        .putNullable("cancelCounter", value.cancelCounter)
        .putNullable("cancelBlock", value.cancelBlock?.name)
        .putNullable("cancelObservedCentiUnits", value.cancelObservedCentiUnits)
        .putNullable("cancelStoppedAt", value.cancelStoppedAt)
        .putNullable("cancelDispatchedAt", value.cancelDispatchedAt)
        .putNullable("blockTerminalAt", value.blockTerminalAt)
        .putNullable("detail", value.detail)
        .put(
            "baseline",
            JSONObject()
                .put("fastSequence", value.baseline.fastSequence)
                .put("slowSequence", value.baseline.slowSequence)
                .put("historyPumpId", value.baseline.historyPumpId)
                .put("historyFingerprintHigh", value.baseline.historyFingerprintHigh)
                .put("historyFingerprintLow", value.baseline.historyFingerprintLow)
                .put("pumpReboot", value.baseline.pumpReboot)
                .put("observedAt", value.baseline.observedAt),
        )

    private fun decode(json: JSONObject): YpsoBolusAttempt {
        val version = json.getInt("version")
        require(version in 2..7) { "unsupported bolus journal version" }
        require(json.keys().asSequence().toSet() == rootFields(version)) { "unexpected bolus journal fields" }
        val baseline = json.getJSONObject("baseline")
        require(baseline.keys().asSequence().toSet() == BASELINE_FIELDS)
        val cancelRequestId = json.stringOrNull("cancelRequestId")
        return YpsoBolusAttempt(
            requestId = json.getString("requestId"),
            pumpSerial = json.getString("pumpSerial"),
            sessionGeneration = json.getString("sessionGeneration"),
            sessionKeyId = if (version >= 4) json.stringOrNull("sessionKeyId") else null,
            treatment = YpsoBolusTreatment.valueOf(json.getString("treatment")),
            requestedCentiUnits = json.getInt("requestedCentiUnits"),
            payloadHash = json.getString("payloadHash"),
            baseline = YpsoBolusBaseline(
                fastSequence = baseline.getLong("fastSequence"),
                slowSequence = baseline.getLong("slowSequence"),
                historyPumpId = baseline.getLong("historyPumpId"),
                historyFingerprintHigh = baseline.getLong("historyFingerprintHigh"),
                historyFingerprintLow = baseline.getLong("historyFingerprintLow"),
                pumpReboot = baseline.getInt("pumpReboot"),
                observedAt = baseline.getLong("observedAt"),
            ),
            createdAt = json.getLong("createdAt"),
            shape = if (version >= 3) YpsoBolusShape.valueOf(json.getString("shape")) else YpsoBolusShape.IMMEDIATE,
            durationMinutes = if (version >= 3) json.getInt("durationMinutes") else 0,
            immediateCentiUnits = if (version >= 3) json.getInt("immediateCentiUnits") else 0,
            outcome = YpsoBolusOutcome.valueOf(json.getString("outcome")),
            dispatchCounter = json.longOrNull("dispatchCounter"),
            dispatchedAt = json.longOrNull("dispatchedAt"),
            pumpFastSequence = json.longOrNull("pumpFastSequence"),
            pumpSlowSequence = if (version >= 3) json.longOrNull("pumpSlowSequence") else null,
            pumpHistoryId = json.longOrNull("pumpHistoryId"),
            confirmedCentiUnits = json.intOrNull("confirmedCentiUnits"),
            deliveryTimestamp = json.longOrNull("deliveryTimestamp"),
            cancelRequestId = cancelRequestId,
            cancelCounter = json.longOrNull("cancelCounter"),
            // Version 2 only ever carried immediate attempts, so its cancellation ownership migrates
            // to the fast block; version 3 records the block explicitly.
            cancelBlock = if (version >= 3) json.stringOrNull("cancelBlock")?.let(YpsoBolusBlock::valueOf)
            else if (cancelRequestId != null) YpsoBolusBlock.FAST else null,
            cancelObservedCentiUnits = if (version >= 3) json.intOrNull("cancelObservedCentiUnits") else null,
            cancelStoppedAt = if (version >= 5) json.longOrNull("cancelStoppedAt") else null,
            cancelDispatchedAt = if (version >= 6) json.longOrNull("cancelDispatchedAt") else null,
            blockTerminalAt = if (version >= 7) json.longOrNull("blockTerminalAt") else null,
            detail = json.stringOrNull("detail"),
        )
    }

    private fun decodeAll(json: JSONObject): List<YpsoBolusAttempt> {
        if (json.optInt("version", -1) !in 5..7 || !json.has("attempts")) return listOf(decode(json))
        require(json.keys().asSequence().toSet() == setOf("version", "attempts")) { "unexpected bolus journal fields" }
        val attempts = json.getJSONArray("attempts")
        require(attempts.length() > 0) { "bolus journal is empty" }
        return (0 until attempts.length()).map { decode(attempts.getJSONObject(it)) }.also { values ->
            require(values.map(YpsoBolusAttempt::requestId).distinct().size == values.size) {
                "duplicate bolus request identity"
            }
        }
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)
    private fun JSONObject.longOrNull(name: String): Long? = if (isNull(name)) null else getLong(name)
    private fun JSONObject.intOrNull(name: String): Int? = if (isNull(name)) null else getInt(name)
    private fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else getString(name)

    companion object {
        private val VERSION_2_FIELDS = setOf(
            "version", "requestId", "pumpSerial", "sessionGeneration", "treatment", "requestedCentiUnits",
            "payloadHash", "baseline", "createdAt", "outcome", "dispatchCounter", "dispatchedAt",
            "pumpFastSequence", "pumpHistoryId", "confirmedCentiUnits", "deliveryTimestamp", "cancelRequestId",
            "cancelCounter", "detail",
        )
        private val VERSION_3_FIELDS = VERSION_2_FIELDS + setOf(
            "shape", "durationMinutes", "immediateCentiUnits", "pumpSlowSequence",
            "cancelBlock", "cancelObservedCentiUnits",
        )
        private val VERSION_4_FIELDS = VERSION_3_FIELDS + "sessionKeyId"
        private val VERSION_5_FIELDS = VERSION_4_FIELDS + "cancelStoppedAt"
        private val VERSION_6_FIELDS = VERSION_5_FIELDS + "cancelDispatchedAt"
        private val VERSION_7_FIELDS = VERSION_6_FIELDS + "blockTerminalAt"
        private val BASELINE_FIELDS = setOf(
            "fastSequence", "slowSequence", "historyPumpId", "historyFingerprintHigh", "historyFingerprintLow",
            "pumpReboot", "observedAt",
        )

        private fun rootFields(version: Int) = when (version) {
            2 -> VERSION_2_FIELDS
            3 -> VERSION_3_FIELDS
            4 -> VERSION_4_FIELDS
            5 -> VERSION_5_FIELDS
            6 -> VERSION_6_FIELDS
            else -> VERSION_7_FIELDS
        }
    }
}
