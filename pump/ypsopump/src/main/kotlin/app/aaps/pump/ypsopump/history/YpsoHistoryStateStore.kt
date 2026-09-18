package app.aaps.pump.ypsopump.history

import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

data class YpsoPendingBolusSync(
    val pumpSerial: String,
    val pumpId: Long,
    val timestamp: Long,
    val amountCentiUnits: Int,
    val eventSequence: Long,
) {
    init {
        require(pumpSerial.isNotBlank())
        require(timestamp > 0 && amountCentiUnits in 0..3000 && eventSequence in 0..0xffffffffL)
    }
}

data class YpsoHistoryState(
    val cursor: YpsoHistoryCursor? = null,
    val pendingBolus: YpsoPendingBolusSync? = null,
)

interface YpsoHistoryStateStore {
    fun load(): YpsoHistoryState
    fun commit(value: YpsoHistoryState)
}

class YpsoHistoryStateFileStore(private val file: File) : YpsoHistoryStateStore {
    override fun load(): YpsoHistoryState =
        if (!file.exists()) YpsoHistoryState() else decode(JSONObject(file.readText()))

    override fun commit(value: YpsoHistoryState) {
        file.parentFile?.mkdirs()
        val next = File(file.parentFile, "${file.name}.next")
        FileOutputStream(next).use { output ->
            output.write(encode(value).toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(next.renameTo(file)) { "cannot atomically replace history state" }
    }

    private fun encode(value: YpsoHistoryState): JSONObject = JSONObject()
        .put("version", 1)
        .put("cursor", value.cursor?.let(::encodeCursor) ?: JSONObject.NULL)
        .put("pendingBolus", value.pendingBolus?.let(::encodePending) ?: JSONObject.NULL)

    private fun encodeCursor(value: YpsoHistoryCursor): JSONObject = JSONObject()
        .put("serial", value.identity.pumpSerial)
        .put("generation", value.identity.sequenceGeneration)
        .put("sequence", value.identity.sequence)
        .put("fingerprintHigh", value.fingerprint.high)
        .put("fingerprintLow", value.fingerprint.low)
        .put("reboot", value.pumpReboot)
        .put("activeTbr", value.activeTbr?.let(::encodeTbr) ?: JSONObject.NULL)

    private fun encodeTbr(value: YpsoMutableHistoryState): JSONObject = JSONObject()
        .put("serial", value.identity.pumpSerial)
        .put("generation", value.identity.sequenceGeneration)
        .put("sequence", value.identity.sequence)
        .put("fingerprintHigh", value.fingerprint.high)
        .put("fingerprintLow", value.fingerprint.low)
        .put("stateHigh", value.stateFingerprint.high)
        .put("stateLow", value.stateFingerprint.low)
        .put("percent", value.percent)
        .put("requestedMinutes", value.requestedDurationMinutes)

    private fun encodePending(value: YpsoPendingBolusSync): JSONObject = JSONObject()
        .put("pumpSerial", value.pumpSerial)
        .put("pumpId", value.pumpId)
        .put("timestamp", value.timestamp)
        .put("amountCentiUnits", value.amountCentiUnits)
        .put("eventSequence", value.eventSequence)

    private fun decode(json: JSONObject): YpsoHistoryState {
        require(json.getInt("version") == 1)
        require(json.keys().asSequence().toSet() == setOf("version", "cursor", "pendingBolus"))
        return YpsoHistoryState(
            cursor = if (json.isNull("cursor")) null else decodeCursor(json.getJSONObject("cursor")),
            pendingBolus = if (json.isNull("pendingBolus")) null else decodePending(json.getJSONObject("pendingBolus")),
        )
    }

    private fun decodeCursor(value: JSONObject): YpsoHistoryCursor {
        require(value.keys().asSequence().toSet() == CURSOR_FIELDS)
        return YpsoHistoryCursor(
            identity(value),
            YpsoHistoryFingerprint(value.getLong("fingerprintHigh"), value.getLong("fingerprintLow")),
            value.getLong("reboot"),
            if (value.isNull("activeTbr")) null else decodeTbr(value.getJSONObject("activeTbr")),
        )
    }

    private fun decodeTbr(value: JSONObject): YpsoMutableHistoryState {
        require(value.keys().asSequence().toSet() == TBR_FIELDS)
        return YpsoMutableHistoryState(
            identity(value),
            YpsoHistoryFingerprint(value.getLong("fingerprintHigh"), value.getLong("fingerprintLow")),
            YpsoHistoryFingerprint(value.getLong("stateHigh"), value.getLong("stateLow")),
            value.getInt("percent"),
            value.getInt("requestedMinutes"),
        )
    }

    private fun identity(value: JSONObject) = YpsoEventIdentity(
        value.getString("serial"), value.getInt("generation"), value.getLong("sequence"),
    )

    private fun decodePending(value: JSONObject): YpsoPendingBolusSync {
        require(value.keys().asSequence().toSet() == PENDING_FIELDS)
        return YpsoPendingBolusSync(
            value.getString("pumpSerial"), value.getLong("pumpId"), value.getLong("timestamp"),
            value.getInt("amountCentiUnits"), value.getLong("eventSequence"),
        )
    }

    companion object {
        private val CURSOR_FIELDS = setOf(
            "serial", "generation", "sequence", "fingerprintHigh", "fingerprintLow", "reboot", "activeTbr",
        )
        private val TBR_FIELDS = setOf(
            "serial", "generation", "sequence", "fingerprintHigh", "fingerprintLow", "stateHigh", "stateLow", "percent", "requestedMinutes",
        )
        private val PENDING_FIELDS = setOf("pumpSerial", "pumpId", "timestamp", "amountCentiUnits", "eventSequence")
    }
}
