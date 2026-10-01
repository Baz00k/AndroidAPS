package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionJournal
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Executes journal serialization/order and sealed-body behavior; storage is not Android Keystore. */
class SessionJournalTest {
    private class Storage : SessionJournal.Storage {
        var file: String? = null
        val keys = mutableMapOf<String, ByteArray>()
        var fault = ""
        var failSeal = false
        var terminateAt = ""
        private fun boundary(name: String) {
            if (terminateAt == name) throw ThreadDeath()
            check(fault != name) { "Injected crash: $name" }
        }
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
        private fun mac(alias: String, body: String): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(checkNotNull(keys[alias]), "HmacSHA256"))
            doFinal(body.toByteArray())
        }
        override fun seal(alias: String, body: String): String {
            check(!failSeal) { "Injected seal failure" }
            return body + "." + mac(alias, body).joinToString("") { "%02x".format(it) }
        }
        override fun open(alias: String, sealed: String): String {
            val body = sealed.substringBeforeLast('.')
            val expected = sealed.substringAfterLast('.').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            check(java.security.MessageDigest.isEqual(mac(alias, body), expected))
            return body
        }
        override fun writeAndSync(value: String) {
            boundary("before-truncate")
            boundary("after-truncate")
            boundary("partial-write")
            boundary("before-sync")
            boundary("after-sync")
            file = value
        }
    }

    private val old = PumpSession.State(listOf(PumpSession.Record("pump", "00".repeat(32), "generation", 8, 100, null)))
    private val next = old.copy(records = old.records.map { it.copy(read = 101) })

    @Test
    fun `process termination before publication preserves exact committed journal with an extra key`() {
        for (boundary in listOf("after-create", "before-truncate", "after-truncate", "partial-write", "before-sync", "after-sync")) {
            val storage = Storage()
            val journal = SessionJournal(storage)
            journal.commit(old)
            val original = storage.file
            storage.terminateAt = boundary
            assertThrows(ThreadDeath::class.java) { journal.commit(next) }
            storage.terminateAt = ""
            assertEquals(2, storage.anchors().size)
            assertEquals(old, SessionJournal(storage).load())
            assertEquals(original, storage.file)
            assertEquals(2, storage.anchors().size)
            journal.commit(next)
            assertEquals(next, journal.load())
            assertEquals(1, storage.anchors().size)
        }
    }

    @Test
    fun `process termination after retiring prior anchor exposes exact next revision`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)
        storage.terminateAt = "after-delete"

        assertThrows(ThreadDeath::class.java) { journal.commit(next) }

        storage.terminateAt = ""
        assertEquals(next, SessionJournal(storage).load())
        assertEquals(1, storage.anchors().size)
    }

    @Test
    fun `unavailable journal replacement retires stale anchors before publishing recovery`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)
        val publishedAlias = org.json.JSONObject(checkNotNull(storage.file)).getString("anchor")
        storage.keys.remove(publishedAlias)
        storage.keys["ypso.session.revision.stale-restored-backup"] = byteArrayOf(1)
        assertThrows(Exception::class.java) { journal.load() }

        journal.replaceUnavailable(next)

        assertEquals(next, SessionJournal(storage).load())
        assertEquals(1, storage.anchors().size)
        assertNotEquals("ypso.session.revision.stale-restored-backup", storage.anchors().single())
    }

    @Test
    fun `healthy journal cannot be replaced through disaster recovery`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)

        assertThrows(IllegalStateException::class.java) { journal.replaceUnavailable(next) }

        assertEquals(old, journal.load())
    }

    @Test
    fun `replacement interruption stays unavailable and can be retried without rollback`() {
        val boundaries = listOf(
            "after-create",
            "before-delete",
            "after-delete",
            "before-truncate",
            "after-truncate",
            "partial-write",
            "before-sync",
            "after-sync",
        )
        for (boundary in boundaries) {
            val storage = Storage()
            val journal = SessionJournal(storage)
            journal.commit(old)
            val publishedAlias = org.json.JSONObject(checkNotNull(storage.file)).getString("anchor")
            storage.keys.remove(publishedAlias)
            storage.keys["ypso.session.revision.stale-restored-backup"] = byteArrayOf(1)
            storage.terminateAt = boundary

            assertThrows(ThreadDeath::class.java, { journal.replaceUnavailable(next) }, boundary)

            storage.terminateAt = ""
            assertThrows(Exception::class.java, { SessionJournal(storage).load() }, boundary)
            journal.replaceUnavailable(next)
            assertEquals(next, SessionJournal(storage).load(), boundary)
            assertEquals(1, storage.anchors().size, boundary)
        }
    }

    @Test
    fun `process termination before retiring prior anchor keeps prior revision authoritative`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)
        storage.terminateAt = "before-delete"

        assertThrows(ThreadDeath::class.java) { journal.commit(next) }

        storage.terminateAt = ""
        assertEquals(old, SessionJournal(storage).load())
        assertEquals(2, storage.anchors().size)
        journal.commit(next)
        assertEquals(next, SessionJournal(storage).load())
        assertEquals(1, storage.anchors().size)
    }

    @Test
    fun `older orphan anchors are retired before authority moves to next revision`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)
        storage.keys["orphan"] = byteArrayOf(1)
        storage.fault = "before-delete"

        assertThrows(IllegalStateException::class.java) { journal.commit(next) }

        storage.fault = ""
        assertEquals(old, SessionJournal(storage).load())
    }

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
    fun `roundtrip preserves reservation evidence and lower bound recovery epoch`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val reservation = PumpSession.Reservation(
            id = "probe",
            counter = 9_036,
            phase = PumpSession.Phase.ACKED,
            operationId = "operation",
            characteristic = EVENT_INDEX,
            purpose = "HISTORY_SELECTOR",
            payloadHash = "ab".repeat(32),
            priorWrite = 9_035,
            candidate = PumpSession.WriteCandidate.LOWER_BOUND_HISTORY_RECOVERY_SELECTOR,
        )
        val recovering = PumpSession.State(
            records = listOf(
                PumpSession.Record(
                    pump = "pump",
                    keyId = "00".repeat(32),
                    generation = "generation",
                    reboot = 21,
                    read = 5,
                    write = 9_036,
                    reservation = reservation,
                    writeEvidence = listOf(evidence(reservation, resolution = null)),
                    writeBootstrapState = PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND,
                    lowerBoundRecoveryReboot = 21,
                ),
            ),
            activeGeneration = "generation",
            availability = PumpSession.Availability(setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN)),
        )

        journal.commit(recovering)

        assertEquals(recovering, SessionJournal(storage).load())
        assertEquals(18, committedBody(storage).getInt("version"))
    }

    @Test
    fun `version seventeen journal drops retired bench records and keeps every counter`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)
        val standard = v17Write("therapy", 4_101, 4_100, "STANDARD", TBR_COMMAND, "THERAPY_COMMAND", resolution = "ACCEPTED")
        val selector = v17Write("selector", 4_100, 4_099, "STANDARD", EVENT_INDEX, "HISTORY_SELECTOR", resolution = "ACCEPTED")
        val bench = v17Write("bench", 33, 32, "BENCH_STRICT_NEXT_SELECTOR", EVENT_INDEX, "HISTORY_SELECTOR", resolution = "ACCEPTED")
        val legacyAlarm = v17Write("alarm", 33, 32, "BENCH_ALARM_CURSOR_RECOVERY_SELECTOR", ALARM_INDEX, "HISTORY_SELECTOR", resolution = "ACCEPTED")
        val record = v17Record(
            write = 4_101,
            reservation = v17Reservation(standard, "VERIFIED"),
            evidence = listOf(legacyAlarm, bench, selector, standard),
        ).put("retiredLegacyBenchAlarmCursorRecovery", v17Reservation(legacyAlarm, "VERIFIED"))
        replaceBody(storage, v17Body(record))

        val loaded = SessionJournal(storage).load().records.single()

        assertEquals(21, loaded.reboot)
        assertEquals(812L, loaded.read)
        assertEquals(4_101L, loaded.write)
        assertEquals(PumpSession.WriteBootstrapState.ESTABLISHED, loaded.writeBootstrapState)
        assertEquals(2, loaded.counterRecoveryExponent)
        assertEquals("therapy", loaded.reservation?.operationId)
        assertEquals(PumpSession.Phase.VERIFIED, loaded.reservation?.phase)
        assertEquals(listOf("selector", "therapy"), loaded.writeEvidence.map { it.operationId })
        // The next commit is a clean version 18 body.
        journal.commit(SessionJournal(storage).load())
        val body = committedBody(storage)
        assertEquals(18, body.getInt("version"))
        assertFalse(body.toString().contains("bench", ignoreCase = true))
        assertEquals(loaded, SessionJournal(storage).load().records.single())
    }

    @Test
    fun `unresolved bench selector in the live slot is retired above its counter`() {
        val storage = Storage()
        SessionJournal(storage).commit(old)
        val bench = v17Write("bench", 4_102, 4_101, "BENCH_STRICT_NEXT_SELECTOR", EVENT_INDEX, "HISTORY_SELECTOR", resolution = null)
        replaceBody(storage, v17Body(v17Record(write = 4_102, reservation = v17Reservation(bench, "ACKED"), evidence = listOf(bench), exponent = 0)))
        val session = PumpSession(SessionJournal(storage))

        val token = session.open("pump", KEY)
        val reserved = session.reserve(token, session.begin(token))

        // The pump may have consumed 4102, so the next write must be above it.
        assertEquals(4_102L, reserved.priorWrite)
        assertEquals(4_103L, reserved.counter)
    }

    @Test
    fun `bench records outside a version seventeen journal fail closed`() {
        val storage = Storage()
        SessionJournal(storage).commit(old)
        val bench = v17Write("bench", 33, 32, "BENCH_STRICT_NEXT_SELECTOR", EVENT_INDEX, "HISTORY_SELECTOR", resolution = "ACCEPTED")
        replaceBody(storage, v17Body(v17Record(write = 33, reservation = null, evidence = listOf(bench))).put("version", 18))
        assertThrows(IllegalArgumentException::class.java) { SessionJournal(storage).load() }

        val boundStandard = v17Write("standard", 33, 32, "STANDARD", EVENT_INDEX, "HISTORY_SELECTOR", resolution = "ACCEPTED")
            .put("acceptedPredecessor", JSONObject().put("counter", 31))
        replaceBody(storage, v17Body(v17Record(write = 33, reservation = null, evidence = listOf(boundStandard))))
        assertThrows(IllegalArgumentException::class.java) { SessionJournal(storage).load() }

        replaceBody(storage, v17Body(v17Record(write = 33, reservation = null, evidence = emptyList())).put("version", 16))
        assertThrows(IllegalStateException::class.java) { SessionJournal(storage).load() }
    }

    @Test
    fun `journal validation rejects negative candidate failures and mismatched replacement identity`() {
        val keyHex = "01".repeat(32)
        val keyId = PumpSession.fingerprint(ByteArray(32) { 1 })
        val otherKeyId = PumpSession.fingerprint(ByteArray(32) { 2 })
        val active = PumpSession.Record("pump", keyId, "active", 8, 100, null, serial = "serial", keyHex = keyHex)
        val candidate = PumpSession.Record("pump", keyId, "candidate", null, null, null, serial = "serial", keyHex = keyHex)
        val state = PumpSession.State(
            records = listOf(active, candidate),
            activeGeneration = "active",
            candidateGeneration = "candidate",
            candidateReplacesGeneration = "active",
            candidateAttemptId = "attempt",
            candidateAvailability = PumpSession.Availability(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE))
        )
        PumpSession.validate(state)

        assertThrows(IllegalArgumentException::class.java) {
            PumpSession.validate(state.copy(candidateAvailability = state.candidateAvailability!!.copy(failures = -1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PumpSession.validate(state.copy(candidateGeneration = null, candidateAttemptId = null, candidateAvailability = null))
        }
        val foreign = candidate.copy(keyId = otherKeyId)
        assertThrows(IllegalArgumentException::class.java) {
            PumpSession.validate(state.copy(records = listOf(active, foreign)))
        }
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
                "before-create", "after-create", "before-truncate", "after-truncate", "partial-write", "before-sync", "after-sync" ->
                    assertEquals(old, loaded) // Atomic publication has not occurred.
                "before-delete" -> assertEquals(old, loaded) // Transition still resolves to prior.
                "after-delete" -> assertEquals(next, loaded) // Exact prior anchor was retired.
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
            old.copy(records = listOf(record.copy(write = 42, reservation = PumpSession.Reservation("id", 43, PumpSession.Phase.ACKED, priorWrite = 42))))
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { journal.commit(it) } }
        assertTrue(storage.keys.isEmpty())
        assertNull(storage.file)
    }


    private fun evidence(reservation: PumpSession.Reservation, resolution: PumpSession.WriteResolution?) = PumpSession.WriteEvidence(
        operationId = checkNotNull(reservation.operationId),
        reservationId = reservation.id,
        counter = reservation.counter,
        characteristic = checkNotNull(reservation.characteristic),
        purpose = checkNotNull(reservation.purpose),
        payloadHash = checkNotNull(reservation.payloadHash),
        priorWrite = reservation.priorWrite,
        candidate = reservation.candidate,
        resolution = resolution,
        evidenceHash = "cd".repeat(32),
        detail = "evidence",
    )

    /** A write record exactly as the version 17 writer emitted it, including its bench binding fields. */
    private fun v17Write(operation: String, counter: Long, priorWrite: Long, candidate: String, characteristic: String, purpose: String, resolution: String?) =
        JSONObject()
            .put("operationId", operation).put("reservationId", "reservation-$operation").put("counter", counter)
            .put("characteristic", characteristic).put("purpose", purpose).put("payloadHash", "ab".repeat(32))
            .put("priorWrite", priorWrite).put("candidate", candidate).put("resolution", resolution ?: JSONObject.NULL)
            .put("evidenceHash", "cd".repeat(32)).put("detail", "evidence")
            .put("historyBinding", JSONObject.NULL).put("acceptedPredecessor", JSONObject.NULL).put("unresolvedPredecessor", JSONObject.NULL)

    private fun v17Reservation(write: JSONObject, phase: String) = JSONObject()
        .put("id", write.getString("reservationId")).put("counter", write.getLong("counter")).put("phase", phase)
        .put("operationId", write.getString("operationId")).put("characteristic", write.getString("characteristic"))
        .put("purpose", write.getString("purpose")).put("payloadHash", write.getString("payloadHash"))
        .put("priorWrite", write.getLong("priorWrite")).put("candidate", write.getString("candidate"))
        .put("historyBinding", JSONObject.NULL).put("acceptedPredecessor", JSONObject.NULL).put("unresolvedPredecessor", JSONObject.NULL)

    private fun v17Record(write: Long, reservation: JSONObject?, evidence: List<JSONObject>, exponent: Int = 2) = JSONObject()
        .put("pump", "pump").put("key", PumpSession.fingerprint(KEY)).put("generation", "generation")
        .put("reboot", 21).put("read", 812).put("write", write)
        .put("reservation", reservation ?: JSONObject.NULL)
        .put("retiredLegacyBenchAlarmCursorRecovery", JSONObject.NULL)
        .put("serial", "10175983").put("keyHex", "01".repeat(32))
        .put("createdAt", 1).put("importedAt", 2).put("source", JSONObject()).put("verifiedAt", 3).put("verifiedSerial", "10175983")
        .put("benchNewEpochBootstrapReference", JSONObject.NULL)
        .put("benchHistoryCounts", JSONArray()).put("benchHistorySelectorStates", JSONArray())
        .put("writeBootstrapState", "ESTABLISHED")
        .put("benchNewEpochBootstrapAttempted", false).put("benchStrictNextAccepted", true).put("benchForwardGapAttempted", true)
        .put("benchDuplicateCounterAttempted", true).put("benchAmbiguityConvergenceAttempted", true)
        .put("benchDuplicateCounterPredecessor", JSONObject.NULL)
        .put("counterRecoveryExponent", exponent).put("lowerBoundRecoveryReboot", JSONObject.NULL)
        .put("writeEvidence", JSONArray(evidence))

    private fun v17Body(record: JSONObject) = JSONObject()
        .put("version", 17).put("records", JSONArray().put(record)).put("activeGeneration", "generation")
        .put("availability", JSONObject().put("causes", JSONArray()).put("since", 3).put("code", JSONObject.NULL)
            .put("operation", JSONObject.NULL).put("firmware", JSONObject.NULL).put("failures", 0).put("retryAt", JSONObject.NULL))
        .put("candidateGeneration", JSONObject.NULL).put("candidateReplacesGeneration", JSONObject.NULL)
        .put("candidateAttemptId", JSONObject.NULL).put("lastAttempt", JSONObject.NULL).put("candidateAvailability", JSONObject.NULL)

    private fun committedBody(storage: Storage): org.json.JSONObject {
        val envelope = org.json.JSONObject(checkNotNull(storage.file))
        return org.json.JSONObject(storage.open(envelope.getString("anchor"), envelope.getString("sealed")))
    }

    private fun replaceBody(
        storage: Storage,
        body: org.json.JSONObject,
    ) {
        val alias = org.json.JSONObject(checkNotNull(storage.file)).getString("anchor")
        storage.file = org.json.JSONObject().put("anchor", alias).put("sealed", storage.seal(alias, body.toString())).toString()
    }

    private companion object {
        val KEY = ByteArray(32) { 1 }
        const val EVENT_INDEX = "669a0c20-0008-969e-e211-fcbecc3b7bc5"
        const val ALARM_INDEX = "669a0c20-0008-969e-e211-fcbec93b7bc5"
        const val TBR_COMMAND = "669a0c20-0008-969e-e211-fcbee38b7bc5"
    }
}
