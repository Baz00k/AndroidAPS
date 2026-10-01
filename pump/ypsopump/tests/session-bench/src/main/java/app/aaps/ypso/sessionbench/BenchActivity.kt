package app.aaps.ypso.sessionbench

import android.app.Activity
import android.os.Bundle
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionJournal
import java.io.File

/** Separate UID, synthetic identity, no Bluetooth permissions. Never touches AAPS storage. */
class BenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread { runBenchmark() }.start()
    }

    private fun runBenchmark() {
        val action = intent.getStringExtra("action") ?: "inspect"
        val fault = intent.getStringExtra("fault") ?: ""
        val storage = SessionJournal.AndroidStorage(this) { point ->
            if (point == fault) {
                report("KILL:$point")
                android.os.Process.killProcess(android.os.Process.myPid())
                error("Process kill returned")
            }
        }
        val journal = SessionJournal(storage)
        try {
            when (action) {
                "reset" -> {
                    storage.anchors().forEach(storage::delete)
                    File(noBackupFilesDir, "ypso-session.json").delete()
                    journal.commit(PumpSession.State(listOf(PumpSession.Record("synthetic-pump", "00".repeat(32), "synthetic-generation", 8, 100, null))))
                    report("BASELINE:100")
                }
                "commit" -> {
                    val old = journal.load()
                    journal.commit(old.copy(records = old.records.map { it.copy(read = 101) }))
                    report("COMMITTED:101")
                }
                "inspect" -> report("READ:${journal.load().records.single().read}")
                "snapshot-performance", "snapshot-compacted" -> {
                    val body = File(filesDir, "redacted-body.json").readText()
                    var imported = SessionJournal(object : SessionJournal.Storage {
                        override fun read() = "{\"anchor\":\"snapshot\",\"sealed\":\"snapshot\"}"
                        override fun anchors() = listOf("snapshot")
                        override fun open(alias: String, sealed: String) = body
                        override fun create(alias: String) = error("Read-only import")
                        override fun delete(alias: String) = error("Read-only import")
                        override fun seal(alias: String, body: String) = error("Read-only import")
                        override fun authenticateLegacy(alias: String, body: String): ByteArray = error("Read-only import")
                        override fun writeAndSync(value: String) = error("Read-only import")
                    }).load()
                    if (action == "snapshot-compacted") {
                        val captured = imported
                        val owner = PumpSession(object : PumpSession.Store {
                            override fun load() = captured
                            override fun commit(state: PumpSession.State) = error("Read-only snapshot load")
                        })
                        owner.open("synthetic-pump-0", ByteArray(32))
                        imported = imported.copy(records = listOf(checkNotNull(owner.snapshot())))
                    }
                    val nanos = mutableMapOf<String, Long>()
                    val calls = mutableMapOf<String, Int>()
                    fun <T> timed(name: String, block: () -> T): T {
                        val start = System.nanoTime()
                        try { return block() } finally {
                            nanos[name] = (nanos[name] ?: 0L) + System.nanoTime() - start
                            calls[name] = (calls[name] ?: 0) + 1
                        }
                    }
                    val measured = SessionJournal(object : SessionJournal.Storage {
                        override fun read() = timed("read") { storage.read() }
                        override fun anchors() = timed("anchors") { storage.anchors() }
                        override fun open(alias: String, sealed: String) = timed("open") { storage.open(alias, sealed) }
                        override fun create(alias: String) = timed("create") { storage.create(alias) }
                        override fun delete(alias: String) = timed("delete") { storage.delete(alias) }
                        override fun seal(alias: String, body: String) = timed("seal") { storage.seal(alias, body) }
                        override fun authenticateLegacy(alias: String, body: String) = storage.authenticateLegacy(alias, body)
                        override fun writeAndSync(value: String) = timed("writeSync") { storage.writeAndSync(value) }
                    })
                    var state = imported
                    measured.commit(state)
                    check(measured.load() == state) { "Imported snapshot changed" }
                    nanos.clear()
                    calls.clear()
                    val validationStart = System.nanoTime()
                    repeat(6) { PumpSession.validate(state) }
                    val validationMs = (System.nanoTime() - validationStart) / 1_000_000
                    val start = System.nanoTime()
                    repeat(6) {
                        state = state.copy(records = state.records.map { record ->
                            record.copy(read = record.read?.plus(1))
                        })
                        measured.commit(state)
                    }
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000
                    val parts = nanos.map { (name, ns) -> "$name=${ns / 1_000_000}ms/${calls[name]}calls" }.joinToString(",")
                    check(measured.load() == state) { "Snapshot round-trip changed" }
                    report("SNAPSHOT:commits=6,elapsedMs=$elapsedMs,bytes=${File(noBackupFilesDir, "ypso-session.json").length()},evidence=${state.records.sumOf { it.writeEvidence.size }},validation6Ms=$validationMs,$parts\n" +
                        if (elapsedMs < 1000) "PASS:6 commits below 1s" else "SLOW:6 commits exceed 1s")
                }
                "selector-replay" -> {
                    // The prior snapshot-compacted action installed synthetic credentials in this UID.
                    val owner = PumpSession(journal)
                    val token = runCatching { owner.open("synthetic-pump-0", ByteArray(32)) }.getOrElse {
                        error("Run snapshot-compacted with a redacted synthetic snapshot before selector-replay")
                    }
                    val before = checkNotNull(owner.snapshot())
                    val start = System.nanoTime()
                    repeat(48) { i ->
                        val tx = owner.begin(token)
                        owner.reserve(token, tx, PumpSession.WriteIntent("profile-$i", "669a0c20-0008-969e-e211-fcbeb3147bc5",
                            "SETTINGS_SELECTOR", "ab".repeat(32)))
                        owner.advance(token, tx, PumpSession.Phase.POSSIBLY_SENT)
                        repeat(2) {
                            val current = checkNotNull(owner.snapshot())
                            owner.accept(token, tx, app.aaps.pump.ypsopump.crypto.SessionCrypto.Message(
                                byteArrayOf(1), checkNotNull(current.reboot), checkNotNull(current.read) + 1))
                        }
                        owner.finish(token, tx)
                        owner.resolveWrite(token, tx, PumpSession.WriteResolution.ACCEPTED, "cd".repeat(32), "synthetic same-link read-back")
                    }
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000
                    val after = checkNotNull(owner.snapshot())
                    check(after.write == checkNotNull(before.write) + 48)
                    check(after.read == checkNotNull(before.read) + 96)
                    check(journal.load().records.single() == after)
                    report("SELECTOR_REPLAY:rows=48,commits=240,elapsedMs=$elapsedMs,evidence=${after.writeEvidence.size},bytes=${File(noBackupFilesDir, "ypso-session.json").length()}")
                }
                else -> error("Unknown action")
            }
        } catch (e: Exception) {
            android.util.Log.e("YpsoSessionBench", "Synthetic snapshot benchmark failed", e)
            report("UNAVAILABLE:${e.javaClass.simpleName}")
        }
        runOnUiThread { finish() }
    }

    private fun report(value: String) {
        File(filesDir, "result.txt").outputStream().use { out ->
            out.write(value.toByteArray())
            out.fd.sync()
        }
        android.util.Log.i("YpsoSessionBench", value)
    }
}
