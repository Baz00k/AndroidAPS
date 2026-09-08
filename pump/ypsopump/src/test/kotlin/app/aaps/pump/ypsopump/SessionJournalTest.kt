package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionJournal
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Executes real journal serialization/order and HMAC; injected storage is not Android Keystore. */
class SessionJournalTest {
    private class Storage : SessionJournal.Storage {
        var file: String? = null
        val keys = mutableMapOf<String, ByteArray>()
        var fault = ""
        private fun boundary(name: String) { check(fault != name) { "Injected crash: $name" } }
        override fun read() = file
        override fun anchors() = keys.keys.toList()
        override fun create(alias: String) {
            boundary("before-create")
            keys[alias] = java.security.MessageDigest.getInstance("SHA-256").digest(alias.toByteArray())
            boundary("after-create")
        }
        override fun delete(alias: String) {
            boundary("before-delete")
            keys.remove(alias)
            boundary("after-delete")
        }
        override fun authenticate(alias: String, body: String): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(checkNotNull(keys[alias]), "HmacSHA256"))
            doFinal(body.toByteArray())
        }
        override fun writeAndSync(value: String) {
            boundary("before-truncate")
            file = ""
            boundary("after-truncate")
            file = value.take(value.length / 2)
            boundary("partial-write")
            file = value
            boundary("before-sync")
            boundary("after-sync")
        }
    }

    private val old = PumpSession.State(listOf(PumpSession.Record("pump", "00".repeat(32), "generation", 8, 100, null)))
    private val next = old.copy(records = old.records.map { it.copy(read = 101) })

    @Test
    fun `roundtrip and restoring stale file rejects deleted anchor`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)
        val backup = storage.file
        journal.commit(next)
        assertEquals(next, journal.load())
        storage.file = backup
        assertThrows(IllegalStateException::class.java) { journal.load() }
    }

    @Test
    fun `every journal crash boundary restores new state or becomes unavailable`() {
        val boundaries = listOf("before-create", "after-create", "before-delete", "after-delete", "before-truncate", "after-truncate", "partial-write", "before-sync", "after-sync")
        for (boundary in boundaries) {
            val storage = Storage()
            val journal = SessionJournal(storage)
            journal.commit(old)
            storage.fault = boundary
            assertThrows(IllegalStateException::class.java) { journal.commit(next) }
            storage.fault = ""
            val loaded = runCatching { SessionJournal(storage).load() }.getOrNull()
            when (boundary) {
                "before-create" -> assertEquals(old, loaded) // No change and no publication/dispatch.
                "before-sync", "after-sync" -> assertEquals(next, loaded)
                else -> assertNull(loaded, boundary)
            }
        }
    }

    @Test
    fun `file loss key loss corruption and restored backup on another installation reject`() {
        for (fault in 0..3) {
            val storage = Storage()
            val journal = SessionJournal(storage)
            journal.commit(old)
            when (fault) {
                0 -> storage.file = null
                1 -> storage.keys.clear()
                2 -> storage.file = storage.file!!.replace("100", "999")
                3 -> storage.keys.values.first()[0] = 42
            }
            assertTrue(runCatching { journal.load() }.isFailure, "fault=$fault")
        }
    }

    @Test
    fun `invalid state invariants reject before creating an anchor`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val record = old.records.single()
        val invalid = listOf(
            old.copy(records = listOf(record, record)),
            old.copy(records = listOf(record.copy(read = -1))),
            old.copy(records = listOf(record.copy(reboot = -1))),
            old.copy(records = listOf(record.copy(write = 42, reservation = PumpSession.Reservation("id", 43, PumpSession.Phase.ACKED))))
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { journal.commit(it) } }
        assertTrue(storage.keys.isEmpty())
        assertNull(storage.file)
    }
}
