package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionJournal
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Executes journal serialization/order, legacy HMAC and sealed-body behavior; storage is not Android Keystore. */
class SessionJournalTest {
    private class Storage : SessionJournal.Storage {
        var file: String? = null
        val keys = mutableMapOf<String, ByteArray>()
        var fault = ""
        var failSeal = false
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
        override fun authenticateLegacy(alias: String, body: String): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(checkNotNull(keys[alias]), "HmacSHA256"))
            doFinal(body.toByteArray())
        }
        override fun seal(alias: String, body: String): String {
            check(!failSeal) { "Injected seal failure" }
            return body + "." + authenticateLegacy(alias, body).joinToString("") { "%02x".format(it) }
        }
        override fun open(alias: String, sealed: String): String {
            val body = sealed.substringBeforeLast('.')
            val mac = sealed.substringAfterLast('.').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            check(java.security.MessageDigest.isEqual(authenticateLegacy(alias, body), mac))
            return body
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
    fun `legacy version one journal selects its sole generation without inventing protected credentials`() {
        val storage = Storage()
        val alias = "ypso.session.revision.legacy"
        storage.create(alias)
        val body = """{"version":1,"records":[{"pump":"pump","key":"${"00".repeat(32)}","generation":"generation","reboot":8,"read":100,"write":null,"reservation":null}]}"""
        storage.file = org.json.JSONObject()
            .put("anchor", alias)
            .put("body", body)
            .put("mac", storage.authenticateLegacy(alias, body).joinToString("") { "%02x".format(it) })
            .toString()

        val loaded = SessionJournal(storage).load()

        assertEquals("generation", loaded.activeGeneration)
        assertNull(loaded.records.single().keyHex)
        assertEquals(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE), loaded.availability.causes)
    }

    @Test
    fun `version two upgrades with no invented candidate and final candidate attempts survive restart`() {
        val storage = Storage()
        val alias = "ypso.session.revision.v2"
        storage.create(alias)
        val keyHex = "01".repeat(32)
        val keyId = PumpSession.fingerprint(ByteArray(32) { 1 })
        val body = """{"version":2,"records":[{"pump":"pump","key":"$keyId","generation":"generation","reboot":8,"read":100,"write":null,"reservation":null,"serial":"serial","keyHex":"$keyHex","createdAt":null,"importedAt":1,"source":{},"verifiedAt":2,"verifiedSerial":"serial"}],"activeGeneration":"generation","availability":{"causes":[],"since":2,"code":null,"operation":null,"firmware":null,"failures":0,"retryAt":null}}"""
        storage.file = org.json.JSONObject().put("anchor", alias).put("sealed", storage.seal(alias, body)).toString()
        val journal = SessionJournal(storage)

        val upgraded = journal.load()
        assertNull(upgraded.candidateGeneration)
        assertNull(upgraded.lastAttempt)

        val candidateHex = "02".repeat(32)
        val candidate = PumpSession.Record("pump", keyId, "candidate", null, null, null, serial = "serial", keyHex = keyHex)
        val pending = upgraded.copy(
            records = upgraded.records + candidate,
            candidateGeneration = candidate.generation,
            candidateReplacesGeneration = upgraded.activeGeneration,
            candidateAttemptId = "attempt",
            candidateAvailability = PumpSession.Availability(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE))
        )
        journal.commit(pending)
        assertEquals(pending, SessionJournal(storage).load())

        val cancelled = pending.copy(
            records = pending.records - candidate,
            candidateGeneration = null,
            candidateReplacesGeneration = null,
            candidateAttemptId = null,
            candidateAvailability = null,
            lastAttempt = PumpSession.AttemptResult("attempt", PumpSession.AttemptStatus.CANCELLED)
        )
        journal.commit(cancelled)
        assertEquals(cancelled, SessionJournal(storage).load())
    }

    @Test
    fun `failed seal removes its uncommitted anchor and preserves the prior revision`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val prior = old
        journal.commit(prior)
        storage.failSeal = true

        assertThrows(Exception::class.java) { journal.commit(next) }

        storage.failSeal = false
        assertEquals(prior, journal.load())
        assertEquals(1, storage.anchors().size)
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
                "before-create", "after-create" -> assertEquals(old, loaded) // No change and no publication/dispatch.
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
