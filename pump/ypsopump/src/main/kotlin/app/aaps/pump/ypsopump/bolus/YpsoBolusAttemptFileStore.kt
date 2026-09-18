package app.aaps.pump.ypsopump.bolus

import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/** Atomic no-backup storage adapter. The caller must place [file] under Android's noBackupFilesDir. */
class YpsoBolusAttemptFileStore(private val file: File) : YpsoBolusAttemptStore {
    override fun load(): YpsoBolusAttempt? {
        if (!file.exists()) return null
        return decode(JSONObject(file.readText()))
    }

    override fun commit(attempt: YpsoBolusAttempt) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.next")
        val bytes = encode(attempt).toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "cannot atomically replace bolus journal" }
    }

    private fun encode(value: YpsoBolusAttempt): JSONObject = JSONObject()
        .put("version", 3)
        .put("requestId", value.requestId)
        .put("pumpSerial", value.pumpSerial)
        .put("sessionGeneration", value.sessionGeneration)
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
        require(version in 2..3) { "unsupported bolus journal version" }
        require(json.keys().asSequence().toSet() == rootFields(version)) { "unexpected bolus journal fields" }
        val baseline = json.getJSONObject("baseline")
        require(baseline.keys().asSequence().toSet() == BASELINE_FIELDS)
        return YpsoBolusAttempt(
            requestId = json.getString("requestId"),
            pumpSerial = json.getString("pumpSerial"),
            sessionGeneration = json.getString("sessionGeneration"),
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
            cancelRequestId = json.stringOrNull("cancelRequestId"),
            cancelCounter = json.longOrNull("cancelCounter"),
            cancelBlock = if (version >= 3) json.stringOrNull("cancelBlock")?.let(YpsoBolusBlock::valueOf) else null,
            cancelObservedCentiUnits = if (version >= 3) json.intOrNull("cancelObservedCentiUnits") else null,
            detail = json.stringOrNull("detail"),
        )
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
        private val BASELINE_FIELDS = setOf(
            "fastSequence", "slowSequence", "historyPumpId", "historyFingerprintHigh", "historyFingerprintLow",
            "pumpReboot", "observedAt",
        )

        private fun rootFields(version: Int) = if (version == 2) VERSION_2_FIELDS else VERSION_3_FIELDS
    }
}
