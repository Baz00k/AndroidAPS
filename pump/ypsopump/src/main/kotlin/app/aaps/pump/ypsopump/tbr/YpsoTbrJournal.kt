package app.aaps.pump.ypsopump.tbr

import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * One START_STOP_TBR command sent by AAPS. The attempt is journalled before the command can leave the
 * phone and is resolved only by pump evidence: same-link status, a later status read, or history.
 */
data class YpsoTbrAttempt(
    val id: String,
    val kind: Kind,
    val pumpSerial: String,
    /** Requested percent; [YpsoTbrRequest.STOP_PERCENT] for a stop. */
    val percent: Int,
    /** Requested minutes; 0 for a stop. */
    val durationMinutes: Int,
    /** [app.aaps.core.interfaces.pump.PumpSync.TemporaryBasalType] name of a start. */
    val type: String,
    /** PumpSync temporary ID of a start's record until history binds it to [pumpId]. */
    val temporaryId: Long,
    /** History cursor identity when the start was prepared; its pump row is newer. */
    val baselinePumpId: Long?,
    val createdAt: Long,
    val state: State = State.PREPARED,
    val dispatchedAt: Long? = null,
    /** When the command took effect: pump acknowledgement, or dispatch when the ACK was lost. */
    val effectiveAt: Long? = null,
    /** Latest moment a start can have taken effect; bounds which history row can be its own. */
    val effectiveBy: Long? = null,
    /** A start's record was saved and read back. A stop has no record of its own. */
    val accounted: Boolean = false,
    /** Set on a start when a later confirmed AAPS stop ended it. */
    val stoppedAt: Long? = null,
    val pumpId: Long? = null,
    val detail: String? = null,
) {
    enum class Kind { START, STOP }

    enum class State {
        /** Journalled; the command has not left the phone. */
        PREPARED,
        /** The command may have reached the pump; no status has proven its effect yet. */
        DISPATCHED,
        /** Status proved the command's effect. */
        EFFECTIVE,
        /** Status proved the command had no effect. */
        NO_EFFECT,
    }

    val awaitsStatus: Boolean get() = state == State.PREPARED || state == State.DISPATCHED
    val awaitsAccounting: Boolean get() = kind == Kind.START && state == State.EFFECTIVE && !accounted
    val awaitsBinding: Boolean get() = kind == Kind.START && state == State.EFFECTIVE && pumpId == null
    /** Anything that keeps AAPS from knowing it represents the pump truthfully. */
    val unresolved: Boolean get() = awaitsStatus || awaitsAccounting
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
        require(attempts.none { it.id == attempt.id || it.kind == YpsoTbrAttempt.Kind.START && it.temporaryId == attempt.temporaryId }) {
            "duplicate TBR attempt identity"
        }
        store.commitAll(retained(attempts + attempt))
    }

    /**
     * The command may leave the phone now. A proven counter rejection (139) makes the transport send
     * the same command again under a higher counter; that repeats this call and keeps the first time.
     */
    @Synchronized fun dispatched(id: String, at: Long) = update(id) {
        check(it.awaitsStatus) { "TBR attempt outcome is already known" }
        if (it.state == YpsoTbrAttempt.State.DISPATCHED) it else it.copy(state = YpsoTbrAttempt.State.DISPATCHED, dispatchedAt = at)
    }

    /** A start took effect at [at], and no later than [by]. */
    @Synchronized fun effective(id: String, at: Long, by: Long = at) = update(id) {
        check(it.awaitsStatus && it.kind == YpsoTbrAttempt.Kind.START) { "TBR start outcome is already known" }
        it.copy(state = YpsoTbrAttempt.State.EFFECTIVE, effectiveAt = at, effectiveBy = maxOf(at, by), detail = null)
    }

    /**
     * A stop took effect at [at]. In the same commit, every AAPS start still recorded as running then
     * gets its end and is queued for accounting again, so a crash at any later point is replayed.
     */
    @Synchronized fun stopEffective(id: String, at: Long): List<YpsoTbrAttempt> {
        val attempts = store.loadAll()
        val stop = attempts.firstOrNull { it.id == id } ?: error("unknown TBR attempt")
        check(stop.awaitsStatus && stop.kind == YpsoTbrAttempt.Kind.STOP) { "TBR stop outcome is already known" }
        val ended = attempts.filter {
            it.kind == YpsoTbrAttempt.Kind.START && it.state == YpsoTbrAttempt.State.EFFECTIVE && it.stoppedAt == null &&
                checkNotNull(it.effectiveAt) < at && checkNotNull(it.effectiveAt) + it.durationMinutes * MINUTE > at
        }.map(YpsoTbrAttempt::id).toSet()
        val next = attempts.map {
            when {
                it.id == id -> it.copy(state = YpsoTbrAttempt.State.EFFECTIVE, effectiveAt = at, effectiveBy = at, detail = null)
                it.id in ended -> it.copy(stoppedAt = at, accounted = false)
                else -> it
            }
        }
        store.commitAll(next)
        return next.filter { it.id in ended }
    }

    @Synchronized fun noEffect(id: String, detail: String) = update(id) {
        check(it.awaitsStatus) { "TBR attempt outcome is already known" }
        it.copy(state = YpsoTbrAttempt.State.NO_EFFECT, detail = detail)
    }

    @Synchronized fun accounted(id: String) = update(id) {
        check(it.awaitsAccounting) { "TBR attempt is not awaiting accounting" }
        it.copy(accounted = true, detail = null)
    }

    @Synchronized fun detail(id: String, detail: String) = update(id) { it.copy(detail = detail) }

    /** The record is gone (e.g. deleted by the user); there is nothing left to account. */
    @Synchronized fun abandonAccounting(id: String, detail: String) = update(id) {
        check(it.awaitsAccounting) { "TBR attempt is not awaiting accounting" }
        it.copy(accounted = true, detail = detail)
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
        if (next != current) store.commitAll(attempts.map { if (it.id == id) next else it })
        return next
    }

    /** Keeps every attempt that can still change AAPS records, plus a short diagnostic tail. */
    private fun retained(attempts: List<YpsoTbrAttempt>): List<YpsoTbrAttempt> {
        val recent = attempts.takeLast(RETAINED_ATTEMPTS).toSet()
        return attempts.filter { it.unresolved || it.awaitsBinding || it in recent }.takeLast(MAX_ATTEMPTS)
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val RETAINED_ATTEMPTS = 10
        private const val MAX_ATTEMPTS = 64
    }
}

/** Atomic no-backup file store. The caller must place [file] under Android's noBackupFilesDir. */
class YpsoTbrAttemptFileStore(private val file: File) : YpsoTbrAttemptStore {

    override fun loadAll(): List<YpsoTbrAttempt> {
        if (!file.exists()) return emptyList()
        val root = JSONObject(file.readText())
        val version = root.getInt("version")
        if (version != VERSION) return retireEarlierVersion(root, version)
        val array = root.getJSONArray("attempts")
        return (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }

    /**
     * Pre-release journals used another schema. One whose attempts are all settled holds nothing that
     * could still change a record, so it is archived and a new journal starts. Anything else stays
     * unreadable, which fails every TBR operation closed.
     */
    private fun retireEarlierVersion(root: JSONObject, version: Int): List<YpsoTbrAttempt> {
        require(version in 1 until VERSION) { "unsupported TBR journal version" }
        val array = root.getJSONArray("attempts")
        val settled = (0 until array.length()).all {
            val attempt = array.getJSONObject(it)
            attempt.getString("state") == "NOT_STARTED" ||
                attempt.getString("state") in setOf("STARTED", "EFFECTIVE") && !attempt.isNull("pumpId")
        }
        require(settled) { "TBR journal version $version holds unresolved attempts" }
        check(file.renameTo(File(file.parentFile, "${file.name}.v$version"))) { "cannot archive TBR journal version $version" }
        return emptyList()
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
        .put("kind", value.kind.name)
        .put("pumpSerial", value.pumpSerial)
        .put("percent", value.percent)
        .put("durationMinutes", value.durationMinutes)
        .put("type", value.type)
        .put("temporaryId", value.temporaryId)
        .putNullable("baselinePumpId", value.baselinePumpId)
        .put("createdAt", value.createdAt)
        .put("state", value.state.name)
        .putNullable("dispatchedAt", value.dispatchedAt)
        .putNullable("effectiveAt", value.effectiveAt)
        .putNullable("effectiveBy", value.effectiveBy)
        .put("accounted", value.accounted)
        .putNullable("stoppedAt", value.stoppedAt)
        .putNullable("pumpId", value.pumpId)
        .putNullable("detail", value.detail)

    private fun decode(json: JSONObject): YpsoTbrAttempt {
        require(json.keys().asSequence().toSet() == FIELDS) { "unexpected TBR journal fields" }
        return YpsoTbrAttempt(
            id = json.getString("id"),
            kind = YpsoTbrAttempt.Kind.valueOf(json.getString("kind")),
            pumpSerial = json.getString("pumpSerial"),
            percent = json.getInt("percent"),
            durationMinutes = json.getInt("durationMinutes"),
            type = json.getString("type"),
            temporaryId = json.getLong("temporaryId"),
            baselinePumpId = json.longOrNull("baselinePumpId"),
            createdAt = json.getLong("createdAt"),
            state = YpsoTbrAttempt.State.valueOf(json.getString("state")),
            dispatchedAt = json.longOrNull("dispatchedAt"),
            effectiveAt = json.longOrNull("effectiveAt"),
            effectiveBy = json.longOrNull("effectiveBy"),
            accounted = json.getBoolean("accounted"),
            stoppedAt = json.longOrNull("stoppedAt"),
            pumpId = json.longOrNull("pumpId"),
            detail = if (json.isNull("detail")) null else json.getString("detail"),
        )
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)
    private fun JSONObject.longOrNull(name: String): Long? = if (isNull(name)) null else getLong(name)

    companion object {
        private const val VERSION = 3
        private val FIELDS = setOf(
            "id", "kind", "pumpSerial", "percent", "durationMinutes", "type", "temporaryId", "baselinePumpId", "createdAt",
            "state", "dispatchedAt", "effectiveAt", "effectiveBy", "accounted", "stoppedAt", "pumpId", "detail",
        )
    }
}
