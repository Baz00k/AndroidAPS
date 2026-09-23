package app.aaps.pump.ypsopump.tbr

import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * One AAPS-initiated TBR start. A started attempt owns a PumpSync record under [temporaryId] until
 * history ingestion binds it to the pump's own TBR row ([pumpId]).
 */
data class YpsoTbrAttempt(
    val id: String,
    val pumpSerial: String,
    val percent: Int,
    val durationMinutes: Int,
    /** [app.aaps.core.interfaces.pump.PumpSync.TemporaryBasalType] name. */
    val type: String,
    val temporaryId: Long,
    /** History cursor identity when the start was prepared; the pump row must be newer. */
    val baselinePumpId: Long?,
    val createdAt: Long,
    val state: State = State.PREPARED,
    val dispatchedAt: Long? = null,
    /** Timestamp of the AAPS record: pump acknowledgement, or dispatch when the ACK was lost. */
    val startedAt: Long? = null,
    /** Set when AAPS itself stopped this TBR and cut its record. */
    val stoppedAt: Long? = null,
    val pumpId: Long? = null,
    val detail: String? = null,
) {
    enum class State {
        /** Journalled; the command has not left the phone. */
        PREPARED,
        /** The command may have reached the pump; no status has been read since. */
        DISPATCHED,
        /** Status proved the pump runs this TBR, and AAPS recorded it. */
        STARTED,
        /** Status proved the pump does not run this TBR; AAPS recorded nothing for it. */
        NOT_STARTED,
    }

    val awaitsStatus: Boolean get() = state == State.PREPARED || state == State.DISPATCHED
    val awaitsBinding: Boolean get() = state == State.STARTED && pumpId == null
}

interface YpsoTbrAttemptStore {
    fun loadAll(): List<YpsoTbrAttempt>
    fun commitAll(attempts: List<YpsoTbrAttempt>)
}

/** Serialized access to the durable attempt list. Every transition is persisted before it is acted on. */
class YpsoTbrJournal(private val store: YpsoTbrAttemptStore) {

    @Synchronized fun all(): List<YpsoTbrAttempt> = store.loadAll()

    @Synchronized fun find(id: String): YpsoTbrAttempt? = store.loadAll().firstOrNull { it.id == id }

    @Synchronized fun prepare(attempt: YpsoTbrAttempt) {
        require(attempt.state == YpsoTbrAttempt.State.PREPARED)
        val attempts = store.loadAll()
        require(attempts.none { it.id == attempt.id || it.temporaryId == attempt.temporaryId }) { "duplicate TBR attempt identity" }
        store.commitAll(retained(attempts + attempt))
    }

    @Synchronized fun dispatched(id: String, at: Long) = update(id) {
        check(it.state == YpsoTbrAttempt.State.PREPARED) { "TBR attempt is not awaiting dispatch" }
        it.copy(state = YpsoTbrAttempt.State.DISPATCHED, dispatchedAt = at)
    }

    @Synchronized fun started(id: String, at: Long) = update(id) {
        check(it.awaitsStatus) { "TBR attempt outcome is already known" }
        it.copy(state = YpsoTbrAttempt.State.STARTED, startedAt = at, detail = null)
    }

    @Synchronized fun notStarted(id: String, detail: String) = update(id) {
        check(it.awaitsStatus) { "TBR attempt outcome is already known" }
        it.copy(state = YpsoTbrAttempt.State.NOT_STARTED, detail = detail)
    }

    @Synchronized fun detail(id: String, detail: String) = update(id) { it.copy(detail = detail) }

    /** AAPS cut the record of the running started attempt, if any, at [at]. */
    @Synchronized fun stopped(at: Long) {
        val attempts = store.loadAll()
        val running = attempts.lastOrNull { it.state == YpsoTbrAttempt.State.STARTED && it.stoppedAt == null } ?: return
        store.commitAll(attempts.map { if (it.id == running.id) it.copy(stoppedAt = at) else it })
    }

    @Synchronized fun bound(id: String, pumpId: Long) = update(id) {
        check(it.awaitsBinding) { "TBR attempt is not awaiting a pump identity" }
        it.copy(pumpId = pumpId)
    }

    @Synchronized fun boundTo(pumpId: Long): YpsoTbrAttempt? = store.loadAll().firstOrNull { it.pumpId == pumpId }

    private fun update(id: String, change: (YpsoTbrAttempt) -> YpsoTbrAttempt): YpsoTbrAttempt {
        val attempts = store.loadAll()
        val current = attempts.firstOrNull { it.id == id } ?: error("unknown TBR attempt")
        val next = change(current)
        store.commitAll(attempts.map { if (it.id == id) next else it })
        return next
    }

    /** Keeps every attempt that can still change AAPS records, plus a short diagnostic tail. */
    private fun retained(attempts: List<YpsoTbrAttempt>): List<YpsoTbrAttempt> {
        val open = attempts.filter { it.awaitsStatus || it.awaitsBinding || it.pumpId != null && it.stoppedAt == null }
        val recent = attempts.takeLast(RETAINED_ATTEMPTS)
        return attempts.filter { it in open || it in recent }.takeLast(MAX_ATTEMPTS)
    }

    companion object {
        private const val RETAINED_ATTEMPTS = 10
        private const val MAX_ATTEMPTS = 64
    }
}

/** Atomic no-backup file store. The caller must place [file] under Android's noBackupFilesDir. */
class YpsoTbrAttemptFileStore(private val file: File) : YpsoTbrAttemptStore {

    override fun loadAll(): List<YpsoTbrAttempt> {
        if (!file.exists()) return emptyList()
        val root = JSONObject(file.readText())
        require(root.getInt("version") == VERSION) { "unsupported TBR journal version" }
        val array = root.getJSONArray("attempts")
        return (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }

    override fun commitAll(attempts: List<YpsoTbrAttempt>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.next")
        val bytes = JSONObject()
            .put("version", VERSION)
            .put("attempts", JSONArray().also { array -> attempts.forEach { array.put(encode(it)) } })
            .toString()
            .toByteArray(Charsets.UTF_8)
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "cannot atomically replace TBR journal" }
        file.parentFile?.let { directory ->
            java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun encode(value: YpsoTbrAttempt): JSONObject = JSONObject()
        .put("id", value.id)
        .put("pumpSerial", value.pumpSerial)
        .put("percent", value.percent)
        .put("durationMinutes", value.durationMinutes)
        .put("type", value.type)
        .put("temporaryId", value.temporaryId)
        .putNullable("baselinePumpId", value.baselinePumpId)
        .put("createdAt", value.createdAt)
        .put("state", value.state.name)
        .putNullable("dispatchedAt", value.dispatchedAt)
        .putNullable("startedAt", value.startedAt)
        .putNullable("stoppedAt", value.stoppedAt)
        .putNullable("pumpId", value.pumpId)
        .putNullable("detail", value.detail)

    private fun decode(json: JSONObject): YpsoTbrAttempt {
        require(json.keys().asSequence().toSet() == FIELDS) { "unexpected TBR journal fields" }
        return YpsoTbrAttempt(
            id = json.getString("id"),
            pumpSerial = json.getString("pumpSerial"),
            percent = json.getInt("percent"),
            durationMinutes = json.getInt("durationMinutes"),
            type = json.getString("type"),
            temporaryId = json.getLong("temporaryId"),
            baselinePumpId = json.longOrNull("baselinePumpId"),
            createdAt = json.getLong("createdAt"),
            state = YpsoTbrAttempt.State.valueOf(json.getString("state")),
            dispatchedAt = json.longOrNull("dispatchedAt"),
            startedAt = json.longOrNull("startedAt"),
            stoppedAt = json.longOrNull("stoppedAt"),
            pumpId = json.longOrNull("pumpId"),
            detail = if (json.isNull("detail")) null else json.getString("detail"),
        )
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)
    private fun JSONObject.longOrNull(name: String): Long? = if (isNull(name)) null else getLong(name)

    companion object {
        private const val VERSION = 1
        private val FIELDS = setOf(
            "id", "pumpSerial", "percent", "durationMinutes", "type", "temporaryId", "baselinePumpId", "createdAt",
            "state", "dispatchedAt", "startedAt", "stoppedAt", "pumpId", "detail",
        )
    }
}
