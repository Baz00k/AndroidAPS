package app.aaps.pump.ypsopump.provisioning

import app.aaps.pump.ypsopump.crypto.KeyTiming
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.json.JSONObject

/** Advisory-only store. Never acquires a session, provisioning or therapy monitor during I/O. */
internal class KeyTimingRepository(private val store: Store = MemoryStore()) {
    interface Store {
        fun load(): Map<String, KeyTiming>
        fun save(values: Map<String, KeyTiming>)
    }
    class MemoryStore : Store {
        private var values = emptyMap<String, KeyTiming>()
        override fun load() = values
        override fun save(values: Map<String, KeyTiming>) { this.values = values }
    }

    // Construction is used by therapy DI too: do not perform advisory I/O until its worker asks.
    @Volatile private var loaded = false
    @Volatile private var values = emptyMap<String, KeyTiming>()
    // Presentation is a non-blocking cache read, including when advisory storage is unavailable.
    fun cached(identity: String): KeyTiming = values[identity] ?: KeyTiming()
    fun isLoaded(): Boolean = loaded

    @Synchronized fun get(identity: String): KeyTiming {
        ensureLoaded()
        return cached(identity)
    }

    @Synchronized fun save(identity: String, timing: KeyTiming) {
        ensureLoaded()
        if (values[identity] == timing) return
        val next = values + (identity to timing)
        store.save(next)
        values = next
    }

    private fun ensureLoaded() {
        if (!loaded) {
            val read = store.load()
            values = read
            loaded = true
        }
    }

    /** Plain advisory metadata in no-backup storage; contains no keys, counters or therapy records. */
    class FileStore(private val file: File, private val beforeRename: () -> Unit = {}) : Store {
        override fun load(): Map<String, KeyTiming> {
            if (!file.exists()) return emptyMap()
            val root = JSONObject(file.readText())
            require(root.getInt("version") == 1)
            val entries = root.getJSONObject("entries")
            return entries.keys().asSequence().associateWith { identity ->
                val json = entries.getJSONObject(identity)
                fun date(name: String) = if (json.isNull(name)) null else json.getLong(name).also { require(it > 0) }
                KeyTiming(date("expiry"), date("reminder"), json.getBoolean("expiryDue"),
                    json.getBoolean("reminderDue"), json.getLong("observedAt").also { require(it >= 0) })
            }
        }

        override fun save(values: Map<String, KeyTiming>) {
            val entries = JSONObject()
            values.forEach { (identity, timing) -> entries.put(identity, JSONObject()
                .put("expiry", timing.expiryOverride ?: JSONObject.NULL)
                .put("reminder", timing.reminderOverride ?: JSONObject.NULL)
                .put("expiryDue", timing.expiryDue).put("reminderDue", timing.reminderDue)
                .put("observedAt", timing.observedAt)) }
            val bytes = JSONObject().put("version", 1).put("entries", entries).toString().toByteArray(Charsets.UTF_8)
            val temporary = File(file.path + ".new")
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            beforeRename()
            check(temporary.renameTo(file)) { "Cannot publish advisory dates" }
            FileChannel.open(checkNotNull(file.parentFile).toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
