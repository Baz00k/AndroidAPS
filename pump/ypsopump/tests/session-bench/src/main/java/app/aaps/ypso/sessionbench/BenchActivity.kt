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
                "performance" -> {
                    val evidence = (0 until 2000).map { i ->
                        PumpSession.WriteEvidence("operation-$i", "reservation-$i", i + 1L,
                            "669a0c20-0008-969e-e211-fcbee38b7bc5", "START_STOP_TBR",
                            PumpSession.fingerprint("payload-$i".toByteArray()), i.toLong(),
                            PumpSession.WriteCandidate.STANDARD, PumpSession.WriteResolution.ACCEPTED,
                            PumpSession.fingerprint("evidence-$i".toByteArray()), "same-link status proved requested temporary basal")
                    }
                    var state = PumpSession.State(listOf(PumpSession.Record("synthetic-pump", "00".repeat(32),
                        "synthetic-generation", 8, 100, 2000, writeEvidence = evidence,
                        writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED)))
                    journal.commit(state)
                    val started = System.nanoTime()
                    repeat(6) {
                        state = state.copy(records = state.records.map { r -> r.copy(read = r.read!! + 1) })
                        journal.commit(state)
                    }
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    check(journal.load() == state) { "Round-trip changed session evidence" }
                    report("PERFORMANCE:commits=6,elapsedMs=$elapsedMs,journalBytes=${File(noBackupFilesDir, "ypso-session.json").length()},evidence=${evidence.size}")
                }
                else -> error("Unknown action")
            }
        } catch (e: Exception) {
            report("UNAVAILABLE:${e.javaClass.simpleName}")
        }
        finish()
    }

    private fun report(value: String) {
        File(filesDir, "result.txt").outputStream().use { out ->
            out.write(value.toByteArray())
            out.fd.sync()
        }
        android.util.Log.i("YpsoSessionBench", value)
    }
}
